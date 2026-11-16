package intellijandroid.client.session

import kotlin.test.Test
import kotlin.test.assertEquals

/** Numbers recorded from the real JetBrains Client typing into this file (2026-11-15). */
class VersionHashesTest {
    private val original = """
        |package demo;
        |
        |public class Main {
        |  public static void main(final String[] arguments) {
        |    final String unused = "never read";
        |    String greeting = "just a simple greeting";
        |    System.out.println(greeting);
        |    System.out.println(greeting.length());
        |  }
        |}
        |""".trimMargin()

    @Test
    fun documentHashesFollowTheRecordedChain() {
        assertEquals(260, original.length)
        val reset = VersionHashes.reset(original)
        assertEquals(-5446235163934729545, reset)
        // Enter at the end of line 7 inserts a line break and the 4-space indent.
        val endOfLine7 = original.indexOf("System.out.println(greeting);") + "System.out.println(greeting);".length
        assertEquals(210, endOfLine7)
        val afterEnter = VersionHashes.change(reset, 210, 210, 260, "", "\n    ")
        assertEquals(-6257571537734692227, afterEnter)
        // Then typing 'i' at 215.
        assertEquals(-2740688552363290787, VersionHashes.change(afterEnter, 215, 215, 265, "", "i"))
    }

    @Test
    fun caretHashesMatchTheRecordedEditorVersions() {
        assertEquals(122078, VersionHashes.caret(1, 0, 0, 0))
        assertEquals(122288, VersionHashes.caret(1, 210, 210, 210))
        assertEquals(14938339346, VersionHashes.caret(1, 148, 140, 148))
    }
}
