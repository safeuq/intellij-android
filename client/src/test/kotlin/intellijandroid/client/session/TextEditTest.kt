package intellijandroid.client.session

import intellijandroid.client.session.EditSession.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditTest {
    @Test
    fun insertingAtTheEndLeavesTheRangeAlone() {
        // Recorded case: `;` at 209..210, then Enter and "    int co" typed at 210. The `;` highlight must stay `;`.
        assertEquals(209 to 210, TextEdit(210, 0, 5).shift(209, 210))
    }

    @Test
    fun insertingAtTheStartMovesTheRange() = assertEquals(215 to 218, TextEdit(210, 0, 5).shift(210, 213))

    @Test
    fun insertingInsideGrowsTheRange() = assertEquals(208 to 213, TextEdit(209, 0, 2).shift(208, 211))

    @Test
    fun replacingTheEndKeepsTheReplacementInside() = assertEquals(200 to 206, TextEdit(203, 2, 3).shift(200, 205))

    @Test
    fun deletingTheWholeRangeLeavesAnEmptyRange() = assertEquals(200 to 200, TextEdit(200, 10, 0).shift(203, 208))

    @Test
    fun anEmptyRangeAtTheInsertionPointMoves() = assertEquals(212 to 212, TextEdit(210, 0, 2).shift(210, 210))
}
