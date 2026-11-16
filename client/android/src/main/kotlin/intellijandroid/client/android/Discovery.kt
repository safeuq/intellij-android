package intellijandroid.client.android

import java.net.InetSocketAddress
import java.net.Socket

/**
 * A backend found on this phone (docs/connections.md, "Discovery"): from Termux's `intellij-backend status --json`, or
 * a port on 127.0.0.1 that accepts connections. LAN machines are added once by link and then reached from history.
 */
data class DiscoveredBackend(
    /** For example `This phone · smoke` or `This phone · port 5990`. */
    val name: String,
    val port: Int,
    /** Project path from `status --json`, e.g. `/data/data/com.termux/files/home/intellij/smoke`. */
    val project: String?,
    /** Known only from `status --json`; a bare open port needs its link pasted. */
    val joinLink: String?,
) {
    val host: String get() = "127.0.0.1"
}

object LocalPorts {
    /** The ports `intellij-backend` and `remote-dev-server.sh` use, from 5990 up. */
    private val PORTS = 5990..5999

    /** Ports on 127.0.0.1 that accept a TCP connection. Blocking; call off the main thread. */
    fun open(): List<Int> = PORTS.filter { port ->
        runCatching { Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
    }
}
