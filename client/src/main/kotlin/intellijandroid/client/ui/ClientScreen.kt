package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState

// Only common Compose APIs here (no AWT, no desktop-only modules), so this screen can move to Android as is.

/** The main window: Project tool window and editor as two "islands" on the window background, status line below. */
@Composable
fun ClientScreen(state: SessionState, session: ClientSession) {
    val fonts = LocalClientFonts.current
    val islandShape = RoundedCornerShape(IslandsDark.islandRadius)
    Column(Modifier.fillMaxSize().background(IslandsDark.windowBackground).padding(IslandsDark.islandGap)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ProjectToolWindow(
                tree = state.tree,
                onToggle = { session.toggle(it.id) },
                onSelect = { session.select(it.id) },
                onOpen = { node -> node.path?.takeIf { !node.isDirectory }?.let(session::open) },
                modifier = Modifier.width(300.dp).fillMaxHeight().clip(islandShape).background(IslandsDark.islandBackground),
            )
            Spacer(Modifier.width(IslandsDark.islandGap))
            Column(Modifier.weight(1f).fillMaxHeight().clip(islandShape).background(IslandsDark.islandBackground)) {
                val selected = state.editors.firstOrNull { it.path == state.selectedEditor }
                EditorTabs(state, session)
                val scheme = state.scheme
                if (selected != null && scheme != null) {
                    EditorView(selected, scheme, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText(
                            if (state.tree.nodes.isEmpty()) "Connecting…" else "Double-click a file in the Project tree to open it",
                            style = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp),
                        )
                    }
                }
            }
        }
        val status = state.failure ?: state.log.lastOrNull().orEmpty()
        BasicText(
            status,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (state.failure != null) Color(0xFFFA6675) else IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp),
        )
    }
}

/**
 * Editor tabs: file icon and name, the selected one underlined.
 * ponytail: approximate New UI tab metrics (height 40, 2 px accent underline); measure against IntelliJ when tabs matter.
 */
@Composable
private fun EditorTabs(state: SessionState, session: ClientSession) {
    if (state.editors.isEmpty()) return
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (editor in state.editors) {
            val selected = editor.path == state.selectedEditor
            val iconPath = state.tree.nodes.values.firstOrNull { it.path == editor.path }?.iconPaths?.firstOrNull() ?: "expui/fileTypes/text.svg"
            val underline = Color(0xFF3574F0)
            Box(
                Modifier.fillMaxHeight().clickable { session.selectEditor(editor.path) }
                    .drawBehind { if (selected) drawRect(underline, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                    .padding(horizontal = 10.dp),
            ) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    icons.painter(iconPath)?.let { Image(it, null, Modifier.size(16.dp)) }
                    Spacer(Modifier.width(6.dp))
                    BasicText(editor.name, style = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp))
                }
                
            }
        }
    }
}
