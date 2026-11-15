package intellijandroid.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.Lookup
import intellijandroid.client.session.LookupItem
import intellijandroid.client.session.matchedIndices

/**
 * IntelliJ's completion popup: rows of icon, item text with the typed letters in blue, grey tail, grey type on the
 * right; grouped items ("Commands") under a header; a tip in the footer. Colours from ManyIslandsDark.theme.json
 * (`Popup.*`, `CompletionPopup.*`), sizes measured on the real client (docs/intellij-look.md, "Completion popup").
 */
@Composable
internal fun LookupPopup(lookup: Lookup, onChoose: (String) -> Unit) {
    val fonts = LocalClientFonts.current
    val icons = LocalIcons.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val itemStyle = TextStyle(fontFamily = fonts.editor, fontSize = IslandsDark.EDITOR_FONT_SIZE.sp, color = Lookups.foreground)
    val footerStyle = TextStyle(fontFamily = fonts.ui, fontSize = (IslandsDark.UI_FONT_SIZE - 1).sp, color = Lookups.secondary)
    val headerStyle = TextStyle(fontFamily = fonts.ui, fontSize = IslandsDark.UI_FONT_SIZE.sp, fontWeight = FontWeight.SemiBold, color = Lookups.secondary)

    // One entry per row; a group's first item gets a separator and the group's header before it.
    val entries = remember(lookup.items) {
        buildList {
            var group: String? = null
            lookup.items.forEachIndexed { index, item ->
                if (item.group != null && item.group != group) add(Entry.Header(item.group))
                group = item.group
                add(Entry.Item(index, item))
            }
        }
    }
    val width = remember(lookup.items, lookup.advertisement, itemStyle) {
        with(density) {
            val rows = lookup.items.maxOf { item ->
                measurer.measure(item.itemText + item.tail, itemStyle).size.width + measurer.measure(item.typeText, itemStyle).size.width
            }.toDp() + Lookups.textInset + Lookups.typeGap + Lookups.rightPadding
            val footer = lookup.advertisement?.let { measurer.measure(it, footerStyle).size.width.toDp() + Lookups.footerInset * 2 } ?: 0.dp
            maxOf(rows, footer).coerceIn(Lookups.minWidth, Lookups.maxWidth)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lookup.selected, entries) {
        val target = entries.indexOfFirst { it is Entry.Item && it.index == lookup.selected }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (target >= 0 && visible.isNotEmpty() && (target < visible.first().index || target > visible.last().index)) {
            listState.scrollToItem(if (target < visible.first().index) target else (target - visible.size + 2).coerceAtLeast(0))
        }
    }
    val shape = RoundedCornerShape(Lookups.radius)

    Column(Modifier.width(width).background(Lookups.background, shape).border(1.dp, Lookups.border, shape).padding(top = Lookups.verticalPadding)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = Lookups.rowHeight * Lookups.maxRows)) {
            items(entries.size) { position ->
                when (val entry = entries[position]) {
                    is Entry.Header -> GroupHeader(entry.name, headerStyle)
                    is Entry.Item -> LookupRow(entry.item, lookup.prefix, entry.index == lookup.selected, lookup.focused, itemStyle, icons, onChoose)
                }
            }
        }
        if (lookup.advertisement != null) {
            BasicText(lookup.advertisement, style = footerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Lookups.footerInset, vertical = Lookups.footerVerticalPadding))
        } else {
            Spacer(Modifier.height(Lookups.verticalPadding))
        }
    }
}

private sealed interface Entry {
    class Header(val name: String) : Entry
    class Item(val index: Int, val item: LookupItem) : Entry
}

@Composable
private fun GroupHeader(name: String, style: TextStyle) {
    Column {
        Spacer(Modifier.height(Lookups.separatorGap))
        Box(Modifier.fillMaxWidth().padding(horizontal = Lookups.separatorInset).height(1.dp).background(Lookups.border))
        Box(Modifier.fillMaxWidth().height(Lookups.rowHeight).padding(start = Lookups.footerInset), contentAlignment = Alignment.CenterStart) {
            BasicText(name, style = style, maxLines = 1)
        }
    }
}

@Composable
private fun LookupRow(item: LookupItem, prefix: String, selected: Boolean, focused: Boolean, style: TextStyle, icons: IconProvider, onChoose: (String) -> Unit) {
    val text = remember(item, prefix) { rowText(item, prefix) }
    val selection = when {
        !selected -> Color.Transparent
        focused -> Lookups.selection
        else -> Lookups.selectionUnfocused
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Lookups.rowHeight)
            .padding(horizontal = Lookups.selectionInset)
            .background(selection, RoundedCornerShape(Lookups.selectionRadius))
            .clickable { onChoose(item.id) }
            .padding(start = Lookups.iconLeft - Lookups.selectionInset, end = Lookups.rightPadding - Lookups.selectionInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Lookups.iconSize)) {
            item.iconPath?.let(icons::painter)?.let { Image(it, contentDescription = null, modifier = Modifier.size(Lookups.iconSize)) }
        }
        Spacer(Modifier.width(Lookups.iconGap))
        BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = true))
        if (item.typeText.isNotEmpty()) {
            Spacer(Modifier.width(Lookups.typeGap))
            BasicText(item.typeText, style = style.copy(color = Lookups.secondary), maxLines = 1)
        }
    }
}

private fun rowText(item: LookupItem, prefix: String): AnnotatedString {
    val matched = matchedIndices(prefix, item.itemText).orEmpty()
    return buildAnnotatedString {
        val base = SpanStyle(fontWeight = if (item.bold) FontWeight.Bold else null, textDecoration = if (item.strikeout) TextDecoration.LineThrough else null)
        item.itemText.forEachIndexed { index, character ->
            withStyle(if (index in matched) base.merge(SpanStyle(color = Lookups.match)) else base) { append(character) }
        }
        withStyle(SpanStyle(color = Lookups.secondary)) { append(item.tail) }
    }
}

/**
 * From the popup's left edge to where the editor puts the typed prefix. The real popup's item text starts 32 dp in
 * and sits 6.5 dp right of the prefix in the editor (measured, 2026-11-15).
 */
internal val LOOKUP_PREFIX_INSET get() = Lookups.textInset - 6.5.dp

/** Popup metrics (measured on JetBrains Client 263.7307.200, Islands Dark) and colours. */
private object Lookups {
    val background = Color(0xFF26282C)          // Popup.background = popup-bg = layer-1-bg
    val border = Color(0xFF33353B)              // Popup.borderColor = layer-1-border; also the group separator
    val foreground = Color(0xFFBCBEC4)          // CompletionPopup.foreground = editor-text
    val match = Color(0xFF538AF9)               // CompletionPopup.matchForeground
    val selection = Color(0xFF2A4371)           // selection-bg-active: after the user moved the selection
    val selectionUnfocused = Color(0xFF33353B)  // measured #33353A: autopopup before any arrow key
    val secondary = Color(0xFF73767C)           // text-secondary: tail, type, group header, footer
    val rowHeight = 22.dp
    val maxRows = 11
    val radius = 6.dp                           // arc 12
    val verticalPadding = 2.dp                  // border to first row
    val selectionInset = 3.dp                   // border to selection band, both sides
    val selectionRadius = 4.dp
    val iconLeft = 11.dp                        // border to icon
    val iconSize = 16.dp
    val iconGap = 5.dp
    val textInset = iconLeft + iconSize + iconGap   // 32: border to item text
    val rightPadding = 17.dp                    // type text to border
    val typeGap = 24.dp
    val separatorGap = 4.dp
    val separatorInset = 11.dp
    val footerInset = 12.dp                     // Popup.Advertiser borderInsets 6,12,6,12
    val footerVerticalPadding = 6.dp
    val minWidth = 160.dp
    val maxWidth = 640.dp
}
