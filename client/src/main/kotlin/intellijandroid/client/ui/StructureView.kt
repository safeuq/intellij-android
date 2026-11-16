package intellijandroid.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState

/** The Structure tool window: the structure of the file in the selected editor. */
@Composable
fun StructureView(state: SessionState, session: ClientSession, modifier: Modifier) {
    val fonts = LocalClientFonts.current
    Box(modifier.padding(12.dp)) {
        BasicText("Structure: not connected yet", style = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp))
    }
}
