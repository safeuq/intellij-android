package intellijandroid.client.session

import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider
import com.jetbrains.codeWithMe.model.RdFindFileParams
import com.jetbrains.codeWithMe.model.SyntaxHighlighterModel
import com.jetbrains.codeWithMe.model.editorTabsModel
import com.jetbrains.codeWithMe.model.vfsModel
import com.jetbrains.ide.model.highlighterRegistration.DefaultRegistryModel
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributesKey
import com.jetbrains.ide.model.highlighterRegistration.TextAttributesKeyModel
import com.jetbrains.ide.model.highlighterRegistration.highlighterRegistrationHostModel
import com.jetbrains.rd.framework.IMarshaller
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.IdKind
import com.jetbrains.rd.framework.Identities
import com.jetbrains.rd.framework.MarshallersProvider
import com.jetbrains.rd.framework.Protocol
import com.jetbrains.rd.framework.RdId
import com.jetbrains.rd.framework.Serializers
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.BasicIdeRootMarshallersProvider
import com.jetbrains.rd.ide.model.FailedRequestComposite
import com.jetbrains.rd.ide.model.HighlighterElement
import com.jetbrains.rd.ide.model.MarkupDocumentExtension
import com.jetbrains.rd.ide.model.MarkupTextControlExtension
import com.jetbrains.rd.ide.model.RdDocumentId
import com.jetbrains.rd.ide.model.RdMarkupKey
import com.jetbrains.rd.ide.model.RdProjectId
import com.jetbrains.rd.ide.model.RdResetDocumentChange
import com.jetbrains.rd.ide.model.RequestCompositeQuery
import com.jetbrains.rd.ide.model.RgbColor
import com.jetbrains.rd.ide.model.RgbaColor
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.ide.model.documentsOperationModel
import com.jetbrains.rd.ide.model.fileEditorsModel
import com.jetbrains.rd.ide.model.requestResponseModel
import com.jetbrains.rd.ide.model.solutionModel
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
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

/** One highlighted range of the open file; [argb] is null when the color scheme has no foreground for it. */
data class Highlight(val id: Long, val start: Int, val end: Int, val argb: Long?)

data class OpenFile(val path: String, val text: String, val highlights: List<Highlight>)

data class SessionState(
    val log: List<String> = emptyList(),
    val file: OpenFile? = null,
    val failure: String? = null,
)

/**
 * Connects to a backend and opens one file, creating only the rd models the editor needs
 * (docs/split-protocol.md, "What a first client has to do, from this recording"). Plain JVM + rd: no UI types here,
 * so the same class can back an Android screen.
 */
class ClientSession(private val link: JoinLink, private val filePath: String) {
    private val lifetimeDefinition = LifetimeDefinition()
    private val lifetime: Lifetime = lifetimeDefinition
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState

    // Touched only on the rd scheduler thread.
    private val texts = mutableMapOf<RdDocumentId, String>()
    private val highlights = mutableMapOf<Long, Highlight>()
    private val foregroundByKey = mutableMapOf<String, Long>()
    private var openDocumentId: RdDocumentId? = null
    private var projectId: RdProjectId? = null

    fun start() {
        thread(name = "client-session-connect", isDaemon = true) {
            runCatching { connect() }.onFailure { failure -> fail(failure) }
        }
    }

    fun stop() = lifetimeDefinition.terminate()

    private fun connect() {
        val certificate = ClientCertificate.create(link, System.getProperty("user.name") ?: "android")
        val connection = Connection.open(link, certificate, ::log)
        val wire = SplitWire(connection) { failure -> fail(failure ?: IllegalStateException("Connection closed")) }
        lifetime.onTermination { wire.close() }
        val scheduler = SingleThreadScheduler(lifetime, "rd")
        val protocol = Protocol("ThinClient", Serializers(ProtocolMarshallers), Identities(IdKind.Client), scheduler, wire, lifetime)
        wire.start()
        scheduler.queue {
            createModels(protocol)
            log("rd protocol up, models created")
        }
    }

    private fun createModels(protocol: IProtocol) {
        val highlighterHost = protocol.highlighterRegistrationHostModel
        val registry = DefaultRegistryModel()
        highlighterHost.registry.set(registry)
        // The backend sends every installed scheme (Default, Darcula, Light, …); the client picks one.
        registry.colorsScheme.advise(lifetime) { scheme ->
            if (scheme.name != EDITOR_SCHEME) return@advise
            foregroundByKey.clear()
            scheme.attributes.forEach { entry -> entry.attributes?.foregroundColor?.toArgb()?.let { foregroundByKey[entry.textAttributesKey.externalName] = it } }
            log("Color scheme ${scheme.name}: ${foregroundByKey.size} foreground colors")
            publishFile()
        }

        val documentsModel = protocol.documentsOperationModel
        documentsModel.requestResponseModel.backendPatches.advise(lifetime) { patchWithMetadata ->
            for (change in patchWithMetadata.patch.changes) {
                if (change is RdResetDocumentChange) {
                    texts[change.documentId] = change.text
                    log("Document text: ${change.text.length} characters")
                } else {
                    log("Not handled yet: ${change::class.simpleName}")
                }
            }
            publishFile()
        }
        documentsModel.documents.view(lifetime) { documentLifetime, documentId, document ->
            log("Document opened: $documentId")
            // Like JetBrains Client: the client adds the document's markup models, one without a project and one per
            // project; the backend fills the per-project one with daemon results (errors, warnings).
            document.extensions.filterIsInstance<MarkupDocumentExtension>().firstOrNull()?.markups?.let { markups ->
                markups[RdMarkupKey(null)] = MarkupExtensions.documentMarkup()
                projectId?.let { markups[RdMarkupKey(it)] = MarkupExtensions.documentMarkup() }
            }
            document.textControls.view(documentLifetime) { controlLifetime, _, control ->
                openDocumentId = documentId
                log("Editor opened for $documentId")
                val markupExtension = control.extensions.filterIsInstance<MarkupTextControlExtension>().firstOrNull()
                    ?: return@view log("Editor has no markup extension; no highlighting")
                // The client creates the editor's markup model; the backend then fills it with highlighters.
                val markup = MarkupExtensions.editorMarkup()
                markupExtension.markup.set(markup)
                markup.highlightersAdded.advise(controlLifetime) { list -> list.elements.forEach(::addHighlight); publishFile() }
                markup.highlightersUpdated.advise(controlLifetime) { list -> list.elements.forEach(::addHighlight); publishFile() }
                markup.highlightersRemoved.advise(controlLifetime) { removed -> removed.elements.forEach { id -> highlights.remove(id) }; publishFile() }
                markup.highlightersCleared.advise(controlLifetime) { highlights.clear(); publishFile() }
            }
        }

        val vfs = protocol.vfsModel
        protocol.solutionModel.solutions.view(lifetime) { solutionLifetime, _, solution ->
            log("Project ${solution.projectId.value} added")
            projectId = solution.projectId
            solution.solutionLifecycle.readyForExtsRegistration.set(true)
            solution.editorTabsModel
            solution.isLoaded.advise(solutionLifetime) { loaded -> if (loaded) openFile(solutionLifetime, solution, vfs) }
        }
    }

    private fun openFile(solutionLifetime: Lifetime, solution: Solution, vfs: com.jetbrains.codeWithMe.model.VfsModel) {
        log("Project loaded; looking up $filePath")
        vfs.findFile.start(solutionLifetime, RdFindFileParams(null, filePath, false, false)).result.advise(solutionLifetime) { found ->
            val snapshot = found.unwrap() ?: return@advise fail(IllegalStateException("Backend has no file $filePath"))
            log("File id ${snapshot.id}")
            val query = RequestCompositeQuery(snapshot.id, "file://$filePath")
            solution.fileEditorsModel.requestComposite.start(solutionLifetime, query).result.advise(solutionLifetime) { response ->
                val composite = response.unwrap()
                if (composite is FailedRequestComposite) fail(IllegalStateException("Backend could not open $filePath: $composite"))
                else log("Editor requested: $composite")
            }
        }
    }

    private fun addHighlight(element: HighlighterElement) {
        val highlighter = element.highlighter
        val keys = (highlighter as? SyntaxHighlighterModel)?.textAttributesKeys ?: listOfNotNull(highlighter.textAttributesKey)
        val argb = keys.firstNotNullOfOrNull(::foregroundOf)
        highlights[highlighter.id] = Highlight(highlighter.id, highlighter.start, highlighter.end, argb)
    }

    /** Looks the key up in the scheme, then its fallbacks: `JAVA_KEYWORD` → `DEFAULT_KEYWORD`. */
    private fun foregroundOf(key: TextAttributesKeyModel): Long? {
        var current: TextAttributesKeyModel? = key
        while (current != null) {
            foregroundByKey[current.externalName]?.let { return it }
            current = (current as? IdeaTextAttributesKey)?.fallback
        }
        return null
    }

    private fun publishFile() {
        val documentId = openDocumentId ?: return
        val text = texts[documentId] ?: return
        val visible = highlights.values.filter { it.start in 0..it.end && it.end <= text.length }.sortedBy { it.start }
        val previous = mutableState.value.file
        if (previous == null || previous.highlights.size != visible.size) {
            log("Showing ${text.length} characters, ${visible.size} highlights (${visible.count { it.argb != null }} with a color)")
        }
        mutableState.update { it.copy(file = OpenFile(filePath, text, visible)) }
    }

    private fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)} $message"
        println(line)
        mutableState.update { it.copy(log = it.log + line) }
    }

    private fun fail(failure: Throwable) {
        log("FAILED: $failure")
        failure.printStackTrace()
        mutableState.update { it.copy(failure = failure.toString()) }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        const val EDITOR_SCHEME = "Darcula"

        fun AbstractColor.toArgb(): Long? = when (this) {
            is RgbaColor -> (alpha.toLong() shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            is RgbColor -> (0xFFL shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
            else -> null
        }
    }
}

/** The client's marshallers: the IDE root models, then the split-mode models (both resolve ids from resource lists). */
private object ProtocolMarshallers : MarshallersProvider {
    override fun getMarshaller(id: RdId): IMarshaller<*>? =
        BasicIdeRootMarshallersProvider.getMarshaller(id) ?: DefaultSplitMarshallersProvider.getMarshaller(id)
}
