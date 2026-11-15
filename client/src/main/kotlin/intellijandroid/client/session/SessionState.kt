package intellijandroid.client.session

/** One piece of a tree node's label, e.g. the project name (bold) and its path (grey). */
data class TextFragment(
    val text: String,
    /** IntelliJ `SimpleTextAttributes` style bits: 1 bold, 2 italic, 4 strikeout, 8 waved, 16 underline. */
    val style: Int,
    /** ARGB, or null for the tree's default foreground. */
    val foreground: Long?,
)

/** One row of the Project view, as `RdProjectViewItem` describes it. */
data class TreeNode(
    val id: Int,
    val parentId: Int?,
    val fragments: List<TextFragment>,
    /** Icon paths inside the icons jar, e.g. `expui/nodes/module.svg`; first is the main icon. */
    val iconPaths: List<String>,
    val hasChildren: Boolean,
    val isDirectory: Boolean,
    /** File path on the backend; null for virtual nodes such as "External Libraries". */
    val path: String?,
    val sortKey: Int,
)

data class ProjectTree(
    val nodes: Map<Int, TreeNode> = emptyMap(),
    val expanded: Set<Int> = emptySet(),
    val selected: Int? = null,
) {
    /** Children in display order. */
    fun children(parentId: Int?): List<TreeNode> =
        nodes.values.filter { it.parentId == parentId }.sortedWith(compareBy({ it.sortKey }, { it.id }))

    /** The visible rows, depth-first, with their depth (1 for top-level nodes, like IntelliJ's root handles). */
    fun visibleRows(): List<Pair<TreeNode, Int>> {
        val rows = mutableListOf<Pair<TreeNode, Int>>()
        fun add(parentId: Int?, depth: Int) {
            for (node in children(parentId)) {
                rows += node to depth
                if (node.id in expanded) add(node.id, depth + 1)
            }
        }
        add(null, 1)
        return rows
    }
}

/** A highlighted range. [style] is already resolved against the current scheme. */
data class Highlight(val id: Long, val start: Int, val end: Int, val layer: Int, val style: TextStyle)

/** An indent guide: a vertical line at [indentLevel] (columns) from the line of [start] to the line of [end]. */
data class IndentGuide(val start: Int, val end: Int, val indentLevel: Int)

data class EditorFile(
    val path: String,
    val text: String,
    /** Sorted by layer, lowest first, so later ones paint over earlier ones. */
    val highlights: List<Highlight>,
    val indentGuides: List<IndentGuide>,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class SessionState(
    val log: List<String> = emptyList(),
    val failure: String? = null,
    val projectName: String? = null,
    val tree: ProjectTree = ProjectTree(),
    val editors: List<EditorFile> = emptyList(),
    val selectedEditor: String? = null,
    val scheme: EditorScheme? = null,
)
