package intellijandroid.client

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.desktop.DesktopResources
import intellijandroid.client.desktop.Snapshots
import intellijandroid.client.session.ClientSession
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.LocalClientFonts
import intellijandroid.client.ui.LocalIcons
import java.io.File
import kotlin.system.exitProcess

/** Desktop entry point. Usage: `<join link> [absolute file path on the backend to open at start]`. */
fun main(arguments: Array<String>) {
    if (arguments.isEmpty() || arguments.size > 2) {
        System.err.println("Usage: <join link, e.g. tcp://127.0.0.1:5990#jt=…&fp=…> [file path on the backend to open]")
        exitProcess(2)
    }
    // The protocol jars from JetBrains Client 263.7307.200 are class version 69.
    check(Runtime.version().feature() >= 25) { "Needs Java 25 or newer (the protocol jars are Java 25 class files); running ${Runtime.version()}" }
    val clientLib = File(requireNotNull(System.getProperty("jetbrains.client.lib")) { "System property jetbrains.client.lib is not set (the Gradle run task sets it)" })
    val fonts = DesktopResources.fonts(clientLib)

    val session = ClientSession(JoinLink.parse(arguments[0]), arguments.getOrNull(1))
    session.start()
    System.getenv("CLIENT_SNAPSHOT")?.let { Snapshots.start(File(it), session, clientLib, fonts) }
    application {
        Window(onCloseRequest = { session.stop(); exitApplication() }, title = "IntelliJ split client", state = rememberWindowState(width = 1200.dp, height = 800.dp)) {
            val state by session.state.collectAsState()
            val density = LocalDensity.current
            val icons = remember(density) { DesktopResources.JarIcons(clientLib, density) }
            CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons) {
                ClientScreen(state, session)
            }
        }
    }
}
