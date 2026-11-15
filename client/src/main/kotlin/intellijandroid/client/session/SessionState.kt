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

/** A gutter icon (run marker, override marker, …) on the line of [offset]; [iconPaths] are paths in the icons jar. */
data class GutterIcon(val offset: Int, val iconPaths: List<String>)

data class EditorFile(
    val path: String,
    val text: String,
    /** Sorted by layer, lowest first, so later ones paint over earlier ones. */
    val highlights: List<Highlight>,
    val indentGuides: List<IndentGuide>,
    val gutterIcons: List<GutterIcon> = emptyList(),
    val caret: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    /** False until the backend has sent the document's version and the editor's caret, or while catching up with it. */
    val editable: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** Editor keys that become backend editor actions. */
enum class EditorKey { ENTER, BACKSPACE, DELETE }

/** Keys the completion popup takes while it is open. */
enum class LookupKey { UP, DOWN, ACCEPT, CANCEL }

data class SessionState(
    val log: List<String> = emptyList(),
    val failure: String? = null,
    val projectName: String? = null,
    val tree: ProjectTree = ProjectTree(),
    val editors: List<EditorFile> = emptyList(),
    val selectedEditor: String? = null,
    val scheme: EditorScheme? = null,
    /** The completion popup, if one is open. */
    val lookup: Lookup? = null,
)

/**
 * IntelliJ's completion popup ("lookup") for the editor of [path]. [prefix] is the part of the identifier typed before
 * the caret, which the items replace; the popup sits under its first character.
 */
data class Lookup(
    val path: String,
    val items: List<LookupItem>,
    val selected: Int,
    val prefix: String,
    /** False for an autopopup until the user moves the selection: the selected row is then grey, not blue. */
    val focused: Boolean,
    /** The tip in the popup's footer ("Press ^. to choose the selected (or first) suggestion…"). */
    val advertisement: String?,
)

/** One row: icon, item text (the typed prefix highlighted), tail in grey ("(String s)"), type text on the right. */
data class LookupItem(
    val id: String,
    val lookupString: String,
    val itemText: String,
    val tail: String,
    val typeText: String,
    val iconPath: String?,
    val bold: Boolean,
    val strikeout: Boolean,
    /** A group shown after the ungrouped items under its own header, e.g. "Commands"; null for ordinary items. */
    val group: String? = null,
    val groupOrder: Int = 0,
)
