package intellijandroid.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.ProjectTree
import intellijandroid.client.session.SessionState
import intellijandroid.client.session.StructureTree
import intellijandroid.client.session.TextFragment
import intellijandroid.client.session.TreeNode

/**
 * The Structure tool window: the backend's tree for the selected file, drawn with the Project tree's rows. Everything
 * starts expanded, as in IntelliJ. A tap selects and moves the caret to the element (IntelliJ's "Navigate with Single
 * Click" is on by default); a double tap also calls [onNavigated], which on a phone held upright slides back to the
 * editor. Left out: caret following, sorting and filters.
 */
@Composable
fun StructureView(state: SessionState, session: ClientSession, onNavigated: () -> Unit, modifier: Modifier) {
    val structure = state.structure
    // Node ids change on every rebuild, so remember collapsed nodes by name path instead.
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    if (structure.nodes.isEmpty() || structure.path != state.selectedEditor) {
        val fonts = LocalClientFonts.current
        Box(modifier.padding(12.dp)) {
            BasicText(
                if (state.selectedEditor == null) "No file is open" else "Loading…",
                style = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp),
            )
        }
        return
    }
    val keys = remember(structure) { nameKeys(structure) }
    val tree = remember(structure, collapsed, selected) { toProjectTree(structure, keys, collapsed, selected) }
    fun navigate(node: TreeNode) {
        selected = keys[node.id]
        val offset = structure.nodes[node.sortKey].offset
        if (offset >= 0) session.moveCaret(offset)
    }
    ProjectTreeView(
        tree = tree,
        onToggle = { node -> keys[node.id]?.let { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key } },
        onSelect = ::navigate,
        onOpen = { node -> navigate(node); onNavigated() },
        modifier = modifier,
    )
}

/** `Main.java/Main/main(String[]): void`: a node's names from the root down, stable across rebuilds. */
private fun nameKeys(structure: StructureTree): Map<Int, String> {
    val keys = mutableMapOf<Int, String>()
    fun visit(index: Int, prefix: String) {
        val node = structure.nodes[index]
        val key = "$prefix/${node.name}"
        keys[node.id] = key
        node.children.forEach { visit(it, key) }
    }
    visit(0, "")
    return keys
}

/** The pre-order list with child indexes, as the Project tree's id/parent map; `sortKey` keeps the index. */
private fun toProjectTree(structure: StructureTree, keys: Map<Int, String>, collapsed: Set<String>, selected: String?): ProjectTree {
    val parents = mutableMapOf<Int, Int>()
    structure.nodes.forEach { node -> node.children.forEach { child -> parents[structure.nodes[child].id] = node.id } }
    val nodes = structure.nodes.mapIndexed { index, node ->
        node.id to TreeNode(
            id = node.id,
            parentId = parents[node.id],
            fragments = listOf(TextFragment(node.name, 0, null)),
            iconPaths = node.iconPaths,
            hasChildren = node.children.isNotEmpty(),
            isDirectory = node.children.isNotEmpty(),
            path = null,
            sortKey = index,
        )
    }.toMap()
    val expanded = nodes.keys.filter { keys[it] !in collapsed }.toSet()
    return ProjectTree(nodes, expanded, nodes.keys.firstOrNull { keys[it] == selected })
}
