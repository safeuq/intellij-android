package intellijandroid.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.ui.ClientScreen
import kotlin.system.exitProcess

/** Desktop entry point. Usage: `<join link> <absolute file path on the backend>`. */
fun main(arguments: Array<String>) {
    if (arguments.size != 2) {
        System.err.println("Usage: <join link, e.g. tcp://127.0.0.1:5990#jt=…&fp=…> <file path on the backend, e.g. /data/data/com.termux/files/home/intellij/smoke/src/demo/Main.java>")
        exitProcess(2)
    }
    // The protocol jars from JetBrains Client 263.7307.200 are class version 69.
    check(Runtime.version().feature() >= 25) { "Needs Java 25 or newer (the protocol jars are Java 25 class files); running ${Runtime.version()}" }

    val session = ClientSession(JoinLink.parse(arguments[0]), arguments[1])
    session.start()
    application {
        Window(onCloseRequest = { session.stop(); exitApplication() }, title = "IntelliJ split client") {
            val state by session.state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) { ClientScreen(state) }
        }
    }
}
