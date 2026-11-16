package intellijandroid.client.connection

import com.jetbrains.rd.framework.AbstractBuffer
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.UnsafeBuffer
import com.jetbrains.rd.framework.base.WireBase
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * rd wire over an accepted [Connection]: each rd message travels in one message frame
 * `seq || lastReceivedSeq || RdId || message context || body` (docs/split-protocol.md, "Where rd starts").
 *
 * ponytail: no resend buffer and no reconnect. A dropped connection ends the session; add the backend's
 * resend-after-reconnect (WIRE_EXIST = 1) when a flaky network makes that matter.
 */
class SplitWire(private val connection: Connection, private val onClosed: (Throwable?) -> Unit) : WireBase() {
    private val sendLock = Any()
    private var sentSequence = 0

    @Volatile
    private var lastReceivedSequence = 0

    @Volatile
    private var closed = false

    /** Starts the reader and ping threads. Call once the rd protocol exists (it starts message delivery itself). */
    fun start() {
        connection.socket.soTimeout = IDLE_TIMEOUT_MILLIS
        connected.set(true)
        thread(name = "split-wire-reader", isDaemon = true) { readLoop() }
        thread(name = "split-wire-pings", isDaemon = true) {
            while (!closed) {
                runCatching { connection.frames.writePing() }.onFailure { failure -> close(failure) }
                Thread.sleep(PING_INTERVAL_MILLIS)
            }
        }
    }

    override fun send(id: RdId, writer: (AbstractBuffer) -> Unit) {
        require(!id.isNull) { "rd id must not be null" }
        if (closed) return
        // Array-backed, so getArray() can hand the bytes to the frame writer (a native-memory buffer can't).
        UnsafeBuffer(ByteArray(256)).use { buffer ->
            id.write(buffer)
            contexts.writeCurrentMessageContext(buffer)
            writer(buffer)
            // ponytail: writes on the caller's thread; move to a send queue if a slow network stalls the rd scheduler.
            synchronized(sendLock) {
                sentSequence++
                runCatching { connection.frames.writeMessage(sentSequence, lastReceivedSequence, buffer.getArray(), buffer.position) }
                    .onFailure { failure -> close(failure) }
            }
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                when (val frame = connection.frames.read()) {
                    is Frame.Ping -> Unit
                    is Frame.System -> when (frame.status) {
                        WireStatus.FORCE_TERMINATE_WIRE -> error("Backend terminated the wire")
                        else -> Unit // e.g. a late JOIN_PIN; nothing to do once connected
                    }
                    is Frame.Message -> {
                        if (frame.sequence <= lastReceivedSequence) continue // duplicate after a resend
                        lastReceivedSequence = frame.sequence
                        val buffer = UnsafeBuffer(frame.payload)
                        val id = RdId.read(buffer)
                        messageBroker.dispatch(id, buffer, null)
                    }
                }
            }
        } catch (timeout: SocketTimeoutException) {
            close(IllegalStateException("Nothing received for ${IDLE_TIMEOUT_MILLIS / 1000} s", timeout))
        } catch (failure: Throwable) {
            close(failure)
        }
    }

    fun close(failure: Throwable? = null) {
        if (closed) return
        closed = true
        connected.set(false)
        connection.close()
        onClosed(failure)
    }

    private companion object {
        const val PING_INTERVAL_MILLIS = 1_000L
        const val IDLE_TIMEOUT_MILLIS = 15_000
    }
}
