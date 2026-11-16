package intellijandroid.client.connection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameChannelTest {
    @Test
    fun framesRoundTripThroughOneDeflateStream() {
        val wire = ByteArrayOutputStream()
        val writer = FrameChannel(ByteArrayInputStream(ByteArray(0)), wire)
        writer.writeSystem(WireStatus.TRANSPORT_INDEX, byteArrayOf(0, 0, 0, 0))
        writer.writePing()
        writer.writeMessage(1, 0, byteArrayOf(9, 8, 7, 6, 5), 3)

        val reader = FrameChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream())
        val system = assertIs<Frame.System>(reader.read())
        assertEquals(WireStatus.TRANSPORT_INDEX, system.status)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), system.data)
        assertIs<Frame.Ping>(reader.read())
        val message = assertIs<Frame.Message>(reader.read())
        assertEquals(1, message.sequence)
        assertEquals(0, message.lastReceivedSequence)
        assertContentEquals(byteArrayOf(9, 8, 7), message.payload)
    }

    @Test
    fun everyFlushEndsWithSyncMarkerAndUncompressedBytesMatchTheDoc() {
        val wire = ByteArrayOutputStream()
        FrameChannel(ByteArrayInputStream(ByteArray(0)), wire).writeSystem(WireStatus.TRANSPORT_INDEX, byteArrayOf(0, 0, 0, 0))
        val compressed = wire.toByteArray()
        // SYNC_FLUSH marker: an empty stored block, 00 00 FF FF.
        assertContentEquals(byteArrayOf(0, 0, -1, -1), compressed.copyOfRange(compressed.size - 4, compressed.size))

        val inflater = Inflater(true).apply { setInput(compressed) }
        val plain = ByteArray(16).also { inflater.inflate(it) }
        // docs/split-protocol.md: TRANSPORT_INDEX = 0 is 00 00 00 0C  FF FF FF FF  00 00 00 09  00 00 00 00
        assertContentEquals(
            byteArrayOf(0, 0, 0, 12, -1, -1, -1, -1, 0, 0, 0, 9, 0, 0, 0, 0),
            plain,
        )
    }
}
