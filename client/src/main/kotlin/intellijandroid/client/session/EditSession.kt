package intellijandroid.client.session

import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.CaretTimestampModel
import com.jetbrains.rd.ide.model.ControlTimestampModel
import com.jetbrains.rd.ide.model.DocOffsetAndVirtualModel
import com.jetbrains.rd.ide.model.DocumentTimestampModel
import com.jetbrains.rd.ide.model.EditorComponentId
import com.jetbrains.rd.ide.model.FileEditorTimestampModel
import com.jetbrains.rd.ide.model.KernelAwareTextControlId
import com.jetbrains.rd.ide.model.OffsetTimestampModel
import com.jetbrains.rd.ide.model.ProjectTimestampModel
import com.jetbrains.rd.ide.model.RangeMarkerTimestampModel
import com.jetbrains.rd.ide.model.RdApplyPatchRequest
import com.jetbrains.rd.ide.model.RdCallEditorActionRequest
import com.jetbrains.rd.ide.model.RdCaret
import com.jetbrains.rd.ide.model.RdCaretId
import com.jetbrains.rd.ide.model.RdCaretState
import com.jetbrains.rd.ide.model.RdCaretStateChange
import com.jetbrains.rd.ide.model.RdCaretStateSnapshot
import com.jetbrains.rd.ide.model.RdCmdGroupId
import com.jetbrains.rd.ide.model.RdCmdId
import com.jetbrains.rd.ide.model.RdCmdItem
import com.jetbrains.rd.ide.model.RdCmdItemVersion
import com.jetbrains.rd.ide.model.RdCmdVersion
import com.jetbrains.rd.ide.model.RdCommandName
import com.jetbrains.rd.ide.model.RdDelGroupId
import com.jetbrains.rd.ide.model.RdDocGroupId
import com.jetbrains.rd.ide.model.RdDocumentChange
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdDocumentVersion
import com.jetbrains.rd.ide.model.RdEditorVersion
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileEditorId
import com.jetbrains.rd.ide.model.RdFileEditorIdStrict
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdPatchCmdFinish
import com.jetbrains.rd.ide.model.RdPatchCmdStart
import com.jetbrains.rd.ide.model.RdPatchDocumentVersion
import com.jetbrains.rd.ide.model.RdPatchEditorVersion
import com.jetbrains.rd.ide.model.RdPatchItem
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdSelection
import com.jetbrains.rd.ide.model.RdSelectionChangeBase
import com.jetbrains.rd.ide.model.RdStackSizeItem
import com.jetbrains.rd.ide.model.RdTextModificationKind
import com.jetbrains.rd.ide.model.RdTextRange
import com.jetbrains.rd.ide.model.RdTypeKeyRequest
import com.jetbrains.rd.ide.model.RdUndoConfirmationPolicy
import com.jetbrains.rd.ide.model.RdUndoMeta
import com.jetbrains.rd.ide.model.RdUndoRedoRequest
import com.jetbrains.rd.ide.model.TextControlId
import com.jetbrains.rd.ide.model.VirtualFileArrayTimestampModel
import com.jetbrains.rd.ide.model.VirtualFileTimestampModel

private val DEBUG = System.getenv("CLIENT_EDIT_DEBUG") != null

/** Ids of the client's commands, shared by all editors: negative and counting down, like JetBrains Client's. */
class CommandChain {
    private var nextId = -2L

    /** The id of the last command the client finished; each patch expects it (`RdCmdItemVersion`). */
    var lastFinished = 0L
        private set

    fun next(): Long = nextId--

    fun finished(commandId: Long) {
        lastFinished = commandId
    }
}

/** What the backend needs to identify an open file's editor in commands and actions. */
class EditorIdentity(val documentId: RdDocumentId, val editorId: TextControlId, val fileEditorId: RdFileEditorId, val projectId: RdProjectId) {
    val fileId get() = fileEditorId.compositeId.fileId

    companion object {
        const val TEXT_EDITOR_PROVIDER = "text-editor"

        /**
         * For an editor the backend opened by itself, the file editor id comes from the text control id:
         * `KernelAwareTextControlId(documentId = file, tabIndex, clientId, projectId)` → `RdFileEditorCompositeId(file, clientId, tabIndex, projectId)`.
         */
        fun of(documentId: RdDocumentId, editorId: TextControlId, knownComposite: RdFileEditorCompositeId?): EditorIdentity? {
            val control = editorId as? KernelAwareTextControlId ?: return null
            val fileId = (control.documentId as? RdDocumentIdWithFileId)?.fileId ?: return null
            val projectId = control.projectId ?: return null
            val composite = knownComposite ?: RdFileEditorCompositeId(fileId, control.clientId, control.tabIndex, projectId)
            return EditorIdentity(documentId, editorId, RdFileEditorId(composite, TEXT_EDITOR_PROVIDER, 1), projectId)
        }
    }
}

/**
 * Local-first editing of one open editor over IntelliJ's patch engine (docs/split-protocol.md, "Typing").
 * Each edit is applied here first, sent as the speculative patch of a request, and confirmed by the backend's answer.
 * If an answer differs (the backend auto-closed a brace, indented differently, …), the pending local edits are undone
 * and the backend's patches are applied instead until nothing is pending.
 *
 * ponytail: IntelliJ replays the later pending edits on top of the backend's result; here they are dropped in favour of
 * the backend's answers, and typing is ignored while that catch-up runs (usually a few milliseconds).
 */
class EditSession(
    private val identity: EditorIdentity,
    private val commands: CommandChain,
    /** Sends a request and returns its request id. */
    private val send: (RdRequest) -> Long,
    /** Called after every change of text or caret, with the change (offset, removed length, inserted length) if any. */
    private val changed: (TextEdit?) -> Unit,
) {
    data class TextEdit(val offset: Int, val removedLength: Int, val insertedLength: Int)

    /** A request the backend hasn't answered yet; [undoState] is the editor state before its local change. */
    private class Pending(val requestId: Long, val speculative: RdPatch, val undoState: Snapshot, val edit: TextEdit?)

    private data class Snapshot(val text: String, val version: RdDocumentVersion, val caret: Int, val selectionStart: Int, val selectionEnd: Int, val editorVersion: RdEditorVersion)

    var text = ""
        private set
    var caret = 0
        private set
    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set
    private var documentVersion = RdDocumentVersion(-1, 0)
    private var uniqueId = ""
    private var editorVersion = RdEditorVersion(-1, 0, -1)
    private var undoSizes = listOf(0, 0)
    private var redoSizes = listOf(0, 0)
    private val pending = ArrayDeque<Pending>()
    private var followingBackend = false

    val isReady get() = documentVersion.value >= 0

    fun hasPending(requestId: Long) = pending.any { it.requestId == requestId }

    /** Whether a backend patch touches this document or editor. */
    fun concerns(patch: RdPatch): Boolean = patch.changes.any { item ->
        (item is RdDocumentChange && item.documentId == identity.documentId) ||
            (item is RdResetDocumentChange && item.documentId == identity.documentId) ||
            (item is RdSelectionChangeBase && item.editorId == identity.editorId)
    }

    // ---- User actions ----

    fun type(character: Char) {
        if (!canEdit()) return
        val (offset, removed) = replacedRange()
        edit(offset, removed, character.toString(), offset + 1, startName = "#", finishName = "#Typing",
            startGroup = RdDocGroupId(identity.fileId), finishGroup = RdDocGroupId(identity.fileId)) { patch ->
            RdTypeKeyRequest(character, identity.editorId, patch)
        }
        sendTransparentMarker()
    }

    fun enter() {
        if (!canEdit()) return
        val (offset, removed) = replacedRange()
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val indent = text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
        editorAction("EditorEnter", offset, removed, "\n" + indent, offset + 1 + indent.length, "#Enter", "#Typing", null, null)
    }

    fun backspace() {
        if (!canEdit()) return
        if (selectionStart != selectionEnd) {
            editorAction("EditorBackSpace", selectionStart, text.substring(selectionStart, selectionEnd), "", selectionStart, "#Backspace", "#Backspace", null, RdDelGroupId())
        } else if (caret > 0) {
            editorAction("EditorBackSpace", caret - 1, text.substring(caret - 1, caret), "", caret - 1, "#Backspace", "#Backspace", null, RdDelGroupId())
        }
    }

    fun delete() {
        if (!canEdit()) return
        if (selectionStart != selectionEnd) {
            editorAction("EditorDelete", selectionStart, text.substring(selectionStart, selectionEnd), "", selectionStart, "#Delete", "#Delete", null, RdDelGroupId())
        } else if (caret < text.length) {
            editorAction("EditorDelete", caret, text.substring(caret, caret + 1), "", caret, "#Delete", "#Delete", null, RdDelGroupId())
        }
    }

    /** Moves the caret, with a selection when [anchor] differs from [offset]. */
    fun moveCaret(offset: Int, anchor: Int = offset) {
        if (!canEdit()) return
        val target = offset.coerceIn(0, text.length)
        val from = minOf(anchor.coerceIn(0, text.length), target)
        val to = maxOf(anchor.coerceIn(0, text.length), target)
        if (target == caret && from == selectionStart && to == selectionEnd) return
        val before = snapshot()
        val oldState = caretState()
        val editorBefore = editorVersion
        caret = target
        selectionStart = from
        selectionEnd = to
        val newState = caretState()
        editorVersion = RdEditorVersion(editorBefore.caretModelVersion + 1, documentVersion.hash, caretHash())
        val commandId = commands.next()
        val patch = RdPatch(
            changes = listOf(commandStart(commandId, "#", null), RdCaretStateChange(oldState, newState, identity.editorId), commandFinish(commandId, "#", null)),
            features = emptyList(),
            expectedItemVersions = listOf(commandVersion(), RdPatchEditorVersion(editorBefore, identity.editorId), documentItemVersion(documentVersion)),
            updatedItemVersions = listOf(RdPatchEditorVersion(editorVersion, identity.editorId)),
        )
        commands.finished(commandId)
        submit(RdApplyPatchRequest(patch), patch, before, null)
        changed(null)
    }

    fun undo(isUndo: Boolean = true) {
        if (!canEdit()) return
        // No local change: the backend's answer carries the undo. Pending local edits are rolled back first so their
        // answers are applied in order, not on top of the local text.
        if (pending.isNotEmpty()) rollBackPending()
        val request = RdUndoRedoRequest(RdFileEditorIdStrict(identity.fileEditorId, false), listOf(identity.fileId), undoSizes, redoSizes, isUndo, identity.projectId)
        pending.addLast(Pending(send(request), RdPatch(emptyList(), emptyList(), emptyList(), emptyList()), snapshot(), null))
        followingBackend = true
    }

    // ---- From the backend ----

    /** A patch the backend sent on its own (`backendPatches`): opening the file, changes made on the backend, … */
    fun backendPatch(patch: RdPatch) {
        debug { "backend patch ${describe(patch)} with ${pending.size} pending" }
        if (pending.isNotEmpty()) rollBackPending()
        apply(patch)
    }

    /** The answer to request [requestId]. */
    fun response(requestId: Long, patch: RdPatch?) {
        val request = pending.firstOrNull()?.takeIf { it.requestId == requestId } ?: return debug { "answer #$requestId out of order; pending ${pending.map { it.requestId }}" }
        pending.removeFirst()
        debug { "answer #$requestId ${patch?.let(::describe)} same=${patch != null && sameEdit(request.speculative, patch)} following=$followingBackend" }
        if (!followingBackend && patch != null && sameEdit(request.speculative, patch)) {
            // Confirmed: the local text is already right; take the backend's versions.
            takeVersions(patch)
        } else {
            if (!followingBackend) {
                pending.addFirst(request)
                rollBackPending()
                pending.removeFirst()
            }
            patch?.let(::apply)
        }
        if (pending.isEmpty()) followingBackend = false
    }

    fun retryOrFailure(requestId: Long) {
        // ponytail: no retry; give up on local speculation and wait for the backend's state.
        if (pending.any { it.requestId == requestId }) {
            rollBackPending()
            pending.removeAll { it.requestId == requestId }
            if (pending.isEmpty()) followingBackend = false
        }
    }

    // ---- Internals ----

    private fun canEdit() = isReady && !followingBackend

    private fun replacedRange(): Pair<Int, String> =
        if (selectionStart != selectionEnd) selectionStart to text.substring(selectionStart, selectionEnd) else caret to ""

    private fun editorAction(actionId: String, offset: Int, oldText: String, newText: String, caretAfter: Int, startName: String, finishName: String, startGroup: RdCmdGroupId?, finishGroup: RdCmdGroupId?) {
        val timestamps = actionTimestamps()
        edit(offset, oldText, newText, caretAfter, startName, finishName, startGroup, finishGroup) { patch ->
            RdCallEditorActionRequest(actionId, identity.editorId, patch, timestamps)
        }
        sendTransparentMarker()
    }

    private fun edit(
        offset: Int, oldText: String, newText: String, caretAfter: Int,
        startName: String, finishName: String, startGroup: RdCmdGroupId?, finishGroup: RdCmdGroupId?,
        request: (RdPatch) -> RdRequest,
    ) {
        val before = snapshot()
        val stateBefore = caretState()
        val editorBefore = editorVersion
        val oldVersion = documentVersion
        // Replacing a selection is two document changes on the backend: delete the selection, then insert. Recorded:
        // typing 'x' over ");" at 208 answers change(208, -');', +'') v0->1, then change(208, +'x') v1->2.
        val parts = if (oldText.isNotEmpty() && newText.isNotEmpty()) listOf(oldText to "", "" to newText) else listOf(oldText to newText)
        val documentChanges = parts.map { (removed, inserted) ->
            val from = documentVersion
            val to = RdDocumentVersion(from.value + 1, VersionHashes.change(from.hash, offset, offset, text.length, removed, inserted))
            val change = RdDocumentChange(offset, offset, inserted, removed, RdTextModificationKind.NotSpecified, text.length, null, false, identity.documentId, to, from, uniqueId)
            text = text.replaceRange(offset, offset + removed.length, inserted)
            documentVersion = to
            change
        }
        val newVersion = documentVersion
        caret = caretAfter
        selectionStart = caretAfter
        selectionEnd = caretAfter
        editorVersion = RdEditorVersion(editorBefore.caretModelVersion + 2, newVersion.hash, caretHash())
        val commandId = commands.next()
        val patch = RdPatch(
            changes = listOf(
                commandStart(commandId, startName, startGroup),
                RdCaretStateSnapshot(true, stateBefore, identity.editorId),
                *documentChanges.toTypedArray(),
                RdCaretStateSnapshot(false, caretState(), identity.editorId),
                commandFinish(commandId, finishName, finishGroup),
            ),
            features = emptyList(),
            expectedItemVersions = listOf(commandVersion(), RdPatchEditorVersion(editorBefore, identity.editorId), documentItemVersion(oldVersion)),
            updatedItemVersions = listOf(RdPatchEditorVersion(editorVersion, identity.editorId), documentItemVersion(newVersion)),
        )
        commands.finished(commandId)
        val textEdit = TextEdit(offset, oldText.length, newText.length)
        submit(request(patch), patch, before, textEdit)
        changed(textEdit)
    }

    /** After every edit command JetBrains Client sends an empty transparent command; it advances the command chain. */
    private fun sendTransparentMarker() {
        val commandId = commands.next()
        val meta = undoMeta()
        val patch = RdPatch(
            changes = listOf(
                RdCmdItem(RdPatchCmdStart(commandId, null, null, RdCommandName("#"), RdUndoConfirmationPolicy.DEFAULT, true, meta)),
                RdCmdItem(RdPatchCmdFinish(commandId, null, null, RdCommandName("#"), RdUndoConfirmationPolicy.DEFAULT, true, meta)),
            ),
            features = emptyList(),
            expectedItemVersions = listOf(commandVersion()),
            updatedItemVersions = emptyList(),
        )
        commands.finished(commandId)
        submit(RdApplyPatchRequest(patch), patch, snapshot(), null)
    }

    private fun submit(request: RdRequest, patch: RdPatch, before: Snapshot, edit: TextEdit?) {
        val requestId = send(request)
        debug { "send #$requestId ${request::class.simpleName} ${describe(patch)} -> text ${text.length} chars, caret $caret ($selectionStart..$selectionEnd)" }
        pending.addLast(Pending(requestId, patch, before, edit))
    }

    private fun describe(patch: RdPatch) = patch.changes.mapNotNull { item ->
        when (item) {
            is RdDocumentChange -> "change(${item.offset}, -'${item.oldText.replace("\n", "\\n")}', +'${item.newText.replace("\n", "\\n")}', len ${item.oldDocumentLength}, v${item.oldVersion.value}->${item.newVersion.value}, whole=${item.isWholeTextReplaced})"
            is RdResetDocumentChange -> "reset(${item.text.length} chars, v${item.newVersion.value})"
            is RdCaretStateSnapshot -> "caret(${item.state.carets.firstOrNull()?.let { "${it.offset.offset} ${it.selection.start.offset}..${it.selection.end.offset}" }})"
            is RdCaretStateChange -> "caretChange(${item.newState.carets.firstOrNull()?.let { "${it.offset.offset} ${it.selection.start.offset}..${it.selection.end.offset}" }})"
            else -> null
        }
    }

    private inline fun debug(message: () -> String) {
        if (DEBUG) println("[edit] ${message()}")
    }

    /** Undoes all pending local edits (newest first) and follows the backend's answers from here on. */
    private fun rollBackPending() {
        val oldest = pending.firstOrNull() ?: return
        // Tell the highlight bookkeeping how the text moves back, newest edit first.
        for (request in pending.reversed()) request.edit?.let { changed(TextEdit(it.offset, it.insertedLength, it.removedLength)) }
        restore(oldest.undoState)
        followingBackend = true
    }

    private fun apply(patch: RdPatch) {
        for (item in patch.changes) applyItem(item)
        takeVersions(patch)
        changed(null)
    }

    private fun applyItem(item: RdPatchItem) {
        when (item) {
            is RdResetDocumentChange -> if (item.documentId == identity.documentId) {
                val oldLength = text.length
                text = item.text
                documentVersion = item.newVersion
                uniqueId = item.uniqueId
                changed(TextEdit(0, oldLength, text.length))
            }
            is RdDocumentChange -> if (item.documentId == identity.documentId) {
                val end = (item.offset + item.oldText.length).coerceAtMost(text.length)
                text = text.replaceRange(item.offset, end, item.newText)
                documentVersion = item.newVersion
                changed(TextEdit(item.offset, item.oldText.length, item.newText.length))
            }
            is RdSelectionChangeBase -> if (item.editorId == identity.editorId) {
                val state = when (item) {
                    is RdCaretStateSnapshot -> item.state
                    is RdCaretStateChange -> item.newState
                    else -> null
                }
                state?.carets?.firstOrNull()?.let { primary ->
                    caret = primary.offset.offset
                    selectionStart = primary.selection.start.offset
                    selectionEnd = primary.selection.end.offset
                }
            }
            is RdStackSizeItem -> if (item.fileId == null) {
                undoSizes = listOf(item.undoSize, undoSizes[1]); redoSizes = listOf(item.redoSize, redoSizes[1])
            } else {
                undoSizes = listOf(undoSizes[0], item.undoSize); redoSizes = listOf(redoSizes[0], item.redoSize)
            }
            else -> Unit
        }
    }

    /** The backend's versions win over our own computation. */
    private fun takeVersions(patch: RdPatch) {
        for (version in patch.updatedItemVersions) {
            when {
                version is RdPatchEditorVersion && version.id == identity.editorId -> editorVersion = version.version
                version is RdPatchDocumentVersion && version.id == identity.documentId -> documentVersion = version.version
            }
        }
        for (item in patch.changes) if (item is RdStackSizeItem) applyItem(item)
    }

    /** Same document changes and same final caret: the backend did exactly what we did locally. */
    private fun sameEdit(speculative: RdPatch, answer: RdPatch): Boolean {
        fun edits(patch: RdPatch) = patch.changes.filterIsInstance<RdDocumentChange>().map { Triple(it.offset, it.oldText, it.newText) }
        fun lastCaret(patch: RdPatch) = patch.changes.filterIsInstance<RdSelectionChangeBase>().lastOrNull()?.let { item ->
            (item as? RdCaretStateSnapshot)?.state ?: (item as? RdCaretStateChange)?.newState
        }?.carets?.firstOrNull()?.let { Triple(it.offset.offset, it.selection.start.offset, it.selection.end.offset) }
        return edits(speculative) == edits(answer) && (lastCaret(speculative) == null || lastCaret(speculative) == lastCaret(answer))
    }

    private fun snapshot() = Snapshot(text, documentVersion, caret, selectionStart, selectionEnd, editorVersion)

    private fun restore(snapshot: Snapshot) {
        text = snapshot.text
        documentVersion = snapshot.version
        caret = snapshot.caret
        selectionStart = snapshot.selectionStart
        selectionEnd = snapshot.selectionEnd
        editorVersion = snapshot.editorVersion
    }

    private fun caretState(): RdCaretState {
        val id = RdCaretId(true, 1)
        fun position(offset: Int) = DocOffsetAndVirtualModel(offset, 0, false)
        return RdCaretState(id, listOf(RdCaret(id, position(caret), RdSelection(position(selectionStart), position(selectionEnd)))), documentVersion)
    }

    private fun caretHash() = VersionHashes.caret(1, caret, selectionStart, selectionEnd)

    private fun commandVersion() = RdCmdItemVersion(RdCmdVersion(commands.lastFinished), RdCmdId())

    private fun documentItemVersion(version: RdDocumentVersion) = RdPatchDocumentVersion(version, uniqueId, identity.documentId)

    private fun undoMeta(): List<RdUndoMeta> {
        val strict = RdFileEditorIdStrict(identity.fileEditorId, false)
        return listOf(RdUndoMeta(null, strict, emptyList(), false), RdUndoMeta(identity.projectId, strict, emptyList(), false))
    }

    private fun commandStart(commandId: Long, name: String, group: RdCmdGroupId?) =
        RdCmdItem(RdPatchCmdStart(commandId, identity.projectId, group, RdCommandName(name), RdUndoConfirmationPolicy.DEFAULT, false, undoMeta()))

    private fun commandFinish(commandId: Long, name: String, group: RdCmdGroupId?) =
        RdCmdItem(RdPatchCmdFinish(commandId, identity.projectId, group, RdCommandName(name), RdUndoConfirmationPolicy.DEFAULT, false, undoMeta()))

    /** The state the action is invoked in (caret before the action), as JetBrains Client sends it. */
    private fun actionTimestamps(): ActionTimestampSetModel {
        val document = DocumentTimestampModel(identity.documentId, documentVersion)
        return ActionTimestampSetModel(
            listOf(
                com.jetbrains.rd.ide.model.EditorTimestampModel(
                    identity.editorId,
                    listOf(CaretTimestampModel(RangeMarkerTimestampModel(RdTextRange(selectionStart, selectionEnd), true, document), OffsetTimestampModel(caret, false, document))),
                ),
                FileEditorTimestampModel(identity.fileEditorId),
                ProjectTimestampModel(identity.projectId),
                VirtualFileTimestampModel(identity.fileId),
                ControlTimestampModel(EditorComponentId(identity.editorId)),
                VirtualFileArrayTimestampModel(listOf(identity.fileId)),
            ),
        )
    }
}
