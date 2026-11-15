package intellijandroid.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import intellijandroid.client.connection.JoinLink
import intellijandroid.client.session.ClientSession
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.ClientScreen
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.LocalClientFonts
import intellijandroid.client.ui.LocalEditorTextInput
import intellijandroid.client.ui.LocalIcons

/**
 * Asks for the backend's join link, then shows the same screen as the desktop client.
 * For scripted runs: `adb shell am start -n intellijandroid.client.android/.MainActivity --es joinLink '<link>' [--es openFile '<path>']`
 * connects at once.
 */
class MainActivity : ComponentActivity() {
    private var session by mutableStateOf<ClientSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fonts = AndroidResources.fonts(this)
        val icons = AndroidResources.SvgIcons(AndroidResources.iconJar(this), resources.displayMetrics.density)
        val preferences = getSharedPreferences("connection", MODE_PRIVATE)
        intent.getStringExtra("joinLink")?.let { link -> connect(link, intent.getStringExtra("openFile")) }

        setContent {
            CompositionLocalProvider(LocalClientFonts provides fonts, LocalIcons provides icons, LocalEditorTextInput provides editorTextInput) {
                Box(Modifier.fillMaxSize().background(IslandsDark.windowBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val current = session
                    if (current == null) {
                        ConnectForm(
                            fonts = fonts,
                            initialLink = preferences.getString("joinLink", "").orEmpty(),
                            initialFile = preferences.getString("openFile", "").orEmpty(),
                        ) { link, file ->
                            preferences.edit { putString("joinLink", link); putString("openFile", file) }
                            connect(link, file.ifBlank { null })
                        }
                    } else {
                        val state by current.state.collectAsState()
                        ClientScreen(state, current)
                    }
                }
            }
        }
    }

    private fun connect(link: String, file: String?) {
        // JoinLink.parse throws on a malformed link before anything connects.
        session = ClientSession(JoinLink.parse(link.trim()), file?.trim()).also { it.start() }
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }
}

@Composable
private fun ConnectForm(fonts: ClientFonts, initialLink: String, initialFile: String, onConnect: (link: String, file: String) -> Unit) {
    var link by remember { mutableStateOf(initialLink) }
    var file by remember { mutableStateOf(initialFile) }
    var error by remember { mutableStateOf<String?>(null) }
    val label = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)
    val input = TextStyle(color = IslandsDark.text, fontFamily = fonts.editor, fontSize = IslandsDark.EDITOR_FONT_SIZE.sp)
    val fieldShape = RoundedCornerShape(IslandsDark.tabRadius)

    @Composable
    fun Field(value: String, onChange: (String) -> Unit) = BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = input,
        cursorBrush = SolidColor(IslandsDark.text),
        modifier = Modifier.fillMaxWidth().background(IslandsDark.islandBackground, fieldShape)
            .border(1.dp, IslandsDark.tabSelectedBorder, fieldShape).padding(8.dp),
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Join link (printed by remote-dev-server.sh)", style = label)
            Field(link) { link = it }
            BasicText("File to open, optional (absolute path on the backend)", style = label)
            Field(file) { file = it }
            BasicText(
                "Connect",
                style = input.copy(fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp),
                modifier = Modifier.background(IslandsDark.tabSelectedBackground, fieldShape)
                    .border(1.dp, IslandsDark.tabSelectedBorder, fieldShape)
                    .clickable {
                        error = runCatching { JoinLink.parse(link.trim()) }.exceptionOrNull()?.message
                        if (error == null) onConnect(link, file)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            error?.let { BasicText(it, style = label.copy(color = IslandsDark.text)) }
        }
    }
}
