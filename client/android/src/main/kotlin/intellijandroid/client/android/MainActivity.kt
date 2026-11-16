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

    private lateinit var history: ConnectionHistory
    private lateinit var termux: Termux
    private val termuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private class Attempt(val kind: ConnectionKind, val link: JoinLink, val text: String, val project: String?, val openFile: String?)

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
        )

        setContent {
            CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons, LocalEditorTextInput provides editorTextInput) {
                Box(Modifier.fillMaxSize().background(IslandsDark.windowBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val current = session
                    if (current == null) {
                        LaunchedEffect(Unit) { refresh() }
                        ConnectScreen(ConnectScreenState(link, file, localBackends, saved, termuxAccess, busy, message, startProject), actions, fonts)
                    } else {
                        val state by current.state.collectAsState()
                        LaunchedEffect(state.connectionLost) {
                            val lost = state.connectionLost ?: return@LaunchedEffect
                            current.stop()
                            session = null
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

    private fun connect(kind: ConnectionKind, text: String, project: String?, openFile: String?, identity: String?) {
        val joinLink = runCatching { JoinLink.parse(text) }.getOrElse { failure -> message = failure.message; return }
        val certificate = identity?.let { encoded ->
            runCatching { ClientCertificate.decode(encoded) }.getOrElse { failure ->
                message = "The saved client identity can't be read (${failure.message}). Remove the entry and paste the link again."
                return
            }
        }
        val current = Attempt(kind, joinLink, text.trim(), project, openFile)
        attempt = current
        accepted = false
        message = null
        startProject = null
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
            ConnectionKind.TERMUX -> termuxStatus(entry.project)
        }
    }

    private fun openDiscovered(backend: DiscoveredBackend) {
        if (busy != null) return
        val known = saved.firstOrNull { entry -> entry.kind == ConnectionKind.LINK && entry.host == backend.host && entry.port == backend.port }
        when {
            backend.joinLink != null -> connectTermux(backend.joinLink, backend.project)
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
                        status.optBoolean("running") -> connectTermux(status.getString("joinLink"), status.optString("project"))
                        else -> startProject = project ?: "${Termux.HOME}/"
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

    /** The backend prints `0.0.0.0` when it listens on every address; from this phone it is `127.0.0.1`. */
    private fun connectTermux(printedLink: String, project: String?) {
        val text = printedLink.trim().replace("tcp://0.0.0.0:", "tcp://127.0.0.1:")
        val entry = saved.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == project }
        // A new join token (the backend restarted) needs a new identity.
        val identity = entry?.identity?.takeIf { entry.lastLink == text }
        connect(ConnectionKind.TERMUX, text, project, entry?.openFile ?: file.ifBlank { null }, identity)
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
