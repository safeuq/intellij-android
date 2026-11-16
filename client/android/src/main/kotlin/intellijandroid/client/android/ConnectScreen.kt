package intellijandroid.client.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.LocalIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the connect screen shows; the activity owns it. */
data class ConnectScreenState(
    val link: String,
    val file: String,
    val discovered: List<DiscoveredBackend>,
    val history: List<SavedConnection>,
    val termux: TermuxAccess,
    /** Work in progress, e.g. "Asking Termux for the backend's link…"; taps are ignored meanwhile. */
    val busy: String?,
    /** The last error or hint. */
    val message: String?,
    /** Set when Termux has no backend running: the project folder to offer `intellij-backend start` for. */
    val startProject: String?,
)

enum class TermuxAccess { NOT_INSTALLED, NO_PERMISSION, READY }

class ConnectActions(
    val linkChanged: (String) -> Unit,
    val fileChanged: (String) -> Unit,
    val connectLink: () -> Unit,
    val openDiscovered: (DiscoveredBackend) -> Unit,
    val openSaved: (SavedConnection) -> Unit,
    val removeSaved: (SavedConnection) -> Unit,
    val allowTermux: () -> Unit,
    val startChanged: (String) -> Unit,
    val startInTermux: () -> Unit,
    val listFolders: (path: String, onResult: (Result<List<Termux.Folder>>) -> Unit) -> Unit,
    val refresh: () -> Unit,
)

/** Link prompt on the left; backends running on this phone and connections made before on the right (docs/connections.md). */
@Composable
fun ConnectScreen(state: ConnectScreenState, actions: ConnectActions, fonts: ClientFonts) {
    val styles = ConnectStyles(fonts)
    var choosingFolder by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Join link (printed by intellij-backend or remote-dev-server.sh)", style = styles.label)
            Field(state.link, actions.linkChanged, styles)
            BasicText("File to open, optional (absolute path on the backend)", style = styles.label)
            Field(state.file, actions.fileChanged, styles)
            Button("Connect", styles, actions.connectLink)
            state.busy?.let { BasicText(it, style = styles.label) }
            state.message?.let { BasicText(it, style = styles.label.copy(color = IslandsDark.text)) }
            state.startProject?.let { project ->
                BasicText("No backend is running in Termux. Project folder to start one for:", style = styles.label)
                Field(project, actions.startChanged, styles, onBrowse = { choosingFolder = true })
                Button("Start in Termux", styles, actions.startInTermux)
                if (choosingFolder) {
                    FolderChooser(
                        title = "Select Project Folder",
                        initialPath = project,
                        listFolders = actions.listFolders,
                        onChoose = { folder -> choosingFolder = false; actions.startChanged(folder) },
                        onCancel = { choosingFolder = false },
                    )
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("On this phone", style = styles.heading, modifier = Modifier.weight(1f))
                BasicText("Refresh", style = styles.link, modifier = Modifier.clickable(onClick = actions.refresh).padding(4.dp))
            }
            if (state.termux == TermuxAccess.NO_PERMISSION) {
                Entry("Termux on this phone", "Tap to let this app run intellij-backend in Termux", styles, onClick = actions.allowTermux)
            }
            if (state.discovered.isEmpty()) BasicText("No backend running on this phone", style = styles.label)
            state.discovered.forEach { backend ->
                Entry(backend.name, listOfNotNull("${backend.host}:${backend.port}", backend.project).joinToString(" · "), styles) { actions.openDiscovered(backend) }
            }
            BasicText("History", style = styles.heading, modifier = Modifier.padding(top = 12.dp))
            if (state.history.isEmpty()) BasicText("Connections you make show up here", style = styles.label)
            state.history.forEach { saved ->
                // LINK names already end in the address, e.g. `smoke · 192.168.0.105:5990`.
                val address = "${saved.host}:${saved.port}".takeIf { !saved.name.endsWith(it) }
                val details = listOfNotNull(address, saved.project, LAST_CONNECTED.format(Instant.ofEpochMilli(saved.lastConnected)))
                Entry(saved.name, details.joinToString(" · "), styles, onRemove = { actions.removeSaved(saved) }) { actions.openSaved(saved) }
            }
        }
    }
}

private val LAST_CONNECTED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

private class ConnectStyles(fonts: ClientFonts) {
    val label = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)
    val heading = label.copy(color = IslandsDark.text, fontWeight = FontWeight.Bold)
    val title = label.copy(color = IslandsDark.text)
    val link = label.copy(color = IslandsDark.link)
    val input = TextStyle(color = IslandsDark.text, fontFamily = fonts.editor, fontSize = IslandsDark.EDITOR_FONT_SIZE.sp)
    val shape = RoundedCornerShape(IslandsDark.tabRadius)
}

/** A text field; with [onBrowse], IntelliJ's browse icon sits inside it on the right (`TextFieldWithBrowseButton`). */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, styles: ConnectStyles, onBrowse: (() -> Unit)? = null) {
    val browse = onBrowse?.let { LocalIcons.current.painter("expui/inline/browse.svg") }
    Row(
        Modifier.fillMaxWidth().background(IslandsDark.islandBackground, styles.shape)
            .border(1.dp, IslandsDark.tabSelectedBorder, styles.shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = styles.input,
            cursorBrush = SolidColor(IslandsDark.text),
            modifier = Modifier.weight(1f).padding(8.dp),
        )
        if (onBrowse != null && browse != null) {
            Image(
                browse,
                contentDescription = "Browse",
                modifier = Modifier.padding(end = 6.dp).clickable(onClick = onBrowse).padding(2.dp).size(16.dp),
            )
        }
    }
}

@Composable
private fun Button(text: String, styles: ConnectStyles, onClick: () -> Unit) = BasicText(
    text,
    style = styles.title,
    modifier = Modifier.background(IslandsDark.tabSelectedBackground, styles.shape)
        .border(1.dp, IslandsDark.tabSelectedBorder, styles.shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
private fun Entry(title: String, details: String, styles: ConnectStyles, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(IslandsDark.islandBackground, styles.shape).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = styles.title)
            BasicText(details, style = styles.label)
        }
        onRemove?.let { remove -> BasicText("Remove", style = styles.link, modifier = Modifier.clickable(onClick = remove).padding(4.dp)) }
    }
}
