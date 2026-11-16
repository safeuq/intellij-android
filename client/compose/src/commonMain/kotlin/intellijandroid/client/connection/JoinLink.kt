package intellijandroid.client.connection

import java.net.URI
import java.net.URLDecoder

/**
 * The link the backend prints, for example
 * `tcp://127.0.0.1:5990#jt=1988c27d-…&p=IU&fp=A75B41FE…27E9&cb=263.7307.200&newUi=true&jb=25.0.4.1b635.79`.
 * Only the address, `jt` and `fp` matter on the wire (docs/split-protocol.md, "The join link").
 */
data class JoinLink(
    val host: String,
    val port: Int,
    /** `jt`: one-time join token, sent inside the client certificate. */
    val joinToken: String,
    /** `fp`: upper-case hex SHA3-256 of the backend's certificate. */
    val fingerprint: String,
    /** The link without its `#…` part, e.g. `tcp://127.0.0.1:5990`; goes into the client certificate. */
    val address: String,
) {
    companion object {
        fun parse(link: String): JoinLink {
            val uri = URI(link.trim())
            require(uri.scheme == "tcp" || uri.scheme == "tcp-direct") { "Join link must start with tcp://, got: $link" }
            val host = requireNotNull(uri.host) { "Join link has no host: $link" }
            require(uri.port > 0) { "Join link has no port: $link" }
            require(host != "0.0.0.0") { "Replace 0.0.0.0 in the join link with the backend's address" }
            val fragment = requireNotNull(uri.rawFragment) { "Join link has no #jt=…&fp=… part: $link" }
            val parameters = fragment.split('&').associate { parameter ->
                parameter.substringBefore('=') to URLDecoder.decode(parameter.substringAfter('=', ""), Charsets.UTF_8)
            }
            val joinToken = requireNotNull(parameters["jt"]) { "Join link has no jt (join token)" }
            val fingerprint = requireNotNull(parameters["fp"]) { "Join link has no fp (server fingerprint)" }
            return JoinLink(host, uri.port, joinToken, fingerprint, link.trim().substringBefore('#'))
        }
    }
}
