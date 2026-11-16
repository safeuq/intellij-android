package intellijandroid.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.ClientSession
import intellijandroid.client.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Only common Compose APIs here (no AWT, no desktop-only modules), so this screen can move to Android as is.

/** Phone or tablet, upright or sideways (docs/mobile-layout.md). */
enum class ScreenLayout { PHONE_PORTRAIT, TABLET_PORTRAIT, LANDSCAPE }

/**
 * Android's tablet line: smallest width 600 dp (`sw600dp`). Measured on the window, so a desktop window or a
 * split-screen app picks its layout from its own size.
 */
fun screenLayout(widthDp: Float, heightDp: Float) = when {
    widthDp >= heightDp -> ScreenLayout.LANDSCAPE
    widthDp >= 600f -> ScreenLayout.TABLET_PORTRAIT
    else -> ScreenLayout.PHONE_PORTRAIT
}

/** Phone portrait shows one of these at a time; swipes move between them. */
private enum class Page { LEFT, EDITOR, RIGHT }

/** The main window: main toolbar, tool window bar, the editor island and open tool windows, status line below. */
@Composable
fun ClientScreen(state: SessionState, session: ClientSession) {
    val fonts = LocalClientFonts.current
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); notice = null } }
    val unsupported = { what: String -> notice = "$what isn't supported in this client yet" }

    BoxWithConstraints(Modifier.fillMaxSize().background(IslandsDark.windowBackground)) {
        val layout = screenLayout(maxWidth.value, maxHeight.value)
        val windows = remember { ToolWindowState(left = ToolWindow.PROJECT, right = null) }
        var page by remember { mutableStateOf(Page.EDITOR) }
        // Phone portrait starts on the editor; the other layouts start with Project open, like IntelliJ.
        LaunchedEffect(layout) { if (layout == ScreenLayout.PHONE_PORTRAIT) page = Page.EDITOR }
        // A tool window covering the editor takes the keyboard away with it.
        val focusManager = LocalFocusManager.current
        LaunchedEffect(page) { if (page != Page.EDITOR) focusManager.clearFocus() }

        fun barClick(window: ToolWindow) {
            if (!window.supported) return unsupported(window.title)
            if (layout != ScreenLayout.PHONE_PORTRAIT) return windows.toggle(window)
            val target = if (window.side == Side.LEFT) Page.LEFT else Page.RIGHT
            val current = if (window.side == Side.LEFT) windows.lastLeft else windows.lastRight
            page = if (page == target && current == window) Page.EDITOR else target
            windows.open(window)
        }
        val open = when (layout) {
            ScreenLayout.PHONE_PORTRAIT -> setOfNotNull(
                windows.lastLeft.takeIf { page == Page.LEFT },
                windows.lastRight.takeIf { page == Page.RIGHT },
            )
            else -> setOfNotNull(windows.left, windows.right)
        }

        // The backend builds the Structure tree only while the tool window shows.
        val structureShown = ToolWindow.STRUCTURE in open
        LaunchedEffect(structureShown) { session.setStructureVisible(structureShown) }

        @Composable
        fun Window(window: ToolWindow, onBack: (() -> Unit)?, modifier: Modifier) =
            ToolWindowPanel(window, onBack, modifier.island()) { ToolWindowContent(window, state, session, onNavigated = { onBack?.invoke() }) }

        Column(Modifier.fillMaxSize()) {
            MainToolbar(state.projectName, unsupported)
            when (layout) {
                ScreenLayout.PHONE_PORTRAIT -> {
                    SwipePager(
                        page = page,
                        onPage = { page = it },
                        left = { Window(windows.lastLeft, onBack = { page = Page.EDITOR }, Modifier.fillMaxSize()) },
                        editor = { EditorIsland(state, session, Modifier.fillMaxSize()) },
                        right = { Window(windows.lastRight, onBack = { page = Page.EDITOR }, Modifier.fillMaxSize()) },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = IslandsDark.islandGap),
                    )
                    ToolWindowBar(open, ::barClick, vertical = false, Modifier.fillMaxWidth())
                }
                ScreenLayout.TABLET_PORTRAIT -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = IslandsDark.islandGap)) {
                        EditorIsland(state, session, Modifier.weight(1f).fillMaxWidth())
                        // Open tool windows share one area under the editor, side by side.
                        if (open.isNotEmpty()) {
                            Spacer(Modifier.height(IslandsDark.islandGap))
                            Row(Modifier.weight(0.8f).fillMaxWidth()) {
                                listOfNotNull(windows.left, windows.right).forEachIndexed { index, window ->
                                    if (index > 0) Spacer(Modifier.width(IslandsDark.islandGap))
                                    Window(window, onBack = null, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    }
                    ToolWindowBar(open, ::barClick, vertical = false, Modifier.fillMaxWidth())
                }
                ScreenLayout.LANDSCAPE -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    ToolWindowBar(open, ::barClick, vertical = true, Modifier.fillMaxHeight())
                    // Open tool windows share one column left of the editor, one above the other.
                    if (open.isNotEmpty()) {
                        Column(Modifier.width(300.dp).fillMaxHeight()) {
                            listOfNotNull(windows.left, windows.right).forEachIndexed { index, window ->
                                if (index > 0) Spacer(Modifier.height(IslandsDark.islandGap))
                                Window(window, onBack = null, Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                        Spacer(Modifier.width(IslandsDark.islandGap))
                    }
                    EditorIsland(state, session, Modifier.weight(1f).fillMaxHeight().padding(end = IslandsDark.islandGap))
                }
            }
            val status = notice ?: state.failure ?: state.log.lastOrNull().orEmpty()
            BasicText(
                status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (notice == null && state.failure != null) Color(0xFFFA6675) else IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

private fun Modifier.island() = clip(RoundedCornerShape(IslandsDark.islandRadius)).background(IslandsDark.islandBackground)

@Composable
private fun ToolWindowContent(window: ToolWindow, state: SessionState, session: ClientSession, onNavigated: () -> Unit) = when (window) {
    ToolWindow.PROJECT -> ProjectTreeView(
        tree = state.tree,
        onToggle = { session.toggle(it.id) },
        onSelect = { session.select(it.id) },
        onOpen = { node -> node.path?.takeIf { !node.isDirectory }?.let(session::open) },
        modifier = Modifier.fillMaxSize(),
    )
    ToolWindow.STRUCTURE -> StructureView(state, session, onNavigated, Modifier.fillMaxSize())
    else -> Unit
}

/**
 * Phone portrait: left tool window, editor and right tool window side by side, one screen wide each; the content
 * follows the finger (dragging right reveals the left window). From the editor a swipe opens either side; from a
 * tool window only the swipe back to the editor works. Horizontal drags are taken before the editor sees them, so
 * selecting text by dragging sideways doesn't work in this layout.
 */
@Composable
private fun SwipePager(
    page: Page,
    onPage: (Page) -> Unit,
    left: @Composable () -> Unit,
    editor: @Composable () -> Unit,
    right: @Composable () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        fun offsetOf(target: Page) = when (target) { Page.LEFT -> width; Page.EDITOR -> 0f; Page.RIGHT -> -width }
        val offset = remember { Animatable(offsetOf(page)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(page, width) { offset.animateTo(offsetOf(page)) }

        Box(
            Modifier.fillMaxSize().pointerInput(page, width) {
                val range = when (page) { Page.LEFT -> 0f..width; Page.EDITOR -> -width..width; Page.RIGHT -> -width..0f }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val velocity = VelocityTracker()
                    var travelled = Offset.Zero
                    var swiping = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (!swiping) {
                            travelled += delta
                            if (travelled.getDistance() < viewConfiguration.touchSlop) continue
                            // Mostly sideways: a swipe. Anything else belongs to the content (scrolling, taps).
                            if (abs(travelled.x) < 2 * abs(travelled.y)) return@awaitEachGesture
                            swiping = true
                        }
                        change.consume()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        scope.launch { offset.snapTo((offset.value + delta.x).coerceIn(range)) }
                    }
                    if (!swiping) return@awaitEachGesture
                    // Past a third of the screen, or a quick flick, moves one page; otherwise it springs back.
                    val flick = velocity.calculateVelocity().x
                    val moved = offset.value - offsetOf(page)
                    val target = when {
                        moved > width / 3 || flick > 1000f -> if (page == Page.RIGHT) Page.EDITOR else Page.LEFT
                        moved < -width / 3 || flick < -1000f -> if (page == Page.LEFT) Page.EDITOR else Page.RIGHT
                        else -> page
                    }.takeIf { offsetOf(it) in range } ?: page
                    if (target == page) scope.launch { offset.animateTo(offsetOf(page)) } else onPage(target)
                }
            },
        ) {
            listOf(Page.LEFT to left, Page.EDITOR to editor, Page.RIGHT to right).forEach { (target, content) ->
                val base = -offsetOf(target)
                Box(Modifier.fillMaxSize().offset { IntOffset((base + offset.value).roundToInt(), 0) }) { content() }
            }
        }
    }
}

@Composable
private fun EditorIsland(state: SessionState, session: ClientSession, modifier: Modifier) {
    val fonts = LocalClientFonts.current
    Column(modifier.island()) {
        val selected = state.editors.firstOrNull { it.path == state.selectedEditor }
        EditorTabs(state, session)
        val scheme = state.scheme
        if (selected != null && scheme != null) {
            val actions = remember(session) {
                EditorActions(session::type, session::key, { offset, anchor -> session.moveCaret(offset, anchor) }, session::undo, session::redo, session::complete, session::lookupKey, session::chooseLookupItem)
            }
            EditorView(selected, scheme, actions, Modifier.fillMaxSize(), state.lookup?.takeIf { it.path == selected.path })
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (state.tree.nodes.isEmpty()) "Connecting…" else "Double-tap a file in the Project tool window to open it",
                    style = TextStyle(color = IslandsDark.textSecondary, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** Editor tabs: file icon and name; the selected one is a rounded "pill" like Islands Dark (measured on the real client). */
@Composable
private fun EditorTabs(state: SessionState, session: ClientSession) {
    if (state.editors.isEmpty()) return
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    Row(Modifier.fillMaxWidth().height(IslandsDark.tabRowHeight).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (editor in state.editors) {
            val selected = editor.path == state.selectedEditor
            val iconPath = state.tree.nodes.values.firstOrNull { it.path == editor.path }?.iconPaths?.firstOrNull() ?: "expui/fileTypes/text.svg"
            val pill = RoundedCornerShape(IslandsDark.tabRadius)
            Box(
                Modifier.height(IslandsDark.tabHeight).clip(pill)
                    .then(if (selected) Modifier.background(IslandsDark.tabSelectedBackground).border(1.dp, IslandsDark.tabSelectedBorder, pill) else Modifier)
                    .clickable { session.selectEditor(editor.path) }
                    .padding(horizontal = 8.dp),
            ) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    icons.painter(iconPath)?.let { Image(it, null, Modifier.size(16.dp)) }
                    Spacer(Modifier.width(6.dp))
                    BasicText(editor.name, style = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp))
                }
            }
        }
    }
}
