package intellijandroid.client.connection

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** A connected, accepted transport: TLS socket plus the frame channel on top of it. */
class Connection(val socket: SSLSocket, val frames: FrameChannel) : AutoCloseable {
    override fun close() {
        frames.close()
        runCatching { socket.close() }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000

        /**
         * TCP → TLS 1.3 with client certificate → frames → handshake, as in docs/split-protocol.md ("Order of steps"):
         * TRANSPORT_INDEX(0) → TRANSPORT_ACCEPTANCE(1) → WIRE_EXIST(0) → (WIRE_EXIST, JOIN_PIN) → ACCEPTED → ACCEPTED.
         */
        fun open(link: JoinLink, certificate: ClientCertificate, log: (String) -> Unit): Connection {
            // The backend counts transports per client id and declines an index it has seen ("Transport index is
            // outdated"). A new identity starts at 0; a stored one, used again, needs a higher index than its last
            // connection's, so seconds since 1970 (fits an Int until 2038).
            val transportIndex = if (certificate.isNew) 0 else (System.currentTimeMillis() / 1000).toInt()
            val tcp = Socket()
            tcp.connect(InetSocketAddress(link.host, link.port), HANDSHAKE_TIMEOUT_MILLIS)
            tcp.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            tcp.tcpNoDelay = true
            log("TCP connected to ${link.host}:${link.port}")

            val socket = tlsContext(link, certificate).socketFactory.createSocket(tcp, link.host, link.port, true) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.useClientMode = true
            socket.startHandshake()
            log("TLS ${socket.session.protocol} done, server fingerprint matches fp")

            val frames = FrameChannel(socket.inputStream, socket.outputStream)
            try {
                handshake(frames, transportIndex, log)
            } catch (failure: Exception) {
                frames.close()
                socket.close()
                throw failure
            }
            return Connection(socket, frames)
        }

        private fun handshake(frames: FrameChannel, transportIndex: Int, log: (String) -> Unit) {
            frames.writeSystem(WireStatus.TRANSPORT_INDEX, ByteBuffer.allocate(4).putInt(transportIndex).array())
            val acceptance = readSystem(frames)
            check(acceptance.status == WireStatus.TRANSPORT_ACCEPTANCE && acceptance.data.firstOrNull() == 1.toByte()) {
                "Backend did not accept the transport: $acceptance"
            }
            log("Transport accepted")

            frames.writeSystem(WireStatus.WIRE_EXIST, byteArrayOf(0))
            while (true) {
                val frame = readSystem(frames)
                when (frame.status) {
                    WireStatus.WIRE_EXIST -> log("Backend WIRE_EXIST = ${frame.data.firstOrNull()}")
                    WireStatus.JOIN_PIN -> log("Join PIN ${frame.data.joinToString("")}")
                    WireStatus.SESSION_TOKEN, WireStatus.PRE_PROTOCOL_MESSAGE -> log("Ignored ${frame.status}")
                    WireStatus.ACCEPTED -> break
                    else -> error("Backend refused the connection: ${frame.status}")
                }
            }
            frames.writeSystem(WireStatus.ACCEPTED)
            log("Connection accepted")

            // Both sides then say which of the other's frames they already have, so either can resend the rest
            // (ServiceMessageProcessor.resendUnconfirmedFrames). A new wire has received nothing: 0.
            frames.writeSystem(WireStatus.CURRENT_CONFIRMED_FRAME_ACK, ByteBuffer.allocate(4).putInt(0).array())
            val confirmation = readSystem(frames)
            check(confirmation.status == WireStatus.CURRENT_CONFIRMED_FRAME_ACK) { "Expected the backend's CURRENT_CONFIRMED_FRAME_ACK, got $confirmation" }
            log("Backend has confirmed our frames up to #${ByteBuffer.wrap(confirmation.data).getInt()}")
        }

        private fun readSystem(frames: FrameChannel): Frame.System {
            while (true) {
                when (val frame = frames.read()) {
                    is Frame.Ping -> continue
                    is Frame.System -> return frame
                    is Frame.Message -> error("rd message before the handshake finished: $frame")
                }
            }
        }

        private fun tlsContext(link: JoinLink, certificate: ClientCertificate): SSLContext =
            SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(SingleKeyManager(certificate)), arrayOf(FingerprintTrustManager(link.fingerprint)), null)
            }
    }
}

/** Always offers our one client certificate, whatever issuers the server asks for (our CA is random, it can't match). */
private class SingleKeyManager(private val certificate: ClientCertificate) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = certificate.chain
    override fun getPrivateKey(alias: String?): PrivateKey = certificate.privateKey
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null

    private companion object {
        const val ALIAS = "client"
    }
}

/** Trusts exactly one server certificate whose SHA3-256 fingerprint equals the join link's `fp`; no CA, date or host checks. */
private class FingerprintTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.size != 1) throw CertificateException("Expected exactly 1 server certificate, got ${chain.size}")
        val fingerprint = ClientCertificate.fingerprint(chain.single())
        if (fingerprint != expectedFingerprint) throw CertificateException("Server fingerprint $fingerprint does not match fp $expectedFingerprint")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("This side is the client")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
