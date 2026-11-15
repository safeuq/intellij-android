package intellijandroid.client.session

/**
 * The version hashes of IntelliJ's patch engine (`IncrementalHashKt`, `DocumentVersionsKt`, `EditorVersionsKt` in the
 * client jars). Signed 64-bit arithmetic that wraps around, exactly as Kotlin's Long does.
 *
 * A document's hash is a rolling hash of its edit history, not of its text: start from the backend's reset version and
 * fold every change into it. Example (recorded): the 260-character Main.java resets to -5446235163934729545; inserting
 * "\n    " at offset 210 gives -6257571537734692227.
 */
object VersionHashes {
    private fun mix(hash: Long, value: Int): Long = hash * 31 + value

    private fun mix(hash: Long, text: String): Long {
        var result = hash
        for (character in text) result = result * 31 + character.code
        return result
    }

    /** Hash after `RdResetDocumentChange(text)`. */
    fun reset(text: String): Long = mix(127, text)

    /** Hash after `RdDocumentChange(offset, moveOffset, oldDocumentLength, oldText, newText)`. */
    fun change(previous: Long, offset: Int, moveOffset: Int, oldDocumentLength: Int, oldText: String, newText: String): Long =
        mix(mix(mix(mix(mix(previous, offset), moveOffset), oldDocumentLength), oldText), newText)

    /** `RdEditorVersion.hash` of the primary caret: collapsed at [offset], or the selection [selectionStart]..[selectionEnd]. */
    fun caret(caretNumber: Long, offset: Int, selectionStart: Int, selectionEnd: Int): Long {
        val initial = 127L * 31 + caretNumber
        return if (selectionStart != selectionEnd) {
            initial * 31 + (initial * 31 + selectionStart) * (initial * 31 + selectionEnd)
        } else {
            initial * 31 + offset
        }
    }
}
