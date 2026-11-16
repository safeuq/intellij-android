package intellijandroid.client.connection

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientCertificateTest {
    @Test
    fun storedIdentityDecodesToTheSameKeyAndCertificates() {
        val link = JoinLink.parse("tcp://127.0.0.1:5990#jt=584c7a19-0df1-4798-be5c-7af786a00846&fp=ABCD")
        val created = ClientCertificate.create(link, "android")
        val decoded = ClientCertificate.decode(created.encode())

        assertTrue(created.isNew)
        assertFalse(decoded.isNew)
        assertEquals(created.clientId, decoded.clientId)
        assertContentEquals(created.privateKey.encoded, decoded.privateKey.encoded)
        assertContentEquals(created.chain.map { it.encoded.toList() }, decoded.chain.map { it.encoded.toList() })
        // The backend rejects a client id that doesn't start with the authority's fingerprint.
        assertTrue(decoded.clientId.startsWith(ClientCertificate.fingerprint(decoded.chain[1]) + "-"))
    }
}
