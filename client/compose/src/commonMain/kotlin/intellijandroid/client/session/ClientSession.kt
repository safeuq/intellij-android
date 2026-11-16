package intellijandroid.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.codeWithMe.model.RdFindFileParams
import com.jetbrains.codeWithMe.model.RdProjectViewAsyncPane
import com.jetbrains.codeWithMe.model.RdProjectViewItem
import com.jetbrains.codeWithMe.model.SyntaxHighlighterModel
import com.jetbrains.codeWithMe.model.VfsModel
import com.jetbrains.codeWithMe.model.editorTabsModel
import com.jetbrains.codeWithMe.model.projectViewModel
import com.jetbrains.codeWithMe.model.vfsModel
import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.DefaultRegistryModel
import com.jetbrains.ide.model.highlighterRegistration.highlighterRegistrationHostModel
import com.jetbrains.rd.framework.IMarshaller
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.MarshallersProvider
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.ide.model.CompositionIconModel
import com.jetbrains.rd.ide.model.DeferredIconModel
import com.jetbrains.rd.ide.model.EmptyIconModel
import com.jetbrains.rd.ide.model.FailedRequestComposite
import com.jetbrains.rd.ide.model.GutterMarkHighlighterModel
import com.jetbrains.rd.ide.model.FileEditorManagerEventModel
import com.jetbrains.rd.ide.model.OriginKind
import com.jetbrains.rd.ide.model.RdFileEditorCompositeId
import com.jetbrains.rd.ide.model.RdFileEditorId
import com.jetbrains.rd.ide.model.SuccessfulRequestComposite
import com.jetbrains.rd.ide.model.HighlighterElement
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.ImageIconModel
import com.jetbrains.rd.ide.model.IndentGuideHighlighterModel
import com.jetbrains.rd.ide.model.MarkupDocumentExtension
import com.jetbrains.rd.ide.model.MarkupTextControlExtension
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdDocumentIdWithFileId
import com.jetbrains.rd.ide.model.RdFileIdNew
import com.jetbrains.rd.ide.model.RdMarkupKey
import com.jetbrains.rd.ide.model.RdMarkupModel
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdDocumentChangeBase
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdRequestHolder
import com.jetbrains.rd.ide.model.RdRequests
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RdRetryTimestamp
import com.jetbrains.rd.ide.model.RdSuccessResponse
import com.jetbrains.rd.ide.model.RequestResponseModel
import com.jetbrains.rd.ide.model.TextControlId
import com.jetbrains.rd.ide.model.RequestCompositeQuery
import com.jetbrains.rd.ide.model.RowIconModel
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.VisibleRange
import com.jetbrains.rd.ide.model.documentsOperationModel
import com.jetbrains.rd.ide.model.fleetRpcModel
import com.jetbrains.rd.ide.model.RdCodeWithMeProject
import com.jetbrains.rd.ide.model.RdOpenSolution
import com.jetbrains.rd.ide.model.fileEditorsModel
import com.jetbrains.rd.ide.model.requestResponseModel
import com.jetbrains.rd.ide.model.solutionModel
import com.jetbrains.rd.ide.model.codeStructureTreeModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.reactive.IScheduler
import com.jetbrains.rd.util.threading.SingleThreadScheduler
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.Connection
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.connection.SplitWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Connects to a backend and keeps the Project tree and the open editors in a [StateFlow], creating only the rd models
 * those need (docs/split-protocol.md, "Our own client"). Plain JVM + rd: no UI types here, so the same class can back an
 * Android screen. All rd model access happens on the rd scheduler thread; UI actions are queued onto it.
 */
class ClientSession(
    private val link: JoinLink,
    private val initialFile: String?,
    /** A stored identity to use [link]'s join token again; null makes a new one. */
    private val identity: ClientCertificate? = null,
    /** Called on the connect thread once the backend has accepted the connection, with the identity it accepted. */
    private val onAccepted: (ClientCertificate) -> Unit = {},
) {
    private val lifetimeDefinition = LifetimeDefinition()
    private val lifetime: Lifetime = lifetimeDefinition
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState

    @Volatile
    private var scheduler: IScheduler? = null

    // Touched only on the rd scheduler thread.
    private val schemes = mutableMapOf<String, ColorsSchemeModel>()
    private val documentPaths = mutableMapOf<Int, String>()
    private val documentTexts = mutableMapOf<RdDocumentId, String>()
    private val documentHighlights = mutableMapOf<RdDocumentId, MutableMap<String, RawHighlight>>()
    private val loadedChildren = mutableSetOf<Int>()
    private val unhandledChangeKinds = mutableSetOf<String>()
    private var projectId: RdProjectId? = null
    private var solution: Solution? = null
    private var vfs: VfsModel? = null
    private var pane: RdProjectViewAsyncPane? = null
    private val compositeIds = mutableMapOf<String, RdFileEditorCompositeId>()
    private val editSessions = mutableMapOf<RdDocumentId, EditSession>()
    /** Backend patches for a document that arrived before its editor existed (normally the editor comes first). */
    private val earlyPatches = mutableMapOf<RdDocumentId, MutableList<RdPatch>>()
    private val commands = CommandChain()
    private var requestIds = 0L
    private var fleet: FleetRpc? = null
    private var completion: Completion? = null
    private var requestResponse: RequestResponseModel? = null
    private var selectedEditorId: RdFileEditorId? = null

    fun start() {
        thread(name = "client-session-connect", isDaemon = true) {
            runCatching { connect() }.onFailure { failure -> fail(failure, connectionLost = true) }
        }
    }

    fun stop() = lifetimeDefinition.terminate()

    // ---- Actions from the UI (any thread) ----

    /** Expands or collapses a tree node; the first expansion asks the backend for the children. */
    fun toggle(nodeId: Int) = onRd {
        val tree = mutableState.value.tree
        if (nodeId in tree.expanded) {
            mutableState.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded - nodeId)) }
        } else {
            expand(nodeId)
        }
    }

    fun select(nodeId: Int) = onRd {
        mutableState.update { it.copy(tree = it.tree.copy(selected = nodeId)) }
        pane?.selection?.set(nodeId)
    }

    /** Opens a file of the backend in an editor tab, or switches to its tab if it is already open. */
    fun open(path: String) = onRd {
        if (mutableState.value.editors.any { it.path == path }) {
            showEditor(path)
        } else {
            openFile(path)
        }
    }

    fun selectEditor(path: String) = onRd { showEditor(path) }

    // Editing the selected file (docs/split-protocol.md, "Typing"). All local first, then confirmed by the backend.

    fun type(character: Char) = onRd {
        val session = selectedEditSession() ?: return@onRd
        // While the completion popup is open, identifier letters narrow it down.
        if (completion?.typed(session, character) != true) {
            session.type(character)
            mutableState.value.selectedEditor?.let { path -> completion?.afterTyped(path, session, character) }
        }
    }

    fun key(key: EditorKey) = onRd {
        val session = selectedEditSession() ?: return@onRd
        when (key) {
            EditorKey.ENTER -> { completion?.cancel(); session.enter() }
            EditorKey.BACKSPACE -> if (completion?.backspace(session) != true) session.backspace()
            EditorKey.DELETE -> { completion?.cancel(); session.delete() }
        }
    }

    /** Moves the caret of the selected file; a selection when [anchor] differs from [offset]. */
    fun moveCaret(offset: Int, anchor: Int = offset) = onRd {
        completion?.cancel()
        selectedEditSession()?.moveCaret(offset, anchor)
    }

    fun undo() = onRd {
        completion?.cancel()
        selectedEditSession()?.undo(isUndo = true)
    }

    fun redo() = onRd {
        completion?.cancel()
        selectedEditSession()?.undo(isUndo = false)
    }

    /** Ctrl+Space: basic completion at the caret of the selected file. */
    fun complete() = onRd {
        val path = mutableState.value.selectedEditor ?: return@onRd
        val session = selectedEditSession() ?: return@onRd
        completion?.complete(path, session) ?: log("Completion is not connected yet")
    }

    fun lookupKey(key: LookupKey) = onRd { completion?.key(key) }

    /** A tap or click on a completion popup row. */
    fun chooseLookupItem(itemId: String) = onRd { completion?.choose(itemId) }

    private fun selectedEditSession(): EditSession? {
        val path = mutableState.value.selectedEditor ?: return null
        return editSessions.entries.firstOrNull { pathOf(it.key) == path }?.value
    }

    // ---- Connection and models ----

    private fun connect() {
        val certificate = identity ?: ClientCertificate.create(link, System.getProperty("user.name") ?: "android")
        val connection = Connection.open(link, certificate, ::log)
        onAccepted(certificate)
        val wire = SplitWire(connection) { failure -> fail(failure ?: IllegalStateException("Connection closed"), connectionLost = true) }
        lifetime.onTermination { wire.close() }
        val rdScheduler = SingleThreadScheduler(lifetime, "rd")
        val protocol = Protocol("ThinClient", Serializers(ProtocolMarshallers), Identities(IdKind.Client), rdScheduler, wire, lifetime)
        wire.start()
        scheduler = rdScheduler
        rdScheduler.queue {
            createModels(protocol)
            log("rd protocol up, models created")
        }
    }

    private fun onRd(action: () -> Unit) {
        val rdScheduler = scheduler ?: return log("Not connected yet")
        rdScheduler.queue { runCatching(action).onFailure(::fail) }
    }

    private fun createModels(protocol: IProtocol) {
        val registry = DefaultRegistryModel()
        protocol.highlighterRegistrationHostModel.registry.set(registry)
        // The backend sends every installed scheme (Default, Darcula, Islands Dark, …), each with only its own entries.
        registry.colorsScheme.advise(lifetime) { scheme ->
            schemes[scheme.name] = scheme
            val resolved = EditorScheme.resolve(EDITOR_SCHEME, schemes) ?: return@advise
            if (mutableState.value.scheme == null) log("Color scheme ${resolved.name} ready")
            mutableState.update { it.copy(scheme = resolved) }
            documentTexts.keys.forEach(::publishDocument)
        }

        val documentsModel = protocol.documentsOperationModel
        val requestResponseModel = documentsModel.requestResponseModel
        requestResponse = requestResponseModel
        requestResponseModel.backendPatches.advise(lifetime) { patchWithMetadata ->
            val patch = patchWithMetadata.patch
            val handled = editSessions.values.filter { it.concerns(patch) }
            handled.forEach { it.backendPatch(patch) }
            for (change in patch.changes) {
                val documentId = (change as? RdDocumentChangeBase)?.documentId ?: continue
                if (editSessions[documentId] == null) {
                    earlyPatches.getOrPut(documentId) { mutableListOf() } += patch
                    if (change is RdResetDocumentChange) { documentTexts[documentId] = change.text; publishDocument(documentId) }
                }
            }
        }
        requestResponseModel.responsesWithTime.advise(lifetime) { responses ->
            for (response in responses.responses) {
                val session = editSessions.values.firstOrNull { it.hasPending(response.requestId) } ?: continue
                when (response) {
                    is RdSuccessResponse -> session.response(response.requestId, response.patch)
                    else -> { log("Request ${response.requestId} not applied: $response"); session.retryOrFailure(response.requestId) }
                }
            }
        }
        documentsModel.documents.view(lifetime) { documentLifetime, documentId, document ->
            // Like JetBrains Client: the client adds the document's markup models, one without a project and one per
            // project. The backend fills the per-project one with code analysis results (errors, warnings, unused).
            document.extensions.filterIsInstance<MarkupDocumentExtension>().firstOrNull()?.markups?.let { markups ->
                markups[RdMarkupKey(null)] = MarkupExtensions.documentMarkup()
                projectId?.let { id ->
                    val projectMarkup = MarkupExtensions.documentMarkup()
                    markups[RdMarkupKey(id)] = projectMarkup
                    attachMarkup(documentLifetime, documentId, projectMarkup, "document")
                }
            }
            document.textControls.view(documentLifetime) { controlLifetime, controlId, control ->
                createEditSession(controlLifetime, documentId, controlId)
                val markupExtension = control.extensions.filterIsInstance<MarkupTextControlExtension>().firstOrNull()
                    ?: return@view log("Editor has no markup extension; no highlighting")
                // The client creates the editor's markup model; the backend then fills it with syntax highlighters.
                val markup = MarkupExtensions.editorMarkup()
                markupExtension.markup.set(markup)
                attachMarkup(controlLifetime, documentId, markup, "editor")
                // The backend runs code analysis (unused, method declarations, fields, errors) only for visible
                // editors, and for their visible range. ponytail: reports the whole document as visible.
                control.visibleChangedFromFrontend.fire(true)
                control.focusChangedFromFrontend.fire(true)
                control.visibleRange.set(VisibleRange(0, documentTexts[documentId]?.length ?: Int.MAX_VALUE, emptyList()))
            }
            documentLifetime.onTermination {
                editSessions.remove(documentId)
                earlyPatches.remove(documentId)
                documentTexts.remove(documentId)
                documentHighlights.remove(documentId)
                val path = pathOf(documentId)
                mutableState.update { state -> state.copy(editors = state.editors.filterNot { it.path == path }) }
            }
        }

        // Fleet RPC rides on this rd model; completion uses it (docs/split-protocol.md, "Code completion").
        val rdScheduler = requireNotNull(scheduler) { "rd scheduler not set" }
        val rpc = FleetRpc(protocol.fleetRpcModel, lifetime, rdScheduler, { if (requestIds == 0L) -1 else requestIds }, ::log)
        fleet = rpc

        vfs = protocol.vfsModel
        protocol.solutionModel.solutions.view(lifetime) { solutionLifetime, _, newSolution ->
            log("Project ${newSolution.projectId.value} added")
            projectId = newSolution.projectId
            solution = newSolution
            newSolution.solutionLifecycle.readyForExtsRegistration.set(true)
            // Fleet RPC names projects by the kernel id, e.g. "m3nniruk9k7j4qsibjui", not by the rd project id.
            val kernelProjectId = ((newSolution.solutionOpenStrategy as? RdOpenSolution)?.description as? RdCodeWithMeProject)?.kernelProjectId
            if (kernelProjectId == null) {
                log("Project has no kernel id; no completion")
            } else {
                completion = Completion(
                    rpc,
                    kernelProjectId,
                    editSessionFor = { editorId -> editSessions.entries.firstOrNull { it.value.kernelEditorId == editorId }?.let { pathOf(it.key) to it.value } },
                    publish = { lookup -> mutableState.update { it.copy(lookup = lookup) } },
                    log = ::log,
                )
                solutionLifetime.onTermination { completion = null }
            }
            newSolution.editorTabsModel
            // The backend lists each open file's editor here; the client marks the one it shows as visible.
            newSolution.fileEditorsModel.backendDeferredComposites.view(solutionLifetime) { compositeLifetime, compositeId, deferred ->
                deferred.model.advise(compositeLifetime) { composite ->
                    val selected = selectedEditorId?.takeIf { it.compositeId == compositeId }
                    composite.visible.set(selected != null)
                    // The selection sent when the file opened can come before the backend made this editor, and is then
                    // refused ("Attempt to select already gone editor", ~0.2 s early on the Mac backend); the backend then
                    // has no selected editor, which the Structure tool window needs. Now the editor exists: send it again.
                    if (selected != null) newSolution.fileEditorsModel.selectionChanged.fire(FileEditorManagerEventModel(OriginKind.Frontend, null, selected))
                }
            }
            createProjectView(solutionLifetime, newSolution)
            bindStructure(solutionLifetime, newSolution)
            newSolution.isLoaded.advise(solutionLifetime) { loaded -> if (loaded && initialFile != null) openFile(initialFile) }
        }
    }

    /** Whether the Structure tool window is showing; the backend builds the tree only while it is. */
    private var structureVisible = false

    fun setStructureVisible(visible: Boolean) = onRd {
        structureVisible = visible
        val current = solution ?: return@onRd
        current.codeStructureTreeModel.toolWindowVisible.set(visible)
    }

    /**
     * The Structure tool window's model (docs/structure-protocol.md): the backend builds the tree for the editor it sees
     * as selected, sends it whole, and sends it again after every change to the file.
     */
    private fun bindStructure(solutionLifetime: Lifetime, solution: Solution) {
        val model = solution.codeStructureTreeModel
        model.treeNodes.advise(solutionLifetime) { list ->
            val nodes = list.treeNodes.filterNot { it.isFromRelatedFile }.map { node ->
                StructureNode(node.id, node.shortName.ifBlank { node.name }, node.icon?.let(::iconPaths).orEmpty(), node.offset.takeIf { node.navigable } ?: -1, node.children)
            }
            val path = list.documentTimestamp?.documentId?.takeIf { nodes.isNotEmpty() }?.let(::pathOf)
            mutableState.update { it.copy(structure = StructureTree(path, nodes)) }
        }
        model.toolWindowVisible.set(structureVisible)
    }

    private fun createProjectView(solutionLifetime: Lifetime, solution: Solution) {
        val projectView = solution.projectViewModel
        projectView.panes.view(solutionLifetime) { paneLifetime, _, candidate ->
            // "ProjectPane" is the Project view; others are Packages, Project Files, Scopes, ….
            if (candidate !is RdProjectViewAsyncPane || candidate.id != PROJECT_PANE) return@view
            pane = candidate
            projectView.currentPane.set(PROJECT_PANE)
            candidate.items.view(paneLifetime) { itemLifetime, id, item ->
                val node = item.toNode(id)
                mutableState.update { it.copy(projectName = it.projectName ?: node.takeIf { node.parentId == null }?.fragments?.firstOrNull()?.text, tree = it.tree.copy(nodes = it.tree.nodes + (id to node))) }
                // IntelliJ opens with the project root expanded.
                if (node.parentId == null && node.hasChildren && node.isDirectory && mutableState.value.tree.expanded.isEmpty()) expand(id)
                itemLifetime.onTermination { mutableState.update { it.copy(tree = it.tree.copy(nodes = it.tree.nodes - id)) } }
            }
        }
    }

    private fun expand(nodeId: Int) {
        val currentPane = pane ?: return
        mutableState.update { it.copy(tree = it.tree.copy(expanded = it.tree.expanded + nodeId)) }
        // The backend adds the children to `items` (with parentId = nodeId) before it answers.
        if (loadedChildren.add(nodeId)) currentPane.expand.start(lifetime, nodeId)
    }

    private fun openFile(path: String) {
        val currentSolution = solution ?: return log("No project yet; cannot open $path")
        val fileSystem = vfs ?: return
        fileSystem.findFile.start(lifetime, RdFindFileParams(null, path, false, false)).result.advise(lifetime) { found ->
            val snapshot = found.unwrap() ?: return@advise log("Backend has no file $path")
            (snapshot.id as? RdFileIdNew)?.let { documentPaths[it.backendId] = path }
            mutableState.update { it.copy(selectedEditor = path) }
            currentSolution.fileEditorsModel.requestComposite.start(lifetime, RequestCompositeQuery(snapshot.id, "file://$path")).result.advise(lifetime) { response ->
                when (val composite = response.unwrap()) {
                    is SuccessfulRequestComposite -> { compositeIds[path] = composite.compositeId; showEditor(path) }
                    is FailedRequestComposite -> log("Backend could not open $path: $composite")
                    else -> log("Unexpected answer opening $path: $composite")
                }
            }
        }
    }

    /**
     * Makes [path]'s editor the selected one, on both sides. The backend runs code analysis only for the selected,
     * visible editor (recorded session: `fileEditorsModel.selectionChanged` and `backendDeferredComposites[…].model.visible`).
     */
    private fun showEditor(path: String) {
        mutableState.update { it.copy(selectedEditor = path) }
        val fileEditors = solution?.fileEditorsModel ?: return
        val compositeId = compositeIds[path] ?: return
        val editorId = EditorIdentity.textEditorId(compositeId)
        if (editorId == selectedEditorId) return
        fileEditors.selectionChanged.fire(FileEditorManagerEventModel(OriginKind.Frontend, selectedEditorId, editorId))
        selectedEditorId = editorId
        for ((id, deferred) in fileEditors.backendDeferredComposites) deferred.model.valueOrNull?.visible?.set(id == compositeId)
    }

    private fun createEditSession(controlLifetime: Lifetime, documentId: RdDocumentId, controlId: TextControlId) {
        val identity = EditorIdentity.of(documentId, controlId, compositeIds[pathOf(documentId)]) ?: return log("Cannot edit $controlId: unknown editor id type")
        val session = EditSession(identity, commands, ::sendRequest) { edit ->
            edit?.let { shiftHighlights(documentId, it) }
            publishDocument(documentId)
        }
        editSessions[documentId] = session
        earlyPatches.remove(documentId)?.forEach(session::backendPatch)
        controlLifetime.onTermination { if (editSessions[documentId] === session) editSessions.remove(documentId) }
    }

    private fun sendRequest(request: RdRequest): Long {
        val requestId = ++requestIds
        val model = requestResponse ?: error("requestResponseModel not ready")
        // RdRequests.timestamp: wall clock in epoch nanoseconds, like JetBrains Client.
        val now = java.time.Instant.now()
        model.requestsWithTime.fire(RdRequests(listOf(RdRequestHolder(requestId, request, RdRetryTimestamp(0, 0), null)), now.epochSecond * 1_000_000_000 + now.nano))
        return requestId
    }

    /** Keeps highlighter ranges in place over local edits until the backend sends fresh ones. */
    private fun shiftHighlights(documentId: RdDocumentId, edit: EditSession.TextEdit) {
        val highlights = documentHighlights[documentId] ?: return
        for ((key, highlight) in highlights.entries.toList()) {
            val (start, end) = edit.shift(highlight.start, highlight.end)
            highlights[key] = highlight.copy(start = start, end = end)
        }
    }

    private fun attachMarkup(markupLifetime: Lifetime, documentId: RdDocumentId, markup: RdMarkupModel, source: String) {
        val highlights = documentHighlights.getOrPut(documentId) { mutableMapOf() }
        markup.highlightersAdded.advise(markupLifetime) { list -> list.elements.forEach { highlights["$source:${it.highlighter.id}"] = it.toRaw() }; publishDocument(documentId) }
        markup.highlightersUpdated.advise(markupLifetime) { list -> list.elements.forEach { highlights["$source:${it.highlighter.id}"] = it.toRaw() }; publishDocument(documentId) }
        markup.highlightersRemoved.advise(markupLifetime) { removed -> removed.elements.forEach { highlights.remove("$source:$it") }; publishDocument(documentId) }
        markup.highlightersCleared.advise(markupLifetime) { highlights.keys.removeAll { it.startsWith("$source:") }; publishDocument(documentId) }
        markupLifetime.onTermination { highlights.keys.removeAll { it.startsWith("$source:") } }
    }

    private fun publishDocument(documentId: RdDocumentId) {
        val session = editSessions[documentId]?.takeIf { it.isReady }
        val text = session?.text ?: documentTexts[documentId] ?: return
        documentTexts[documentId] = text
        val scheme = mutableState.value.scheme
        val raw = documentHighlights[documentId]?.values.orEmpty().filter { it.start in 0..it.end && it.end <= text.length }
        val highlights = if (scheme == null) emptyList() else raw.filter { it.indentLevel == null }
            .map { Highlight(it.id, it.start, it.end, it.layer, scheme.style(it.keyChains)) }
            .sortedWith(compareBy({ it.layer }, { it.start }))
        val guides = raw.mapNotNull { highlight -> highlight.indentLevel?.let { IndentGuide(highlight.start, highlight.end, it) } }
        val gutterIcons = raw.filter { it.gutterIconPaths.isNotEmpty() }.sortedBy { it.start }.map { GutterIcon(it.start, it.gutterIconPaths) }
        val file = EditorFile(
            pathOf(documentId), text, highlights, guides, gutterIcons,
            caret = session?.caret ?: 0, selectionStart = session?.selectionStart ?: 0, selectionEnd = session?.selectionEnd ?: 0,
            editable = session != null,
        )
        // Debug aid: CLIENT_HIGHLIGHT_DUMP=<file> writes every highlight with its keys and covered text.
        System.getenv("CLIENT_HIGHLIGHT_DUMP")?.let { dump ->
            java.io.File(dump).writeText(documentHighlights[documentId].orEmpty().entries.sortedBy { it.value.start }.joinToString("\n") { (key, raw) ->
                "$key ${raw.start}-${raw.end} layer=${raw.layer} guide=${raw.indentLevel} keys=${raw.keyChains.map { it.first() }} '${text.substring(raw.start, raw.end).take(30)}'"
            })
        }
        mutableState.update { state ->
            val editors = if (state.editors.any { it.path == file.path }) state.editors.map { if (it.path == file.path) file else it } else state.editors + file
            state.copy(editors = editors, selectedEditor = state.selectedEditor ?: file.path)
        }
    }

    private fun pathOf(documentId: RdDocumentId): String {
        val fileId = (documentId as? RdDocumentIdWithFileId)?.fileId as? RdFileIdNew
        return fileId?.let { documentPaths[it.backendId] } ?: "document-${fileId?.backendId ?: documentId.hashCode()}"
    }

    private fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)} $message"
        println(line)
        mutableState.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    private fun fail(failure: Throwable, connectionLost: Boolean = false) {
        log("FAILED: $failure")
        failure.printStackTrace()
        mutableState.update { it.copy(failure = failure.toString(), connectionLost = if (connectionLost) failure.toString() else it.connectionLost) }
    }

    /** A highlighter before style resolution, so a scheme change can restyle it. Indent guides carry [indentLevel]. */
    private data class RawHighlight(
        val id: Long,
        val start: Int,
        val end: Int,
        val layer: Int,
        val keyChains: List<List<String>>,
        val indentLevel: Int?,
        val gutterIconPaths: List<String>,
    )

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        /** The user's IntelliJ uses Islands Dark (its theme and its editor scheme). */
        const val EDITOR_SCHEME = "Islands Dark"
        const val PROJECT_PANE = "ProjectPane"

        fun HighlighterElement.toRaw(): RawHighlight {
            val model = highlighter
            val keys = (model as? SyntaxHighlighterModel)?.textAttributesKeys ?: listOfNotNull(model.textAttributesKey)
            val gutterIcon = (model as? GutterMarkHighlighterModel)?.gutterMarkRenderer?.icon?.let(::iconPaths).orEmpty()
            return RawHighlight(model.id, model.start, model.end, model.layer, keys.map(EditorScheme::keyChain), (model as? IndentGuideHighlighterModel)?.indentLevel, gutterIcon)
        }

        fun RdProjectViewItem.toNode(id: Int) = TreeNode(
            id = id,
            parentId = parentId,
            fragments = descriptor.presentation.map { fragment ->
                TextFragment(fragment.text, fragment.attributes.style, fragment.attributes.fgColor?.let { it.toLong() and 0xFFFFFFFFL })
            },
            iconPaths = descriptor.icon?.let(::iconPaths).orEmpty(),
            hasChildren = descriptor.hasChildren,
            isDirectory = descriptor.isDirectory,
            path = descriptor.path,
            sortKey = descriptor.sortKey,
        )

        private val unhandledIconTypes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun iconPaths(icon: IconModel): List<String> = when (icon) {
            is ImageIconModel -> listOf(icon.id.path)
            is RowIconModel -> icon.subNodes.flatMap(::iconPaths)
            // Layered icon (e.g. the module file): the first layer is the base icon.
            is CompositionIconModel -> icon.subNodes.flatMap(::iconPaths)
            // Shown until the real icon is computed; the backend sends the final one as an item update.
            is DeferredIconModel -> icon.baseIcon?.let(::iconPaths).orEmpty()
            // A blank slot, e.g. the visibility place in a Structure row's icon.
            is EmptyIconModel -> emptyList()
            else -> {
                if (unhandledIconTypes.add(icon::class.simpleName.orEmpty())) println("Icon type not handled yet: $icon")
                emptyList()
            }
        }
    }
}

/** The client's marshallers: the IDE root models, then the split-mode models (both resolve ids from resource lists). */
private object ProtocolMarshallers : MarshallersProvider {
    override fun getMarshaller(id: RdId): IMarshaller<*>? =
        BasicIdeRootMarshallersProvider.getMarshaller(id) ?: DefaultSplitMarshallersProvider.getMarshaller(id)
}
