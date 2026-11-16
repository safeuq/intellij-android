package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Side { LEFT, RIGHT }

/**
 * The tool windows on IntelliJ's stripes (new UI defaults for a Java project). Only Project and Structure work; the
 * others are shown so the bars look like IntelliJ's, and say "not supported yet" when tapped. Structure sits on the
 * right here (IntelliJ puts it on the left by default), so one window can be open on each side.
 */
enum class ToolWindow(val title: String, val icon: String, val side: Side, val supported: Boolean = false, val bottomGroup: Boolean = false) {
    PROJECT("Project", "expui/toolwindows/project.svg", Side.LEFT, supported = true),
    COMMIT("Commit", "expui/toolwindows/commit.svg", Side.LEFT),
    BOOKMARKS("Bookmarks", "expui/toolwindows/bookmarks.svg", Side.LEFT),
    SERVICES("Services", "expui/toolwindows/services.svg", Side.LEFT, bottomGroup = true),
    RUN("Run", "expui/toolwindows/run.svg", Side.LEFT, bottomGroup = true),
    DEBUG("Debug", "expui/toolwindows/debug.svg", Side.LEFT, bottomGroup = true),
    PROBLEMS("Problems", "expui/toolwindows/problems.svg", Side.LEFT, bottomGroup = true),
    GIT("Git", "expui/toolwindows/vcs.svg", Side.LEFT, bottomGroup = true),
    STRUCTURE("Structure", "expui/toolwindows/structure.svg", Side.RIGHT, supported = true),
    NOTIFICATIONS("Notifications", "expui/toolwindows/notifications.svg", Side.RIGHT),
}

/** Which tool window is open on each side; at most one per side, as the portrait spec asks. */
class ToolWindowState(left: ToolWindow?, right: ToolWindow?) {
    var left by mutableStateOf(left)
    var right by mutableStateOf(right)
    /** Phone portrait: the window a swipe opens on each side (the last one used there). */
    var lastLeft by mutableStateOf(left ?: ToolWindow.PROJECT)
    var lastRight by mutableStateOf(right ?: ToolWindow.STRUCTURE)

    fun open(window: ToolWindow) = when (window.side) {
        Side.LEFT -> { left = window; lastLeft = window }
        Side.RIGHT -> { right = window; lastRight = window }
    }

    /** Tapping an open window's button closes it, like IntelliJ's stripes. */
    fun toggle(window: ToolWindow) = if (window == left || window == right) close(window.side) else open(window)

    fun close(side: Side) = when (side) {
        Side.LEFT -> left = null
        Side.RIGHT -> right = null
    }
}

/** IntelliJ's stripe buttons in one bar: the left stripe's icons, a separator, then the right stripe's. */
@Composable
fun ToolWindowBar(open: Set<ToolWindow>, onClick: (ToolWindow) -> Unit, vertical: Boolean, modifier: Modifier = Modifier) {
    val groups = listOf(
        ToolWindow.entries.filter { it.side == Side.LEFT && !it.bottomGroup },
        ToolWindow.entries.filter { it.side == Side.LEFT && it.bottomGroup },
        ToolWindow.entries.filter { it.side == Side.RIGHT },
    )
    @Composable
    fun Buttons() = groups.forEachIndexed { index, group ->
        if (index > 0) {
            Box(
                (if (vertical) Modifier.padding(vertical = 6.dp).size(Bars.separatorLength, 1.dp) else Modifier.padding(horizontal = 6.dp).size(1.dp, Bars.separatorLength))
                    .background(Bars.separator),
            )
        }
        group.forEach { window -> StripeButton(window, window in open) { onClick(window) } }
    }
    if (vertical) {
        Column(modifier.width(Bars.thickness).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { Buttons() }
    } else {
        Row(modifier.height(Bars.thickness).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { Buttons() }
    }
}

@Composable
private fun StripeButton(window: ToolWindow, selected: Boolean, onClick: () -> Unit) {
    val icon = LocalIcons.current.painter(window.icon)
    Box(Modifier.padding(2.dp).size(Bars.button), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().background(if (selected) Bars.selected else Color.Transparent, Bars.buttonShape).clickable(onClick = onClick),
        )
        // 16 like the main toolbar's icons; IntelliJ's stripes use the 20 px set, which looked heavier next to them.
        icon?.let { Image(it, contentDescription = window.title, modifier = Modifier.size(16.dp)) }
    }
}

/**
 * IntelliJ's main toolbar, reduced: the project widget on the left; the run widget, Build, Run, Debug and More on the
 * right. Only the project name is live; the rest reports "not supported yet" through [onUnsupported].
 */
@Composable
fun MainToolbar(projectName: String?, onUnsupported: (String) -> Unit, modifier: Modifier = Modifier) {
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    val text = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp)
    Row(modifier.fillMaxWidth().height(Bars.toolbarHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val name = projectName ?: "…"
        Row(Modifier.weight(1f, fill = false).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The project widget's coloured square with the name's first letters.
            Box(Modifier.size(20.dp).background(Bars.projectBadge, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                BasicText(initials(name), style = text.copy(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
            }
            BasicText(name, style = text.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
            icons.painter("expui/general/chevronDown.svg")?.let { Image(it, null, Modifier.padding(start = 2.dp).size(16.dp)) }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clickable { onUnsupported("Run configurations") }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Current File", style = text, maxLines = 1)
            icons.painter("expui/general/chevronDown.svg")?.let { Image(it, null, Modifier.padding(start = 2.dp).size(16.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("expui/build/build.svg" to "Build", "expui/run/run.svg" to "Run", "expui/run/debug.svg" to "Debug", "expui/general/moreVertical.svg" to "More actions")
                .forEach { (path, label) ->
                    Box(Modifier.size(Bars.toolbarButton).clickable { onUnsupported(label) }, contentAlignment = Alignment.Center) {
                        icons.painter(path)?.let { Image(it, contentDescription = label, modifier = Modifier.size(16.dp)) }
                    }
                }
        }
    }
}

/** A tool window island: header with the title (and a back chevron on a phone held upright), then [content]. */
@Composable
fun ToolWindowPanel(window: ToolWindow, onBack: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fonts = LocalClientFonts.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(IslandsDark.headerHeight).padding(start = if (onBack != null) 4.dp else IslandsDark.headerLeftInset), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { back ->
                LocalIcons.current.painter("expui/general/chevronLeft.svg")?.let { icon ->
                    Box(Modifier.size(32.dp).clickable(onClick = back), contentAlignment = Alignment.Center) { Image(icon, contentDescription = "Back to the editor", modifier = Modifier.size(16.dp)) }
                }
            }
            BasicText(window.title, style = TextStyle(color = IslandsDark.text, fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** `smoke` → `SM`, `xtool-web` → `XW`. */
internal fun initials(name: String): String {
    val words = name.split('-', '_', ' ', '.').filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}"
        else -> name.take(2)
    }.uppercase()
}

/** Sizes from IntelliJ's new UI (stripe 40 wide; main toolbar 40 high). Icons are 16 everywhere. */
private object Bars {
    val thickness = 40.dp
    val button = 32.dp
    val buttonShape = RoundedCornerShape(6.dp)
    val selected = Color(0xFF393B40)
    val separator = Color(0xFF393B40)
    val separatorLength = 20.dp
    val toolbarHeight = 40.dp
    val toolbarButton = 30.dp
    val projectBadge = Color(0xFF3574F0)
}
