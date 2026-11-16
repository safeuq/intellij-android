package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ProjectTree
import intellijandroid.client.session.TreeNode

/** The Project tool window: header plus the tree, laid out with IntelliJ's New UI tree metrics. */
@Composable
fun ProjectToolWindow(
    tree: ProjectTree,
    onToggle: (TreeNode) -> Unit,
    onSelect: (TreeNode) -> Unit,
    onOpen: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fonts = LocalClientFonts.current
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(IslandsDark.headerHeight).padding(start = IslandsDark.headerLeftInset), contentAlignment = Alignment.CenterStart) {
            BasicText("Project", style = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp))
        }
        ProjectTreeView(tree, onToggle, onSelect, onOpen, Modifier.fillMaxSize())
    }
}

@Composable
fun ProjectTreeView(
    tree: ProjectTree,
    onToggle: (TreeNode) -> Unit,
    onSelect: (TreeNode) -> Unit,
    onOpen: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(tree) { tree.visibleRows() }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Arrow keys like IntelliJ: up/down move, right expands or enters, left collapses or goes to the parent, Enter opens.
    fun onKey(key: Key): Boolean {
        val index = rows.indexOfFirst { it.first.id == tree.selected }
        val current = rows.getOrNull(index)?.first
        when (key) {
            Key.DirectionDown -> rows.getOrNull(if (index < 0) 0 else index + 1)?.let { onSelect(it.first) }
            Key.DirectionUp -> rows.getOrNull(index - 1)?.let { onSelect(it.first) }
            Key.DirectionRight -> current?.let { node ->
                if (node.hasChildren && node.id !in tree.expanded) onToggle(node) else rows.getOrNull(index + 1)?.takeIf { it.first.parentId == node.id }?.let { onSelect(it.first) }
            }
            Key.DirectionLeft -> current?.let { node ->
                if (node.id in tree.expanded) onToggle(node) else node.parentId?.let { parent -> tree.nodes[parent]?.let(onSelect) }
            }
            Key.Enter -> current?.let { node -> if (node.hasChildren) onToggle(node) else onOpen(node) }
            else -> return false
        }
        return true
    }

    LaunchedEffect(tree.selected) {
        val index = rows.indexOfFirst { it.first.id == tree.selected }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (index >= 0 && visible.isNotEmpty() && (index < visible.first().index || index > visible.last().index)) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = IslandsDark.treeTopInset, bottom = IslandsDark.treeTopInset),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event.key) },
    ) {
        items(rows, key = { it.first.id }) { (node, depth) ->
            TreeRow(
                node = node,
                depth = depth,
                expanded = node.id in tree.expanded,
                selected = node.id == tree.selected,
                treeFocused = focused,
                onPress = { focusRequester.requestFocus(); onSelect(node) },
                onDoubleClick = { if (node.hasChildren) onToggle(node) else onOpen(node) },
                onChevron = { onToggle(node) },
            )
        }
    }
}

@Composable
private fun TreeRow(
    node: TreeNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    treeFocused: Boolean,
    onPress: () -> Unit,
    onDoubleClick: () -> Unit,
    onChevron: () -> Unit,
) {
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = when {
        selected -> if (treeFocused) IslandsDark.treeSelectionFocused else IslandsDark.treeSelectionUnfocused
        hovered -> IslandsDark.treeHover
        else -> null
    }
    val chevronX = IslandsDark.treeSideInset + IslandsDark.treeIndent * (depth - 1)
    val rendererX = IslandsDark.treeSideInset + IslandsDark.treeIndent * depth
    val iconX = rendererX + IslandsDark.treeIconTextGap
    val textX = iconX + 16.dp + IslandsDark.treeIconTextGap

    Box(
        Modifier
            .fillMaxWidth()
            .height(IslandsDark.treeRowHeight)
            .hoverable(interaction)
            .pointerInput(node.id) { detectTapGestures(onPress = { onPress() }, onDoubleTap = { onDoubleClick() }) },
    ) {
        // Selection and hover: a rounded rectangle inset 12 px from both sides, not indented with the node.
        if (highlight != null) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = IslandsDark.treeSideInset)
                    .background(highlight, RoundedCornerShape(IslandsDark.treeSelectionRadius)),
            )
        }
        if (node.hasChildren) {
            val chevron = icons.painter(if (expanded) "expui/general/chevronDown.svg" else "expui/general/chevronRight.svg")
            if (chevron != null) {
                Image(
                    chevron, contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.offset(x = chevronX).size(16.dp).align(Alignment.CenterStart)
                        .pointerInput(node.id) { detectTapGestures(onPress = { onChevron() }) },
                )
            }
        }
        node.iconPaths.firstOrNull()?.let(icons::painter)?.let { icon ->
            Image(icon, contentDescription = null, modifier = Modifier.offset(x = iconX).size(16.dp).align(Alignment.CenterStart))
        }
        val label = remember(node.fragments) {
            buildAnnotatedString {
                for (fragment in node.fragments) {
                    withStyle(
                        SpanStyle(
                            color = fragment.foreground?.let { androidx.compose.ui.graphics.Color(it) } ?: IslandsDark.text,
                            // Inter has no Bold file; IntelliJ draws "bold" UI text with Inter SemiBold.
                            fontWeight = if (fragment.style and 1 != 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontStyle = if (fragment.style and 2 != 0) FontStyle.Italic else FontStyle.Normal,
                        ),
                    ) { append(fragment.text) }
                }
            }
        }
        BasicText(
            label,
            style = TextStyle(fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp, color = IslandsDark.text),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.offset(x = textX).align(Alignment.CenterStart).padding(end = IslandsDark.treeSideInset),
        )
    }
}
