package intellijandroid.client.connection

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** `WireConnectStatus` ordinals, the status field of a system frame. */
enum class WireStatus {
    ACCEPTED, USER_DECLINED, PASSWORD_REQUIRED, JOIN_PIN, DECLINED, INTERNAL_ERROR, GUEST_LIMIT_REACHED, SESSION_TOKEN,
    TRANSPORT_ACCEPTANCE, TRANSPORT_INDEX, WIRE_EXIST, CURRENT_CONFIRMED_FRAME_ACK, FORCE_TERMINATE_WIRE, PRE_PROTOCOL_MESSAGE,
}

sealed interface Frame {
    data object Ping : Frame

    class System(val status: WireStatus, val data: ByteArray) : Frame {
        override fun toString() = "System($status, ${data.size} bytes)"
    }

    /** One rd message: `payload` is `RdId || message context || body`. */
    class Message(val sequence: Int, val lastReceivedSequence: Int, val payload: ByteArray) : Frame {
        override fun toString() = "Message(seq $sequence, ack $lastReceivedSequence, ${payload.size} bytes)"
    }
}

/**
 * Length-prefixed frames inside one raw DEFLATE stream per direction (docs/split-protocol.md, "Frames"):
 * `int32 N || body[N]`; N = 0 is a ping; a body starting with int32 -1 is a system frame `-1 || status || data`;
 * anything else is a message frame `seq || lastReceivedSeq || rdPayload`. All integers big-endian.
 */
class FrameChannel(rawInput: InputStream, rawOutput: OutputStream) : Closeable {
    // One Deflater/Inflater for the whole connection: the dictionary carries over. syncFlush = true ends every
    // flush with 00 00 FF FF, which the backend's inflater needs to see each frame at once. Level 1 like the client.
    private val output = DataOutputStream(BufferedOutputStream(DeflaterOutputStream(rawOutput, Deflater(1, true), 64 * 1024, true), 64 * 1024))
    private val input = DataInputStream(BufferedInputStream(InflaterInputStream(rawInput, Inflater(true), 64 * 1024), 64 * 1024))
    private val writeLock = Any()

    fun writePing() = synchronized(writeLock) {
        output.writeInt(0)
        output.flush()
    }

    fun writeSystem(status: WireStatus, data: ByteArray = ByteArray(0)) = synchronized(writeLock) {
        output.writeInt(8 + data.size)
        output.writeInt(-1)
        output.writeInt(status.ordinal)
        output.write(data)
        output.flush()
    }

    fun writeMessage(sequence: Int, lastReceivedSequence: Int, payload: ByteArray, length: Int) = synchronized(writeLock) {
        require(sequence > 0) { "message sequence numbers start at 1, got $sequence" }
        output.writeInt(8 + length)
        output.writeInt(sequence)
        output.writeInt(lastReceivedSequence)
        output.write(payload, 0, length)
        output.flush()
    }

    /** Blocks until the next frame arrives. Only one thread may read. */
    fun read(): Frame {
        val length = input.readInt()
        check(length in 0..MAX_FRAME_LENGTH) { "Bad frame length $length" }
        if (length == 0) return Frame.Ping
        check(length >= 8) { "Frame too short: $length bytes" }
        val body = ByteArray(length).also(input::readFully)
        val header = ByteBuffer.wrap(body)
        val first = header.getInt()
        val second = header.getInt()
        return if (first == -1) {
            val status = WireStatus.entries.getOrNull(second) ?: error("Unknown system frame status $second")
            Frame.System(status, body.copyOfRange(8, length))
        } else {
            Frame.Message(first, second, body.copyOfRange(8, length))
        }
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        const val MAX_FRAME_LENGTH = 300_000_000
    }
}
