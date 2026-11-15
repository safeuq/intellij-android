package intellijandroid.client.session

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val SERVICE = "com.intellij.platform.completion.common.split.RpcCompletionService"
private const val PROTOCOL = "com.intellij.platform.completion.common.protocol"

/**
 * Code completion over Fleet RPC (`RpcCompletionService`), the way JetBrains Client 263.7307.200 does it
 * (docs/split-protocol.md, "Code completion"). One popup at a time. Runs on the rd scheduler thread.
 *
 * - The backend asks for the autopopup after a typed letter (`autopopupRequests`); Ctrl+Space asks directly.
 * - A popup is a session: `OpenSession`, `StartRequest`, then items and their order arrive on `responseFlow`.
 * - Typing more sends `PrefixChanged`; the backend answers with a new order (`NewArrangement`).
 * - Choosing an item sends `ItemSelected` and inserts it as an rd edit (`EditorChooseLookupItem`).
 */
class Completion(
    fleet: FleetRpc,
    /** `RdCodeWithMeProject.kernelProjectId`, e.g. `m3nniruk9k7j4qsibjui`. */
    private val kernelProjectId: String,
    private val editSessionFor: (kernelEditorId: String) -> Pair<String, EditSession>?,
    private val publish: (Lookup?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val commands = fleet.openStream(SERVICE, "commandFlow", "commands")
    private var popup: Popup? = null

    init {
        fleet.subscribe(SERVICE, "responseFlow", JsonObject(emptyMap())) { onResponse(it.jsonObject) }
        fleet.subscribe(SERVICE, "autopopupRequests", buildJsonObject { put("projectId", id(kernelProjectId)) }) { onAutopopupRequest(it.jsonObject) }
    }

    private inner class Popup(val sessionId: String, val path: String, val edit: EditSession, var requestId: String, var prefix: String, val explicit: Boolean) {
        val items = LinkedHashMap<String, LookupItem>()
        var weights = emptyMap<String, Int>()
        var nonMatched = emptySet<String>()
        var selectedId: String? = null
        var itemsFinished = false
        var focused = explicit
        var advertisement: String? = null

        /**
         * Items to show, in the backend's order (ascending weight). The backend's matched set wins: it also matches in
         * the middle of a name ("gre" → `Color.green`). Items it hasn't ranked yet are checked here.
         */
        fun visible(): List<LookupItem> = items.values
            .filter { it.id !in nonMatched && (it.id in weights || matchesPrefix(prefix, it.lookupString)) }
            // Grouped items ("Commands") come after the others, each group under its header.
            .sortedWith(compareBy<LookupItem>({ it.group != null }, { it.groupOrder }, { weights[it.id] ?: Int.MAX_VALUE }))
    }

    val isOpen get() = popup != null

    /** Ctrl+Space: a new popup, or more results (invocation 2) when one is open. */
    fun complete(path: String, edit: EditSession) {
        val current = popup
        if (current != null && current.edit === edit) {
            current.requestId = FleetRpc.newUid()
            sendStartRequest(current, invocationCount = 2)
        } else {
            edit.afterPendingEdits { open(path, edit, invocationCount = 1) }
        }
    }

    /** A character typed into the editor of [edit]; returns false when the caller should type it as usual. */
    fun typed(edit: EditSession, character: Char): Boolean {
        val current = popup?.takeIf { it.edit === edit } ?: return false
        if (!Character.isJavaIdentifierPart(character)) {
            // ponytail: IntelliJ inserts the selected item first for some characters ('.', '(' in a focused popup).
            cancel()
            return false
        }
        edit.type(character)
        current.prefix += character
        command("PrefixChanged") { put("requestId", id(current.requestId)); put("update", buildJsonObject { put("type", "$PROTOCOL.RpcPrefixUpdate.Append"); put("char", character.toString()) }) }
        show(current)
        return true
    }

    /** Backspace in the editor of [edit] while a popup is open; returns false when the caller should handle it. */
    fun backspace(edit: EditSession): Boolean {
        val current = popup?.takeIf { it.edit === edit } ?: return false
        if (current.prefix.isEmpty()) {
            cancel()
            return false
        }
        edit.backspace()
        current.prefix = current.prefix.dropLast(1)
        command("PrefixChanged") { put("requestId", id(current.requestId)); put("update", buildJsonObject { put("type", "$PROTOCOL.RpcPrefixUpdate.Truncate") }) }
        show(current)
        return true
    }

    fun key(key: LookupKey) {
        val current = popup ?: return
        val visible = current.visible()
        when (key) {
            LookupKey.UP, LookupKey.DOWN -> {
                if (visible.isEmpty()) return
                val index = visible.indexOfFirst { it.id == current.selectedId }.coerceAtLeast(0)
                val next = (index + if (key == LookupKey.UP) -1 else 1).mod(visible.size)
                current.focused = true
                select(current, visible[next].id)
            }
            LookupKey.ACCEPT -> visible.firstOrNull { it.id == current.selectedId }?.let(::accept) ?: visible.firstOrNull()?.let(::accept) ?: cancel()
            LookupKey.CANCEL -> cancel()
        }
    }

    /** A tap or click on a row. */
    fun choose(itemId: String) {
        popup?.items?.get(itemId)?.let(::accept)
    }

    /** Closes the popup without inserting anything (Esc, a click elsewhere, undo, …). */
    fun cancel() {
        val current = popup ?: return
        command("LookupEvent") { put("event", buildJsonObject { put("type", "$PROTOCOL.RpcLookupElementEvent.Cancel"); put("projectId", id(kernelProjectId)); put("requestId", id(current.requestId)) }) }
        close(current)
    }

    private fun accept(item: LookupItem) {
        val current = popup ?: return
        // Same order as JetBrains Client: the session ends, the backend learns the chosen item, then the text edit goes
        // out over rd. The backend runs the item's insert handler (parentheses, imports) and sends those edits back.
        close(current)
        command("LookupEvent") {
            put("event", buildJsonObject {
                put("type", "$PROTOCOL.RpcLookupElementEvent.ItemSelected")
                put("projectId", id(kernelProjectId))
                put("requestId", id(current.requestId))
                put("selectedItemId", id(item.id))
                put("itemPattern", current.prefix)
            })
        }
        current.edit.chooseLookupItem(current.prefix, item.lookupString)
    }

    private fun onAutopopupRequest(request: JsonObject) {
        // {"editorId":{"id":"70vmfjiscs4p4bgvtqfk"},"completionType":"BASIC","caretModelVersion":57,"documentVersion":211}
        val editorId = request["editorId"]!!.jsonObject.string("id")
        val (path, edit) = editSessionFor(editorId) ?: return
        // Like JetBrains Client: a request for an editor state that has changed since is stale.
        if (request["caretModelVersion"]!!.jsonPrimitive.int != edit.caretModelVersion || request["documentVersion"]!!.jsonPrimitive.int != edit.documentVersionValue) return
        if (popup?.edit === edit) return
        open(path, edit, invocationCount = 0)
    }

    private fun open(path: String, edit: EditSession, invocationCount: Int) {
        if (popup != null) cancel()
        val prefix = edit.text.substring(0, edit.caret).takeLastWhile(Character::isJavaIdentifierPart)
        val current = Popup(FleetRpc.newUid(), path, edit, FleetRpc.newUid(), prefix, explicit = invocationCount > 0)
        popup = current
        command("OpenSession") { put("sessionId", id(current.sessionId)); put("editorId", id(edit.kernelEditorId!!)); put("projectId", id(kernelProjectId)) }
        sendStartRequest(current, invocationCount)
    }

    private fun sendStartRequest(current: Popup, invocationCount: Int) {
        command("StartRequest") {
            put("request", buildJsonObject {
                put("id", id(current.requestId))
                put("sessionId", id(current.sessionId))
                put("editorId", id(current.edit.kernelEditorId!!))
                put("projectId", id(kernelProjectId))
                put("startingEditorVersion", current.edit.caretModelVersion)
                put("startingDocumentVersion", current.edit.documentVersionValue)
                put("completionType", "BASIC")
                put("invocationCount", invocationCount)
                put("isWriteActionRestart", false)
            })
        }
    }

    private fun onResponse(element: JsonObject) {
        val current = popup ?: return
        if (element["sessionId"]?.jsonObject?.string("id") != current.sessionId) return
        val event = element["event"]!!.jsonObject
        if (event["requestId"]?.jsonObject?.string("id") != current.requestId) return
        when (event.string("type").removePrefix("$PROTOCOL.RpcCompletionResponseEvent.")) {
            "NewItems" -> {
                event["newItems"]!!.jsonArray.forEach { item -> toLookupItem(item.jsonObject).let { current.items[it.id] = it } }
                arrange(current, event["completionArrangement"]!!.jsonObject)
            }
            "NewArrangement" -> arrange(current, event["completionArrangement"]!!.jsonObject)
            "Advertisement" -> current.advertisement = event.string("message")
            "CompletionItemsFinished" -> {
                current.itemsFinished = true
                // IntelliJ shows no autopopup without items.
                if (current.visible().isEmpty()) {
                    if (current.explicit) log("No suggestions")
                    cancel()
                    return
                }
            }
            "CompletionFinished" -> {
                // The backend restarted the request after a write action: follow the new request id.
                val reason = event["reason"]!!.jsonObject
                if (reason.string("type").endsWith("RestartingAfterWriteAction")) current.requestId = reason["request"]!!.jsonObject["id"]!!.jsonObject.string("id")
            }
            // BackendSettings, AddWatchedPrefix, Advertisement, ExpensivePresentations, ModCommandResults: not used yet.
            else -> return
        }
        show(current)
    }

    private fun arrange(current: Popup, arrangement: JsonObject) {
        // {"weightsOfMatchedItems":[{"id":{"id":"i1"},"weight":0}],"nonMatchedItems":[{"id":"i9"}],"startMatches":[…]}
        current.weights = arrangement["weightsOfMatchedItems"]?.jsonArray.orEmpty().associate { it.jsonObject["id"]!!.jsonObject.string("id") to it.jsonObject["weight"]!!.jsonPrimitive.int }
        current.nonMatched = arrangement["nonMatchedItems"]?.jsonArray.orEmpty().map { it.jsonObject.string("id") }.toSet()
    }

    private fun show(current: Popup) {
        if (popup !== current) return
        val visible = current.visible()
        if (visible.none { it.id == current.selectedId }) visible.firstOrNull()?.let { select(current, it.id, publishNow = false) }
        publish(Lookup(current.path, visible, visible.indexOfFirst { it.id == current.selectedId }.coerceAtLeast(0), current.prefix, current.focused, current.advertisement))
    }

    private fun select(current: Popup, itemId: String, publishNow: Boolean = true) {
        current.selectedId = itemId
        command("LookupEvent") {
            put("event", buildJsonObject {
                put("type", "$PROTOCOL.RpcLookupElementEvent.LookupStateChanged")
                put("requestId", id(current.requestId))
                put("selectedItemId", buildJsonObject { put("value", id(itemId)) })
                // Recorded: SEMI_FOCUSED for the autopopup (Enter still inserts); explicit completion is FOCUSED.
                put("focusDegree", if (current.focused) "FOCUSED" else "SEMI_FOCUSED")
                put("sortedItemIds", buildJsonArray { current.visible().forEach { add(id(it.id)) } })
            })
        }
        if (publishNow) show(current)
    }

    private fun close(current: Popup) {
        command("CloseSession") { put("sessionId", id(current.sessionId)) }
        if (popup === current) popup = null
        publish(null)
    }

    private fun command(type: String, fields: JsonObjectBuilder.() -> Unit) =
        commands.send(buildJsonObject { put("type", "$PROTOCOL.RpcCompletionCommand.$type"); fields() })

    private fun id(value: String) = buildJsonObject { put("id", value) }
}

private fun toLookupItem(item: JsonObject): LookupItem {
    val presentation = item["presentation"]!!.jsonObject
    fun flag(name: String) = presentation[name]?.jsonPrimitive?.boolean ?: false
    return LookupItem(
        id = item["id"]!!.jsonObject.string("id"),
        lookupString = item.string("lookupString"),
        itemText = presentation["itemText"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: item.string("lookupString"),
        tail = presentation["tail"]?.jsonArray.orEmpty().joinToString("") { it.jsonObject.string("text") },
        typeText = presentation["typeText"]?.jsonPrimitive?.content.orEmpty(),
        iconPath = iconPath(presentation["icon"]),
        bold = flag("itemTextBold"),
        strikeout = flag("strikeout"),
        // {"order":1,"displayName":"Commands"}
        group = (item["completionGroup"] as? JsonObject)?.string("displayName"),
        groupOrder = (item["completionGroup"] as? JsonObject)?.get("order")?.jsonPrimitive?.int ?: 0,
    )
}

/**
 * The path inside an `IconId`. Its `serializedValue` is a binary blob in base 64 holding the path and plugin id as
 * UTF-16LE strings, e.g. `expui/nodes/variable.svg` and `com.intellij`. The first string ending in `.svg` is the path.
 */
internal fun iconPath(icon: JsonElement?): String? {
    val blob = (icon as? JsonObject)?.get("serializedValue")?.takeIf { it != JsonNull }?.jsonPrimitive?.content ?: return null
    val bytes = Base64.getDecoder().decode(blob)
    val text = StringBuilder()
    val strings = mutableListOf<String>()
    var index = 0
    while (index + 1 < bytes.size) {
        val low = bytes[index].toInt() and 0xFF
        val isCharacter = bytes[index + 1].toInt() == 0 && low in 0x20..0x7E
        if (isCharacter) {
            text.append(low.toChar())
            index += 2
        } else {
            if (text.length >= 4) strings += text.toString()
            text.clear()
            index += 1
        }
    }
    if (text.length >= 4) strings += text.toString()
    return strings.firstOrNull { it.endsWith(".svg") }
}

/**
 * IntelliJ-style camel-hump matching, close enough to show the right rows while the backend's order is on its way:
 * each typed letter matches the next letter of [name], or the start of a later word part (an upper-case letter, or the
 * letter after `_` or `.`). Case is ignored. Returns the matched positions (painted blue in the popup), or null.
 * Examples: "gre" in "greeting" → {0, 1, 2}; "gre" in "getRegistry" → {0, 3, 4}; "gx" in "greeting" → null.
 */
internal fun matchedIndices(prefix: String, name: String): Set<Int>? {
    if (prefix.isEmpty()) return emptySet()
    if (name.isEmpty() || !name[0].equals(prefix[0], ignoreCase = true)) return null
    val matched = mutableSetOf(0)
    var index = 1
    for (character in prefix.drop(1)) {
        val position = if (index < name.length && name[index].equals(character, ignoreCase = true)) {
            index
        } else {
            (index until name.length).firstOrNull { candidate ->
                (name[candidate].isUpperCase() || name[candidate - 1] == '_' || name[candidate - 1] == '.') && name[candidate].equals(character, ignoreCase = true)
            } ?: return null
        }
        matched += position
        index = position + 1
    }
    return matched
}

internal fun matchesPrefix(prefix: String, name: String) = matchedIndices(prefix, name) != null
