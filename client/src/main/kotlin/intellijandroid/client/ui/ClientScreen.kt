package intellijandroid.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.OpenFile
import intellijandroid.client.session.SessionState

// Only common Compose APIs here (no AWT, no desktop-only modules), so this screen can move to Android as is.

private val editorBackground = Color(0xFF1E1F22)
private val editorForeground = Color(0xFFBCBEC4)

@Composable
fun ClientScreen(state: SessionState) {
    Column(Modifier.fillMaxSize()) {
        val file = state.file
        if (file != null) {
            Text("${file.path} · ${file.highlights.size} highlights", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            Editor(file, Modifier.weight(1f).fillMaxWidth())
            HorizontalDivider()
        }
        state.failure?.let { failure -> Text(failure, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(if (file == null) 1f else 0.3f).fillMaxWidth().padding(8.dp)) {
            items(state.log) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Editor(file: OpenFile, modifier: Modifier) {
    val highlighted = remember(file) {
        buildAnnotatedString {
            append(file.text)
            for (highlight in file.highlights) {
                val argb = highlight.argb ?: continue
                addStyle(SpanStyle(color = Color(argb)), highlight.start, highlight.end)
            }
        }
    }
    SelectionContainer(modifier.background(editorBackground)) {
        Text(
            highlighted,
            Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            color = editorForeground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            softWrap = false,
        )
    }
}
