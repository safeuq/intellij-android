package intellijandroid.client.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CompletionTest {
    @Test
    fun camelHumpsMatchLikeTheRecordedPopup() {
        // Recorded for the prefix "gre": greeting, getRegistry and getReceiver were all offered.
        assertEquals(setOf(0, 1, 2), matchedIndices("gre", "greeting"))
        assertEquals(setOf(0, 3, 4), matchedIndices("gre", "getRegistry"))
        assertEquals(setOf(0, 3, 4), matchedIndices("gre", "getReceiver"))
        assertNull(matchedIndices("gx", "greeting"))
        assertNull(matchedIndices("re", "greeting"))
    }

    @Test
    fun iconIdBlobHoldsTheIconPath() {
        // `presentation.icon.serializedValue` of the recorded item "greeting".
        val blob = "Pj1d3rHk9q2MAAAAPQAAAAAAAAABKV1/a6Mo6e13AAAAAQAAAAguMPjbbDkRZwAAABUBAAAAAAAAGAAAAGUAeABwAHUAaQAvAGYAaQBsAGUAVAB5AHAAZQBzAC8AagBhAHYAYQAuAHMAdgBnAAEMAAAAYwBvAG0ALgBpAG4AdABlAGwAbABpAGoAABAAAAAQAAAAAAAAAAAAAAAAAAAAAA=="
        assertEquals("expui/fileTypes/java.svg", iconPath(buildJsonObject { put("serializedValue", blob) }))
    }
}
