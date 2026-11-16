package intellijandroid.client.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import intellijandroid.client.session.ProjectTree
import intellijandroid.client.session.TextFragment
import intellijandroid.client.session.TreeNode
import intellijandroid.client.ui.IslandsDark
import intellijandroid.client.ui.LocalClientFonts
import intellijandroid.client.ui.LocalIcons
import intellijandroid.client.ui.ProjectTreeView

/**
 * IntelliJ's folder chooser ("Open File or Project", `FileChooserDialogImpl`), for folders in Termux. Sizes and colors
 * measured on the real dialog at 2x (docs/intellij-look.md). Left out: Desktop, New Folder, Delete, the path history
 * drop-down and the drag-and-drop hint.
 */
@Composable
fun FolderChooser(
    title: String,
    initialPath: String,
    listFolders: (path: String, onResult: (Result<List<Termux.Folder>>) -> Unit) -> Unit,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    val text = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)

    // Folder contents by path, loaded through Termux when a folder is first expanded.
    val children = remember { mutableStateMapOf<String, List<Termux.Folder>>() }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(initialPath.trimEnd('/')) }
    var showPath by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ids = remember { mutableMapOf<String, Int>() }
    fun idOf(folder: String) = ids.getOrPut(folder) { ids.size + 1 }

    fun load(folder: String) = listFolders(folder) { result ->
        result.onSuccess { children[folder] = it }.onFailure { error = it.message }
    }

    /** Expands the folders from [ROOT] down to [target] and selects it, like the real chooser does for its path. */
    fun reveal(target: String) {
        val clean = target.trimEnd('/')
        if (!clean.startsWith(ROOT)) return
        val chain = generateSequence(clean) { folder -> folder.substringBeforeLast('/').takeIf { it.length >= ROOT.length && it != folder } }
            .toList().reversed()
        chain.forEach { folder -> if (folder !in children) load(folder) }
        expanded = expanded + chain.dropLast(1)
        selected = clean
        path = clean
    }

    LaunchedEffect(Unit) { if (initialPath.startsWith(ROOT)) reveal(initialPath) else load(ROOT) }

    val tree = remember(children.toMap(), expanded, selected, showHidden) {
        val nodes = mutableMapOf<Int, TreeNode>()
        fun add(folder: String, parent: String?, name: String, isProject: Boolean, sortKey: Int) {
            val loaded = children[folder]?.filter { showHidden || !it.name.startsWith(".") }
            nodes[idOf(folder)] = TreeNode(
                id = idOf(folder),
                parentId = parent?.let(::idOf),
                fragments = listOf(TextFragment(name, 0, null)),
                iconPaths = listOf(if (isProject) "expui/nodes/ideaProject.svg" else "expui/nodes/folder.svg"),
                // Like the real chooser: every folder has a chevron until it is known to be empty.
                hasChildren = loaded == null || loaded.isNotEmpty(),
                isDirectory = true,
                path = folder,
                sortKey = sortKey,
            )
            if (folder in expanded) loaded?.forEachIndexed { index, child -> add("$folder/${child.name}", folder, child.name, child.isProject, index) }
        }
        add(ROOT, null, ROOT.substringAfterLast('/'), isProject = false, 0)
        ProjectTree(nodes, expanded.map(::idOf).toSet(), selected?.let(::idOf))
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(Chooser.width).heightIn(max = Chooser.maxHeight).fillMaxHeight()
                .background(IslandsDark.islandBackground, Chooser.shape).border(1.dp, Chooser.outline, Chooser.shape),
        ) {
            Box(Modifier.fillMaxWidth().height(Chooser.titleHeight).padding(start = Chooser.sideInset), contentAlignment = Alignment.CenterStart) {
                BasicText(title, style = text.copy(fontWeight = FontWeight.SemiBold))
            }
            Row(Modifier.fillMaxWidth().height(Chooser.toolbarHeight).padding(start = Chooser.toolbarInset, end = Chooser.sideInset), verticalAlignment = Alignment.CenterVertically) {
                @Composable
                fun ToolbarIcon(iconPath: String, description: String, active: Boolean = false, onClick: () -> Unit) {
                    val painter = icons.painter(iconPath) ?: return
                    Box(
                        Modifier.size(Chooser.toolbarButton)
                            .background(if (active) Chooser.toggledButton else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable(onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) { Image(painter, contentDescription = description, modifier = Modifier.size(16.dp)) }
                }
                ToolbarIcon("expui/nodes/homeFolder.svg", "Home") { reveal(Termux.HOME) }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(Chooser.separator))
                ToolbarIcon("expui/general/refresh.svg", "Refresh") {
                    error = null
                    val open = children.keys.toList()
                    children.clear()
                    open.forEach(::load)
                }
                ToolbarIcon("expui/actions/toggleVisibility.svg", "Show hidden files", active = showHidden) { showHidden = !showHidden }
                Spacer(Modifier.weight(1f))
                BasicText(
                    if (showPath) "Hide path" else "Show path",
                    style = text.copy(color = IslandsDark.link),
                    modifier = Modifier.clickable { showPath = !showPath },
                )
            }
            if (showPath) {
                var fieldFocused by remember { mutableStateOf(false) }
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = text,
                    cursorBrush = SolidColor(IslandsDark.text),
                    modifier = Modifier.padding(horizontal = Chooser.sideInset).fillMaxWidth().height(Chooser.fieldHeight)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) Chooser.accent else Chooser.fieldBorder, Chooser.fieldShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Box(
                Modifier.padding(horizontal = Chooser.treeInset).padding(top = 6.dp).weight(1f).fillMaxWidth()
                    .border(1.dp, Chooser.treeBorder),
            ) {
                ProjectTreeView(
                    tree = tree,
                    onToggle = { node ->
                        val folder = node.path ?: return@ProjectTreeView
                        if (folder in expanded) {
                            expanded = expanded - folder
                        } else {
                            if (folder !in children) load(folder)
                            expanded = expanded + folder
                        }
                    },
                    onSelect = { node -> node.path?.let { selected = it; path = it } },
                    onOpen = { node -> node.path?.let(onChoose) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            error?.let { message -> BasicText(message, style = text.copy(color = IslandsDark.textSecondary), modifier = Modifier.padding(horizontal = Chooser.sideInset, vertical = 4.dp)) }
            Row(
                Modifier.fillMaxWidth().padding(start = Chooser.sideInset, end = Chooser.sideInset, top = 12.dp, bottom = Chooser.bottomInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                DialogButton("OK", text.copy(color = Color.White, fontWeight = FontWeight.SemiBold), Modifier.background(Chooser.accent, Chooser.buttonShape)) {
                    onChoose(path.trim().trimEnd('/'))
                }
                DialogButton("Cancel", text, Modifier.border(1.dp, Chooser.buttonBorder, Chooser.buttonShape), onCancel)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, style: TextStyle, decoration: Modifier, onClick: () -> Unit) {
    Box(
        decoration.width(Chooser.buttonWidth).height(Chooser.buttonHeight).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = style) }
}

/** Termux's files folder, the top of the tree: it holds `home` and `usr`. Folders above it aren't readable. */
private const val ROOT = "/data/data/com.termux/files"

/** Measured on IntelliJ's "Open File or Project" dialog (Islands Dark, macOS, 2x screenshot), in dp = pt. */
private object Chooser {
    val width = 475.dp
    val maxHeight = 482.dp
    val shape = RoundedCornerShape(10.dp)
    val outline = Color(0xFF393B40)
    val titleHeight = 32.dp
    val toolbarHeight = 28.dp
    val toolbarInset = 17.dp                    // first icon's 16 px box starts 21 pt in; buttons have 2 pt padding
    val toolbarButton = 22.dp
    val toggledButton = Color(0xFF393B40)
    val separator = Color(0xFF393B40)
    val sideInset = 14.dp                       // path field: 14.5 pt from the dialog's side
    val fieldHeight = 26.dp                     // 24 pt plus the focus ring
    val fieldShape = RoundedCornerShape(4.dp)
    val fieldBorder = Color(0xFF4E5157)
    val accent = Color(0xFF4670DA)              // focused field ring and the OK button
    val treeInset = 12.dp                       // tree panel: 12.5 pt from the dialog's side
    val treeBorder = Color(0xFF2B2D30)
    val buttonWidth = 72.dp
    val buttonHeight = 24.dp
    val buttonShape = RoundedCornerShape(4.dp)
    val buttonBorder = Color(0xFF414349)
    val bottomInset = 11.dp
}
