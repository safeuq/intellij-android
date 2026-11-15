package intellijandroid.client.session

import com.jetbrains.rd.ide.model.FleetRpcModel
import com.jetbrains.rd.ide.model.FleetTransportMessage
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.reactive.IScheduler
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Fleet RPC, the JSON RPC that IntelliJ's newer split-mode features use, carried as strings on the rd model
 * `FleetRpcModel` (docs/split-protocol.md, "Code completion"). Only what completion needs: calls, streams from the
 * backend and streams to the backend. Runs on the rd scheduler thread.
 *
 * Example call as it goes out (the payload is itself a JSON string):
 * `{"type":"envelope","destination":"fleet","origin":"k0hd0jgd3mhmrigfhtuf","payload":"{\"type\":\"call\",\"requestId\":\"o5u598b5pkqlbedarjft\",
 * \"service\":\"…RpcCompletionService\",\"method\":\"responseFlow\",\"args\":{},\"meta\":{…},\"streamBudget\":100}"}`
 */
class FleetRpc(
    private val model: FleetRpcModel,
    lifetime: Lifetime,
    scheduler: IScheduler,
    /** The id of the last rd request sent; the backend applies rd requests up to it before running a call. */
    private val lastRdRequest: () -> Long,
    private val log: (String) -> Unit,
) {
    private val origin = newUid()
    private val clockId = newUid()
    private val pendingCalls = mutableMapOf<String, (JsonElement) -> Unit>()
    private val incomingStreams = mutableMapOf<String, IncomingStream>()
    private val outgoingStreams = mutableMapOf<String, OutgoingStream>()

    init {
        model.incoming.adviseOn(lifetime, scheduler) { message -> runCatching { receive(message.message) }.onFailure { log("Fleet RPC: cannot read ${message.message.take(300)}: $it") } }
    }

    fun call(service: String, method: String, args: JsonObject, onResult: (JsonElement) -> Unit = {}) {
        val requestId = newUid()
        pendingCalls[requestId] = onResult
        send(buildJsonObject {
            put("type", "call")
            put("requestId", requestId)
            put("service", service)
            put("method", method)
            put("args", args)
            put("meta", meta())
            put("streamBudget", BUDGET)
        })
    }

    /** Calls [method], whose result is a stream from the backend, and passes every element to [onElement]. */
    fun subscribe(service: String, method: String, args: JsonObject, onElement: (JsonElement) -> Unit) =
        call(service, method, args) { result ->
            val streamId = result.jsonPrimitive.content
            incomingStreams[streamId] = IncomingStream(onElement)
            send(streamMessage("stream_init", streamId))
        }

    /** Calls [method] with a new stream to the backend as its argument [argument]; elements go out with [OutgoingStream.send]. */
    fun openStream(service: String, method: String, argument: String): OutgoingStream {
        val stream = OutgoingStream(newUid())
        outgoingStreams[stream.streamId] = stream
        // Same order as JetBrains Client: the call carrying the stream id, then stream_init, then the elements.
        call(service, method, buildJsonObject { put(argument, stream.streamId) })
        send(streamMessage("stream_init", stream.streamId))
        return stream
    }

    inner class OutgoingStream(val streamId: String) {
        // The backend may hold at most BUDGET unread elements; it sends stream_next to allow more.
        private var budget = BUDGET
        private val waiting = ArrayDeque<JsonElement>()

        fun send(data: JsonElement) {
            waiting.addLast(data)
            flush()
        }

        fun refill(count: Int) {
            budget += count
            flush()
        }

        private fun flush() {
            while (budget > 0 && waiting.isNotEmpty()) {
                budget--
                this@FleetRpc.send(buildJsonObject { put("type", "stream_data"); put("streamId", streamId); put("data", waiting.removeFirst()) })
            }
        }
    }

    private class IncomingStream(val onElement: (JsonElement) -> Unit) {
        var remaining = BUDGET
    }

    private fun receive(text: String) {
        val envelope = Json.parseToJsonElement(text).jsonObject
        // Besides "envelope" the backend may send "opened"/"closed" for routes; nothing to do for those.
        if (envelope.string("type") != "envelope") return log("Fleet RPC: $text")
        val message = Json.parseToJsonElement(envelope.string("payload")).jsonObject
        when (message.string("type")) {
            "call_result" -> pendingCalls.remove(message.string("requestId"))?.invoke(message["result"] ?: JsonNull)
            "call_failure" -> pendingCalls.remove(message.string("requestId"))?.let { log("Fleet RPC call failed: ${message["error"]}") }
            "stream_data" -> {
                val streamId = message.string("streamId")
                val stream = incomingStreams[streamId] ?: return
                stream.onElement(message["data"] ?: JsonNull)
                // Like JetBrains Client: ask for 100 more when fewer than 10 are left.
                if (--stream.remaining < 10) {
                    stream.remaining += BUDGET
                    send(buildJsonObject { put("type", "stream_next"); put("streamId", streamId); put("count", BUDGET) })
                }
            }
            "stream_next" -> outgoingStreams[message.string("streamId")]?.refill(message["count"]!!.jsonPrimitive.int)
            "stream_closed" -> {
                val streamId = message.string("streamId")
                if (incomingStreams.remove(streamId) != null || outgoingStreams.remove(streamId) != null) log("Fleet RPC stream $streamId closed ${message["error"] ?: ""}")
            }
            "stream_init" -> Unit
            else -> log("Fleet RPC: unexpected ${message.string("type")}")
        }
    }

    private fun meta() = buildJsonObject {
        // No Rhizome database here, so no transactions to report; JetBrains Client sends zeros too before its first one.
        put("CompressedVectorClock", buildJsonObject { put("clientId", clockId); put("consumedTxs", 0); put("sentTxs", 0) })
        put("PatchEngineSynchronizationData", buildJsonObject { put("lastQueuedRequest", lastRdRequest()) })
    }

    private fun streamMessage(type: String, streamId: String) = buildJsonObject { put("type", type); put("streamId", streamId) }

    private fun send(payload: JsonObject) {
        val envelope = buildJsonObject {
            put("type", "envelope")
            put("destination", "fleet")
            put("origin", origin)
            put("payload", payload.toString())
        }
        model.outgoing.fire(FleetTransportMessage(envelope.toString()))
    }

    companion object {
        const val BUDGET = 100
        private val random = SecureRandom()
        private const val UID_ALPHABET = "0123456789abcdefghijklmnopqrstuv"

        /** A Fleet UID like JetBrains Client's `UID.random()`: 20 characters of base 32, e.g. `85ma8o6plbbp314b5hig`. */
        fun newUid(): String = buildString { repeat(20) { append(UID_ALPHABET[random.nextInt(32)]) } }
    }
}

internal fun JsonObject.string(key: String): String =
    requireNotNull(this[key]?.jsonPrimitive?.content) { "Missing \"$key\" in $this" }
