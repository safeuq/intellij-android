package intellijandroid.client.connection

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.HexFormat
import java.util.UUID

/** The client certificate the backend requires, built like JetBrains Client's (docs/split-protocol.md, "TLS"). */
class ClientCertificate(val privateKey: PrivateKey, val chain: Array<X509Certificate>, val clientId: String) {
    companion object {
        private val bouncyCastle = BouncyCastleProvider()

        /**
         * Subject, for example: `SERIALNUMBER=tcp://127.0.0.1:5990, UNIQUE_IDENTIFIER=<clientId>, T=<install id>,
         * CN=<user>, PSEUDONYM=<jt>`. The backend reads the join token from `PSEUDONYM`, and rejects a client id that
         * doesn't start with the authority certificate's fingerprint ("clientId does not start with client fingerprint").
         */
        fun create(link: JoinLink, userName: String): ClientCertificate {
            val authority = newKeyPair()
            val authorityCertificate = build(
                subject = X500NameBuilder().addRDN(BCStyle.CN, UUID.randomUUID().toString()).build(),
                subjectKey = authority.public,
                issuer = null,
                issuerKey = authority.public,
                signingKey = authority.private,
                validity = Duration.ofDays(60),
            )
            // Like JetBrains Client's ThinClientId: "<SHA3 fingerprint of the authority>-<epoch milliseconds>".
            val clientId = fingerprint(authorityCertificate) + "-" + System.currentTimeMillis()
            val leaf = newKeyPair()
            val subject = X500NameBuilder()
                .addRDN(BCStyle.SERIALNUMBER, link.address)
                .addRDN(BCStyle.UNIQUE_IDENTIFIER, clientId)
                .addRDN(BCStyle.T, UUID.randomUUID().toString())
                .addRDN(BCStyle.CN, userName)
                .addRDN(BCStyle.PSEUDONYM, link.joinToken)
                .build()
            // Like JetBrains Client: issuer is the per-process authority, but the leaf signs itself.
            val leafCertificate = build(subject, leaf.public, authorityCertificate, authority.public, leaf.private, Duration.ofDays(1))
            return ClientCertificate(leaf.private, arrayOf(leafCertificate, authorityCertificate), clientId)
        }

        /** `UPPERCASE_HEX(SHA3-256(DER))`, compared with the join link's `fp`. BouncyCastle, because Android lacks SHA3. */
        fun fingerprint(certificate: X509Certificate): String =
            HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA3-256", bouncyCastle).digest(certificate.encoded))

        private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

        private fun build(
            subject: X500Name,
            subjectKey: PublicKey,
            issuer: X509Certificate?,
            issuerKey: PublicKey,
            signingKey: PrivateKey,
            validity: Duration,
        ): X509Certificate {
            val now = Instant.now()
            val issuerName = if (issuer == null) subject else X500Name.getInstance(issuer.subjectX500Principal.encoded)
            val extensionUtils = BcX509ExtensionUtils()
            val builder = X509v3CertificateBuilder(
                issuerName,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now),
                Date.from(now.plus(validity)),
                subject,
                SubjectPublicKeyInfo.getInstance(subjectKey.encoded),
            )
                .addExtension(Extension.subjectKeyIdentifier, false, extensionUtils.createSubjectKeyIdentifier(SubjectPublicKeyInfo.getInstance(subjectKey.encoded)))
                .addExtension(Extension.authorityKeyIdentifier, false, extensionUtils.createAuthorityKeyIdentifier(SubjectPublicKeyInfo.getInstance(issuerKey.encoded)))
                .addExtension(Extension.basicConstraints, true, BasicConstraints(issuer == null))
            val signer = JcaContentSignerBuilder("SHA512WithRSA").build(signingKey)
            return JcaX509CertificateConverter().setProvider(bouncyCastle).getCertificate(builder.build(signer))
        }
    }
}
