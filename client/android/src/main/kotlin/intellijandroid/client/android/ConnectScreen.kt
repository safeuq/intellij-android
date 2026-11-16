package intellijandroid.client.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import intellijandroid.client.ui.ClientFonts
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.LocalIcons

/** What the connect screen shows; the activity owns it. */
data class ConnectScreenState(
    val page: ConnectPage,
    val link: String,
    val file: String,
    val discovered: List<DiscoveredBackend>,
    val history: List<SavedConnection>,
    val termux: TermuxAccess,
    /** Work in progress, e.g. "Asking Termux for the backend's link…"; taps are ignored meanwhile. */
    val busy: String?,
    /** The last error or hint. */
    val message: String?,
    /** A yes/no question, e.g. "Take over?" when Termux's backend has another client. */
    val question: Question?,
    /** `intellij-backend start` is running for this project: shows Gateway's "Starting the remote IDE Backend" popup. */
    val starting: String?,
)

/** The sidebar's connection kinds, like Gateway's SSH and Link. SSH is not built yet. */
enum class ConnectPage { PHONE, LINK }

enum class TermuxAccess { NOT_INSTALLED, NO_PERMISSION, READY }

class Question(val text: String, val confirm: String, val onConfirm: () -> Unit)

class ConnectActions(
    val pageChanged: (ConnectPage) -> Unit,
    val linkChanged: (String) -> Unit,
    val fileChanged: (String) -> Unit,
    val connectLink: () -> Unit,
    val openDiscovered: (DiscoveredBackend) -> Unit,
    val openSaved: (SavedConnection) -> Unit,
    val removeSaved: (SavedConnection) -> Unit,
    val allowTermux: () -> Unit,
    /** Starts a backend in Termux for the chosen folder. */
    val newProject: (String) -> Unit,
    val stopTermux: () -> Unit,
    val listFolders: (path: String, onResult: (Result<List<Termux.Folder>>) -> Unit) -> Unit,
    val refresh: () -> Unit,
    val answerQuestion: () -> Unit,
    val cancelQuestion: () -> Unit,
    val cancelStart: () -> Unit,
)

/**
 * Gateway's welcome window ("Welcome to JetBrains Gateway", 2026.3): connection kinds in a sidebar, recent projects
 * grouped by host on the right. Sizes measured on a 2x screenshot, in dp = pt (docs/intellij-look.md §10). Left out:
 * the logo, All Providers, the settings gear and New Project's drop-down. Upright, the sidebar becomes a tab bar at the
 * bottom (Gateway has no portrait layout).
 */
@Composable
fun ConnectScreen(state: ConnectScreenState, actions: ConnectActions, fonts: ClientFonts) {
    var choosingFolder by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Gateway.background)) {
        // Upright, the sidebar would take a third of the width for two entries: the connection kinds move to a tab bar
        // at the bottom, like the main window's tool window bar, and project rows put the status under the path.
        val portrait = maxHeight > maxWidth
        val styles = ConnectStyles(fonts, twoLineRows = portrait)
        @Composable
        fun Page(modifier: Modifier) = Column(modifier) {
            when (state.page) {
                ConnectPage.PHONE -> PhonePage(state, actions, styles, onNewProject = { choosingFolder = true })
                ConnectPage.LINK -> LinkPage(state, actions, styles)
            }
        }
        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                Page(Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
                BottomTabs(state.page, actions.pageChanged, styles)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar(state.page, actions.pageChanged, styles, Modifier.width(Gateway.sidebarWidth))
                Box(Modifier.width(1.dp).fillMaxHeight().background(Gateway.divider))
                Page(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
    if (choosingFolder) {
        FolderChooser(
            title = "Select Project Folder",
            initialPath = "${Termux.HOME}/",
            listFolders = actions.listFolders,
            onChoose = { folder -> choosingFolder = false; actions.newProject(folder) },
            onCancel = { choosingFolder = false },
        )
    }
    state.starting?.let { project -> StartProgress(project, fonts, actions.cancelStart) }
}

/** The two connection kinds side by side; the selected one gets the stripe buttons' selected background. */
@Composable
private fun BottomTabs(page: ConnectPage, onSelect: (ConnectPage) -> Unit, styles: ConnectStyles) {
    Row(Modifier.fillMaxWidth().height(Gateway.tabBarHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PAGES.forEach { (target, iconPath, label) ->
            val icon = LocalIcons.current.painter(iconPath)
            Row(
                Modifier.weight(1f).padding(horizontal = 2.dp).height(Gateway.tabHeight)
                    .background(if (page == target) Gateway.tabSelected else Color.Transparent, Gateway.tabShape)
                    .clickable { onSelect(target) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
                BasicText(label, style = styles.body, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** The connection kinds: page, icon, label. */
private val PAGES = listOf(
    Triple(ConnectPage.PHONE, "expui/nodes/ssh.svg", "This phone"),
    Triple(ConnectPage.LINK, "markdown/link.svg", "Link"),
)

@Composable
private fun Sidebar(page: ConnectPage, onSelect: (ConnectPage) -> Unit, styles: ConnectStyles, modifier: Modifier) {
    @Composable
    fun Item(target: ConnectPage, iconPath: String, label: String) {
        val icon = LocalIcons.current.painter(iconPath)
        Row(
            Modifier.fillMaxWidth().height(Gateway.sidebarRowHeight)
                .background(if (page == target) Gateway.selection else Color.Transparent)
                .clickable { onSelect(target) }
                .padding(start = Gateway.sidebarIconInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            BasicText(label, style = styles.sidebarItem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
    Column(modifier.fillMaxHeight().padding(top = Gateway.sidebarTop)) {
        Box(Modifier.height(Gateway.sidebarRowHeight).padding(start = Gateway.sidebarTextInset), contentAlignment = Alignment.CenterStart) {
            BasicText("Connections", style = styles.secondary)
        }
        val (phone, link) = PAGES
        Item(phone.first, phone.second, phone.third)
        Box(Modifier.padding(vertical = 7.dp).fillMaxWidth().height(1.dp).background(Gateway.divider))
        Item(link.first, link.second, link.third)
    }
}

/** One project row: Gateway's product icon, the path in link blue, then Running with a stop square or Last active. */
private class ProjectRow(
    val title: String,
    val running: Boolean,
    val lastActive: Long?,
    val onOpen: () -> Unit,
    val onStop: (() -> Unit)?,
    val onRemove: (() -> Unit)?,
)

@Composable
private fun PhonePage(state: ConnectScreenState, actions: ConnectActions, styles: ConnectStyles, onNewProject: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val termuxReady = state.termux == TermuxAccess.READY
    Header("Recent Termux Projects", styles) {
        if (termuxReady) OutlinedButton("New Project", styles, onNewProject)
    }
    SearchRow(search, { search = it }, styles)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Notices(state, actions, styles)
        when (state.termux) {
            TermuxAccess.NOT_INSTALLED -> Notice("Termux isn't installed. Use Link to connect to a backend on another machine.", styles)
            TermuxAccess.NO_PERMISSION -> {
                Notice("This app needs permission to run intellij-backend in Termux.", styles)
                Box(Modifier.padding(horizontal = Gateway.contentInset)) { OutlinedButton("Allow", styles, actions.allowTermux) }
            }
            TermuxAccess.READY -> Unit
        }
        val running = state.discovered.firstOrNull { it.joinLink != null }
        val rows = buildList {
            running?.let { backend ->
                val entry = state.history.firstOrNull { it.kind == ConnectionKind.TERMUX && it.project == backend.project }
                add(ProjectRow(shortPath(backend.project ?: "port ${backend.port}"), true, null, { actions.openDiscovered(backend) }, actions.stopTermux, entry?.let { { actions.removeSaved(it) } }))
            }
            state.history.filter { it.kind == ConnectionKind.TERMUX && it.project != running?.project }.forEach { entry ->
                add(ProjectRow(shortPath(entry.project.orEmpty()), false, entry.lastConnected, { actions.openSaved(entry) }, null, { actions.removeSaved(entry) }))
            }
            // An open port that Termux doesn't know about, e.g. remote-dev-server.sh started by hand: needs its link.
            state.discovered.filter { it.joinLink == null }.forEach { backend ->
                add(ProjectRow("127.0.0.1:${backend.port}", true, null, { actions.openDiscovered(backend) }, null, null))
            }
        }.filter { search.isBlank() || it.title.contains(search.trim(), ignoreCase = true) }
        HostGroup("This phone", styles, icons = buildList {
            if (termuxReady) add(HeaderIcon("expui/general/add.svg", "New Project", onNewProject))
            add(HeaderIcon("expui/general/refresh.svg", "Refresh", actions.refresh))
        })
        if (rows.isEmpty()) {
            Notice(if (termuxReady && search.isBlank()) "No recent projects. Tap New Project to start a backend in Termux." else "No projects", styles)
        }
        rows.forEach { ProjectRowView(it, styles) }
    }
}

@Composable
private fun LinkPage(state: ConnectScreenState, actions: ConnectActions, styles: ConnectStyles) {
    Header("Connect by Link", styles) {}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = Gateway.contentInset, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Join link (printed by intellij-backend link or remote-dev-server.sh)", style = styles.secondary)
            Field(state.link, actions.linkChanged, styles)
            BasicText("File to open, optional (absolute path on the backend)", style = styles.secondary)
            Field(state.file, actions.fileChanged, styles)
            OutlinedButton("Connect", styles, actions.connectLink)
        }
        Notices(state, actions, styles)
        // Grouped by address, like Gateway groups by user@host; LINK names are `<project> · <host>:<port>`.
        state.history.filter { it.kind == ConnectionKind.LINK }.groupBy { "${it.host}:${it.port}" }.forEach { (address, entries) ->
            HostGroup(address, styles, icons = emptyList())
            entries.forEach { entry ->
                val title = entry.name.substringBefore(" · ").takeIf { entry.name.contains(" · ") } ?: address
                ProjectRowView(ProjectRow(title, false, entry.lastConnected, { actions.openSaved(entry) }, null, { actions.removeSaved(entry) }), styles)
            }
        }
    }
}

/** Busy text, the last message and a pending question, under the header. */
@Composable
private fun Notices(state: ConnectScreenState, actions: ConnectActions, styles: ConnectStyles) {
    state.busy?.let { Notice(it, styles) }
    state.message?.let { Notice(it, styles, color = IslandsDark.text) }
    state.question?.let { question ->
        Notice(question.text, styles, color = IslandsDark.text)
        Row(Modifier.padding(horizontal = Gateway.contentInset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(question.confirm, styles, actions.answerQuestion)
            OutlinedButton("Cancel", styles, actions.cancelQuestion)
        }
    }
}

@Composable
private fun Notice(text: String, styles: ConnectStyles, color: Color = IslandsDark.textSecondary) =
    BasicText(text, style = styles.body.copy(color = color), modifier = Modifier.padding(horizontal = Gateway.contentInset, vertical = 8.dp))

@Composable
private fun Header(title: String, styles: ConnectStyles, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Gateway.headerHeight).padding(start = Gateway.contentInset, end = Gateway.headerEndInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(title, style = styles.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
}

@Composable
private fun SearchRow(value: String, onChange: (String) -> Unit, styles: ConnectStyles) {
    val icon = LocalIcons.current.painter("expui/inline/searchHistory.svg")
    Row(Modifier.fillMaxWidth().height(Gateway.searchHeight).padding(start = Gateway.searchIconInset), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Image(it, contentDescription = "Search", modifier = Modifier.size(width = 22.dp, height = 16.dp)) }
        Box(Modifier.weight(1f).padding(start = 3.dp, end = Gateway.contentInset)) {
            if (value.isEmpty()) BasicText("Search", style = styles.secondary)
            BasicTextField(value, onChange, singleLine = true, textStyle = styles.body, cursorBrush = SolidColor(IslandsDark.text), modifier = Modifier.fillMaxWidth())
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Gateway.divider))
}

private class HeaderIcon(val path: String, val description: String, val onClick: () -> Unit)

@Composable
private fun HostGroup(host: String, styles: ConnectStyles, icons: List<HeaderIcon>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 17.dp).height(28.dp).padding(start = Gateway.contentInset, end = Gateway.iconColumnEndInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(host, style = styles.host, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        icons.forEach { icon -> IconButton(icon.path, icon.description, icon.onClick) }
    }
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun ProjectRowView(row: ProjectRow, styles: ConnectStyles) {
    val icon = LocalIcons.current.painter("expui/nodes/ideaProject.svg")
    var menuOpen by remember { mutableStateOf(false) }
    val status = if (row.running) "Running" else row.lastActive?.let { "Last active: ${lastActive(it, System.currentTimeMillis())}" }.orEmpty()
    val statusStyle = styles.body.copy(color = if (row.running) Gateway.running else IslandsDark.textSecondary)
    Row(
        Modifier.fillMaxWidth().height(if (styles.twoLineRows) Gateway.twoLineRowHeight else Gateway.projectRowHeight)
            .padding(start = Gateway.contentInset, end = Gateway.iconColumnEndInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        val title = @Composable { modifier: Modifier ->
            BasicText(row.title, style = styles.body.copy(color = IslandsDark.link), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.clickable(onClick = row.onOpen))
        }
        if (styles.twoLineRows) {
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                title(Modifier)
                BasicText(status, style = statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            // The status follows the path; the path gives way when both don't fit, and the icons stay on the right.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                title(Modifier.padding(start = 9.dp).weight(1f, fill = false))
                Spacer(Modifier.width(18.dp))
                BasicText(status, style = statusStyle, maxLines = 1)
            }
        }
        row.onStop?.let { stop -> IconButton("expui/run/stop.svg", "Stop", stop) }
        if (row.onRemove != null) {
            Box {
                IconButton("expui/general/moreVertical.svg", "More") { menuOpen = true }
                if (menuOpen) {
                    ProjectMenu(onDismiss = { menuOpen = false }, items = listOf("Remove from Recent Projects" to row.onRemove), styles)
                }
            }
        }
    }
}

/** Gateway's ⋮ menu: Open With, Remove from Recent Projects, Collect Host and Client Logs. Only Remove applies here. */
@Composable
private fun ProjectMenu(onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>, styles: ConnectStyles) {
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, with(LocalDensity.current) { 28.dp.roundToPx() }), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(Gateway.menuWidth).background(Gateway.menuBackground, Gateway.menuShape)
                .border(1.dp, Gateway.menuBorder, Gateway.menuShape).padding(vertical = 6.dp),
        ) {
            items.forEach { (label, onClick) ->
                Box(
                    Modifier.fillMaxWidth().height(Gateway.menuRowHeight).padding(horizontal = 8.dp)
                        .clickable { onDismiss(); onClick() }.padding(start = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { BasicText(label, style = styles.body) }
            }
        }
    }
}

@Composable
private fun IconButton(path: String, description: String, onClick: () -> Unit) {
    val icon = LocalIcons.current.painter(path) ?: return
    Box(Modifier.size(width = Gateway.iconStep, height = 28.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(icon, contentDescription = description, modifier = Modifier.size(16.dp))
    }
}

/** A bordered text field, for the join link and the file to open. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, styles: ConnectStyles) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = styles.input,
        cursorBrush = SolidColor(IslandsDark.text),
        modifier = Modifier.fillMaxWidth().border(1.dp, Gateway.buttonBorder, Gateway.buttonShape).padding(8.dp),
    )
}

@Composable
private fun OutlinedButton(text: String, styles: ConnectStyles, onClick: () -> Unit) {
    Box(
        Modifier.height(Gateway.buttonHeight).border(1.dp, Gateway.buttonBorder, Gateway.buttonShape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = styles.body) }
}

/** `/data/data/com.termux/files/home/intellij/smoke` → `~/intellij/smoke`: the full path doesn't fit a phone. */
private fun shortPath(path: String) = if (path.startsWith(Termux.HOME)) "~" + path.removePrefix(Termux.HOME) else path

/** Gateway's "17 minutes ago". */
internal fun lastActive(thenMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - thenMillis) / 60_000
    fun ago(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"} ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> ago(minutes, "minute")
        minutes < 24 * 60 -> ago(minutes / 60, "hour")
        else -> ago(minutes / (24 * 60), "day")
    }
}

/** [twoLineRows]: the status goes under the path, for narrow screens. */
private class ConnectStyles(fonts: ClientFonts, val twoLineRows: Boolean) {
    val body = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)
    val secondary = body.copy(color = IslandsDark.textSecondary)
    val sidebarItem = body
    val title = body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val host = body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val input = TextStyle(color = IslandsDark.text, fontFamily = fonts.editor, fontSize = IslandsDark.EDITOR_FONT_SIZE.sp)
}

/** Measured on Gateway 2026.3's welcome window and its ⋮ menu (2x screenshots), in dp = pt. */
private object Gateway {
    val background = Color(0xFF1C1D20)
    val divider = Color(0xFF2B2D30)
    val selection = Color(0xFF2E436E)           // selected sidebar row, full width, square
    val running = Color(0xFF6AAB73)
    val sidebarWidth = 224.dp
    val tabBarHeight = 40.dp                    // portrait: like the main window's tool window bar
    val tabHeight = 32.dp
    val tabShape = RoundedCornerShape(6.dp)
    val tabSelected = Color(0xFF393B40)
    val twoLineRowHeight = 48.dp
    val sidebarTop = 8.dp                       // Gateway has its logo here
    val sidebarRowHeight = 33.dp
    val sidebarIconInset = 17.dp
    val sidebarTextInset = 16.dp
    val headerHeight = 64.dp                    // title centred 37.5 pt down, divider at 63.5
    val headerEndInset = 27.dp                  // New Project: 27.5 pt from the right
    val contentInset = 25.dp                    // title, host and project rows start 25 pt in
    val searchHeight = 44.dp
    val searchIconInset = 7.dp
    val projectRowHeight = 39.dp
    val iconStep = 25.5.dp                      // trailing icons, one every 25.5 pt
    val iconColumnEndInset = 32.dp              // last icon centred 44.5 pt from the right
    val buttonHeight = 27.dp
    val buttonBorder = Color(0xFF4E5157)
    val buttonShape = RoundedCornerShape(4.dp)
    val menuWidth = 237.dp
    val menuRowHeight = 24.dp
    val menuBackground = Color(0xFF2B2D30)
    val menuBorder = Color(0xFF43454A)
    val menuShape = RoundedCornerShape(8.dp)
}
