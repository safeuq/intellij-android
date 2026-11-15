package intellijandroid.client.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * IntelliJ IDEA 2026.3 "Islands Dark" UI chrome, from the theme files and Swing painting code (values and sources:
 * docs/intellij-look.md). Editor text colors don't live here: they come from the backend's color scheme.
 */
object IslandsDark {
    val windowBackground = Color(0xFF26282C)      // main-window-bg, behind the islands
    val islandBackground = Color(0xFF191A1C)      // layer-0-bg: tool windows and editor
    val islandRadius = 10.dp                      // Island.arc 20 = radius 10
    val islandGap = 6.dp                          // Island.borderWidth 6

    val text = Color(0xFFD1D3D9)                  // text-default
    val textSecondary = Color(0xFF73767C)         // grayed (e.g. path after the module name)

    val headerHeight = 41.dp                      // ToolWindow header
    val headerLeftInset = 12.dp

    val treeRowHeight = 22.dp                     // measured on the real client (theme file says 24)
    val treeTopInset = 4.dp
    val treeSideInset = 12.dp                     // Tree.border 4,12,4,12; also the selection's left/right inset
    val treeIndent = 19.dp                        // leftChildIndent 7 → 8 + rightChildIndent 11
    val treeIconTextGap = 2.dp
    val treeSelectionFocused = Color(0xFF2A4371)  // selection-bg-active
    val treeSelectionUnfocused = Color(0xFF33353B) // selection-bg-inactive
    val treeHover = Color(0x10FFFFFF)             // selection-bg-hovered

    val tabRowHeight = 32.dp                      // measured: editor tabs row
    val tabHeight = 24.dp
    val tabSelectedBackground = Color(0xFF233657) // measured: selected editor tab pill
    val tabSelectedBorder = Color(0xFF2C4A82)
    val tabRadius = 6.dp
    val treeSelectionRadius = 4.dp                // Tree.Selection.arc 8 passed as arc diameter

    const val UI_FONT_SIZE = 13f                  // Inter 13 (user setting)
    const val EDITOR_FONT_SIZE = 12f              // JetBrains Mono 12 (user setting)
    const val LINE_NUMBER_FONT_SIZE = 11f         // editor size - 1
    const val LINE_SPACING = 1.2f                 // default editor line spacing
}

/** Fonts the screens use; the platform supplies them (desktop: files in the JetBrains Client's JBR). */
data class ClientFonts(val editor: FontFamily, val ui: FontFamily)

/** Loads IntelliJ icons by their path in the icons jar, e.g. `expui/nodes/folder.svg`; the platform supplies it. */
fun interface IconProvider {
    fun painter(path: String): Painter?
}

val LocalClientFonts = staticCompositionLocalOf { ClientFonts(FontFamily.Monospace, FontFamily.SansSerif) }
val LocalIcons = staticCompositionLocalOf { IconProvider { null } }
