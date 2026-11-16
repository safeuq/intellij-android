package intellijandroid.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import intellijandroid.client.connection.ClientCertificate
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.LocalClientFonts
import intellijandroid.client.ui.LocalEditorTextInput
import intellijandroid.client.ui.LocalIcons
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The connect screen (link prompt, discovered backends, history; docs/connections.md), then the same screen as the
 * desktop client. For scripted runs: `adb shell am start -n intellijandroid.client.android/.MainActivity --es joinLink
 * '<link>' [--es openFile '<path>']` connects at once.
 */
class MainActivity : ComponentActivity() {
    private var session by mutableStateOf<ClientSession?>(null)
    /** The connection being made or in use; saved to history once the backend accepts it. */
    private var attempt: Attempt? = null
    private var accepted by mutableStateOf(false)

    private var link by mutableStateOf("")
    private var file by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf<String?>(null)
    private var startProject by mutableStateOf<String?>(null)
    private var saved by mutableStateOf(emptyList<SavedConnection>())
    private var localBackends by mutableStateOf(emptyList<DiscoveredBackend>())
    private var termuxAccess by mutableStateOf(TermuxAccess.NOT_INSTALLED)
    /** Termux's backend has another client connected (e.g. Gateway); connecting with this link ends that client's session. */
    private var takeOver by mutableStateOf<TakeOver?>(null)

    private lateinit var history: ConnectionHistory
    private lateinit var termux: Termux
    private val termuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    /** [resumed]: the saved link and identity of a Termux entry; if refused, the app asks Termux for a fresh link. */
    private class Attempt(val kind: ConnectionKind, val link: JoinLink, val text: String, val project: String?, val openFile: String?, val resumed: Boolean)

    private class TakeOver(val joinLink: String, val project: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fonts = AndroidResources.fonts(this)
        val icons = AndroidResources.SvgIcons(AndroidResources.iconJar(this), resources.displayMetrics.density)
        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        history = ConnectionHistory(this)
        termux = Termux(this)
        saved = history.entries()
        link = preferences.getString("joinLink", "").orEmpty()
        file = preferences.getString("openFile", "").orEmpty()
        intent.getStringExtra("joinLink")?.let { extra -> connectLink(extra, intent.getStringExtra("openFile")) }

        val actions = ConnectActions(
            linkChanged = { link = it },
            fileChanged = { file = it },
            connectLink = {
                preferences.edit { putString("joinLink", link); putString("openFile", file) }
                connectLink(link, file.ifBlank { null })
            },
            openDiscovered = ::openDiscovered,
            openSaved = ::openSaved,
            removeSaved = { entry -> history.remove(entry); saved = history.entries() },
            allowTermux = { termuxPermission.launch(Termux.PERMISSION) },
            startChanged = { startProject = it },
            startInTermux = ::startInTermux,
            listFolders = { path, onResult -> termux.listFolders(path, onResult) },
            refresh = ::refresh,
            takeOver = { takeOver?.let { pending -> connectTermux(pending.joinLink, pending.project) } },
            cancelTakeOver = { takeOver = null },
        )

        setContent {
            CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons, LocalEditorTextInput provides editorTextInput) {
                Box(Modifier.fillMaxSize().background(IslandsDark.windowBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val current = session
                    if (current == null) {
                        LaunchedEffect(Unit) { refresh() }
                        ConnectScreen(ConnectScreenState(link, file, localBackends, saved, termuxAccess, busy, message, startProject, takeOverQuestion()), actions, fonts)
                    } else {
                        val state by current.state.collectAsState()
                        LaunchedEffect(state.connectionLost) {
                            val lost = state.connectionLost ?: return@LaunchedEffect
                            val failed = attempt
                            current.stop()
                            session = null
                            // A join token works once: after another client (e.g. Gateway) took over, the saved one is declined.
                            if (!accepted && failed?.resumed == true) return@LaunchedEffect termuxStatus(failed.project)
                            message = if (accepted) "Connection lost: $lost. Tap it under History to reconnect." else "Couldn't connect: $lost"
                        }
                        LaunchedEffect(state.projectName, accepted) {
                            val projectName = state.projectName ?: return@LaunchedEffect
                            val entry = saved.firstOrNull() ?: return@LaunchedEffect
                            if (accepted && entry.kind == ConnectionKind.LINK && !entry.name.startsWith(projectName)) {
                                history.save(entry.copy(name = "$projectName · ${entry.host}:${entry.port}"))
                                saved = history.entries()
                            }
                        }
                        ClientScreen(state, current)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }

    // ---- Connecting ----

    /** A pasted link: reuses the identity of a saved connection with the same link, else makes a new one. */
    private fun connectLink(text: String, openFile: String?) {
        val identity = saved.firstOrNull { it.lastLink == text.trim() }?.identity
        connect(ConnectionKind.LINK, text, project = null, openFile, identity)
    }

    private fun connect(kind: ConnectionKind, text: String, project: String?, openFile: String?, identity: String?, resumed: Boolean = false) {
        val joinLink = runCatching { JoinLink.parse(text) }.getOrElse { failure -> message = failure.message; return }
        val certificate = identity?.let { encoded ->
            runCatching { ClientCertificate.decode(encoded) }.getOrElse { failure ->
                message = "The saved client identity can't be read (${failure.message}). Remove the entry and paste the link again."
                return
            }
        }
        val current = Attempt(kind, joinLink, text.trim(), project, openFile, resumed)
        attempt = current
        accepted = false
        message = null
        startProject = null
        takeOver = null
        session = ClientSession(joinLink, openFile, certificate) { acceptedCertificate ->
            val encoded = acceptedCertificate.encode()
            runOnUiThread { onAccepted(current, encoded) }
        }.also { it.start() }
    }

    private fun onAccepted(current: Attempt, identity: String) {
        if (attempt !== current) return
        accepted = true
        val previous = saved.firstOrNull { it.key == keyOf(current) }
        val name = when (current.kind) {
            ConnectionKind.TERMUX -> "This phone · ${current.project?.substringAfterLast('/')}"
            ConnectionKind.LINK -> previous?.name ?: "${current.link.host}:${current.link.port}"
        }
        history.save(
            SavedConnection(
                kind = current.kind,
                name = name,
                host = current.link.host,
                port = current.link.port,
                project = current.project,
                fingerprint = current.link.fingerprint,
                lastLink = current.text,
                identity = identity,
                openFile = current.openFile,
                lastConnected = System.currentTimeMillis(),
            ),
        )
        saved = history.entries()
    }

    private fun keyOf(current: Attempt) =
        if (current.kind == ConnectionKind.TERMUX) "termux:${current.project}" else "link:${current.link.host}:${current.link.port}"

    private fun openSaved(entry: SavedConnection) {
        if (busy != null) return
        when (entry.kind) {
            ConnectionKind.LINK -> {
                // Shown in the prompt, so that a refused link (the backend restarted) is easy to replace with the new one.
                link = entry.lastLink
                connect(ConnectionKind.LINK, entry.lastLink, project = null, entry.openFile, entry.identity)
            }
            ConnectionKind.TERMUX -> openTermux(entry.project)
        }
    }

    /** Resumes with the saved link and identity; when that is refused, the connection-lost handler asks Termux (docs/connections.md). */
    private fun openTermux(project: String?) {
        val entry = saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }
        if (entry == null) termuxStatus(project)
        else connect(ConnectionKind.TERMUX, entry.lastLink, project, entry.openFile, entry.identity, resumed = true)
    }

    private fun takeOverQuestion() = takeOver?.let { pending ->
        "Another client is connected to the backend for ${pending.project?.substringAfterLast('/')} (for example Gateway on a laptop). " +
            "Connecting from this phone ends its session."
    }

    private fun openDiscovered(backend: DiscoveredBackend) {
        if (busy != null) return
        val known = saved.firstOrNull { entry -> entry.kind == ConnectionKind.LINK && entry.host == backend.host && entry.port == backend.port }
        when {
            backend.joinLink != null -> openTermux(backend.project)
            known != null -> openSaved(known)
            else -> {
                link = "tcp://${backend.host}:${backend.port}#"
                message = "Paste the join link of the backend on port ${backend.port}. In Termux, `intellij-backend link` prints it."
            }
        }
    }

    // ---- Termux ----

    /**
     * `intellij-backend status --json`: connects when a backend runs, otherwise offers to start one for [project]
     * (docs/connections.md, "Runtime contract").
     */
    private fun termuxStatus(project: String?) {
        if (!termux.hasPermission) {
            termuxPermission.launch(Termux.PERMISSION)
            return
        }
        busy = "Asking Termux for the backend's link…"
        termux.run(listOf("status", "--json")) { result ->
            busy = null
            result.fold(
                onSuccess = { output ->
                    val status = runCatching { JSONObject(output.stdout) }.getOrNull()
                    when {
                        output.exitCode != 0 || status == null -> message = "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}"
                        !status.optBoolean("running") -> startProject = project ?: "${Termux.HOME}/"
                        status.optBoolean("controllerConnected") -> takeOver = TakeOver(status.getString("joinLink"), status.optString("project"))
                        else -> connectTermux(status.getString("joinLink"), status.optString("project"))
                    }
                },
                onFailure = { failure -> message = failure.message },
            )
        }
    }

    private fun startInTermux() {
        val project = startProject?.trim().orEmpty()
        if (project.isEmpty()) {
            message = "Enter the project folder to start the backend for."
            return
        }
        if (busy != null) return
        busy = "Starting the backend in Termux; this takes up to 3 minutes…"
        termux.run(listOf("start", project)) { result ->
            busy = null
            result.fold(
                onSuccess = { output ->
                    when (output.exitCode) {
                        0 -> connectTermux(output.stdout.trim(), project)
                        Termux.EXIT_SETTINGS_IN_USE -> message = "Another IntelliJ in Termux is using the same settings. Stop it first (intellij-backend stop)."
                        else -> message = "intellij-backend start failed (exit ${output.exitCode}): ${output.stderr}"
                    }
                },
                onFailure = { failure -> message = failure.message },
            )
        }
    }

    /**
     * A fresh join link from Termux, with a new client identity (resuming with the saved one is [openTermux]'s job). The
     * backend prints `0.0.0.0` when it listens on every address; from this phone it is `127.0.0.1`.
     */
    private fun connectTermux(printedLink: String, project: String?) {
        val text = printedLink.trim().replace("tcp://0.0.0.0:", "tcp://127.0.0.1:")
        val entry = saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }
        connect(ConnectionKind.TERMUX, text, project, entry?.openFile ?: file.ifBlank { null }, identity = null)
    }

    // ---- Discovery ----

    /** Asks Termux for its backend and probes this phone's ports (docs/connections.md, "Discovery"). */
    private fun refresh() {
        termuxAccess = when {
            !termux.isInstalled -> TermuxAccess.NOT_INSTALLED
            !termux.hasPermission -> TermuxAccess.NO_PERMISSION
            else -> TermuxAccess.READY
        }
        thread(name = "local-ports", isDaemon = true) {
            val ports = LocalPorts.open()
            runOnUiThread {
                val known = localBackends.filter { it.joinLink != null && it.port in ports }
                localBackends = known + ports.filter { port -> known.none { it.port == port } }
                    .map { port -> DiscoveredBackend("This phone · port $port", port, project = null, joinLink = null) }
            }
        }
        if (termuxAccess != TermuxAccess.READY) return
        termux.run(listOf("status", "--json")) { result ->
            result.fold(
                onSuccess = { output ->
                    val status = runCatching { JSONObject(output.stdout) }.getOrNull()
                    when {
                        output.exitCode != 0 || status == null ->
                            message = "intellij-backend status failed (exit ${output.exitCode}): ${output.stderr.ifBlank { output.stdout }}"
                        status.optBoolean("running") -> {
                            val project = status.optString("project")
                            val port = status.getInt("port")
                            val backend = DiscoveredBackend("This phone · ${project.substringAfterLast('/')}", port, project, status.getString("joinLink"))
                            localBackends = listOf(backend) + localBackends.filter { it.port != port }
                            startProject = null
                        }
                        // Nothing running: offer to start one, for the last Termux project or a folder under Termux's home.
                        startProject == null -> startProject = saved.firstOrNull { it.kind == ConnectionKind.TERMUX }?.project ?: "${Termux.HOME}/"
                    }
                },
                onFailure = { failure -> message = failure.message },
            )
        }
    }
}
