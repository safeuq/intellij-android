package intellijandroid.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import intellijandroid.client.session.Lookup
import intellijandroid.client.session.LookupKey
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intellijandroid.client.session.EditorFile
import intellijandroid.client.session.EditorKey
import intellijandroid.client.session.EditorScheme
import intellijandroid.client.session.Highlight
import intellijandroid.client.session.TextEffect
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Where each line starts; line i is text[starts[i] until end(i)], without its line break. */
private class Lines(val text: String) {
    val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }.toIntArray()
    val count get() = starts.size
    fun end(line: Int) = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
    fun lineOf(offset: Int): Int = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }.coerceIn(0, count - 1)
    fun text(line: Int) = text.substring(starts[line], end(line))
}

/** Editor geometry in px, following IntelliJ's `EditorView` and `EditorGutterLayout` (New UI). */
private class EditorMetrics(val lineHeight: Float, val baseline: Float, val caretTop: Float, val caretHeight: Float, val charWidth: Float, val gutterWidth: Float, val lineNumbersRight: Float, val iconAreaLeft: Float)

/** What the editor asks the session to do; the session applies it locally first and syncs it with the backend. */
class EditorActions(
    val type: (Char) -> Unit,
    val key: (EditorKey) -> Unit,
    /** New caret offset and selection anchor (equal to the offset when nothing is selected). */
    val moveCaret: (Int, Int) -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    /** Ctrl+Space. */
    val complete: () -> Unit = {},
    /** Keys for the completion popup while it is open. */
    val lookupKey: (LookupKey) -> Unit = {},
    /** A click or tap on a completion popup row, by item id. */
    val chooseLookupItem: (String) -> Unit = {},
)

@Composable
fun EditorView(file: EditorFile, scheme: EditorScheme, actions: EditorActions, modifier: Modifier = Modifier, lookup: Lookup? = null) {
    val fonts = LocalClientFonts.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val lines = remember(file.text) { Lines(file.text) }
    val textStyle = remember(fonts, scheme) { TextStyle(fontFamily = fonts.editor, fontSize = IslandsDark.EDITOR_FONT_SIZE.sp, color = Color(scheme.foreground)) }
    val lineNumberStyle = remember(fonts) { TextStyle(fontFamily = fonts.editor, fontSize = IslandsDark.LINE_NUMBER_FONT_SIZE.sp) }

    val metrics = remember(textStyle, lines.count, density) {
        with(density) {
            // IntelliJ: lineHeight = ceil(fontMetricsHeight × lineSpacing); the extra space is split above and below.
            val sample = measurer.measure("0".repeat(100), textStyle)
            val fontHeight = ceil(sample.size.height.toFloat() / density.density) * density.density
            val lineHeight = ceil(fontHeight / density.density * IslandsDark.LINE_SPACING) * density.density
            val extra = ((lineHeight - fontHeight) / density.density / 2).toInt() * density.density
            val lineNumberWidth = max(14.dp.toPx(), ceil(measurer.measure("9".repeat(lines.count.toString().length), lineNumberStyle).size.width.toFloat()))
            // Gutter as measured on the real client (Islands Dark, no VCS markers): numbers, 4, icons 17, 4,
            // folding 11, 6. With two-digit line numbers that is 56 px, text starting right after.
            val gutterWidth = lineNumberWidth + 4.dp.toPx() + 17.dp.toPx() + 4.dp.toPx() + 11.dp.toPx() + 6.dp.toPx()
            EditorMetrics(
                lineHeight = lineHeight,
                baseline = sample.firstBaseline + extra,
                caretTop = extra,
                caretHeight = fontHeight,
                charWidth = sample.size.width / 100f,
                gutterWidth = gutterWidth,
                lineNumbersRight = lineNumberWidth,
                // Icons area (17 px) after the line numbers and their 4 px gap.
                iconAreaLeft = lineNumberWidth + 4.dp.toPx(),
            )
        }
    }
    val highlightsByLine = remember(file, lines) { bucketByLine(file.highlights, lines) }
    val gutterIconsByLine = remember(file, lines) { file.gutterIcons.groupBy { lines.lineOf(it.offset) } }
    val guidesByLine = remember(file, lines) {
        val result = HashMap<Int, MutableList<Int>>()
        for (guide in file.indentGuides) {
            for (line in lines.lineOf(guide.start) + 1 until lines.lineOf(guide.end)) result.getOrPut(line) { mutableListOf() } += guide.indentLevel
        }
        result
    }

    // Caret and selection belong to the session (they are synced with the backend); only the column to keep when
    // moving up and down, and the drag start, live here.
    val caret = file.caret
    val anchor = if (file.selectionStart == file.selectionEnd) null else if (file.selectionStart == caret) file.selectionEnd else file.selectionStart
    var preferredColumn by remember(file.path) { mutableIntStateOf(0) }
    var dragAnchor by remember(file.path) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The pointer handlers below live across recompositions; they must read the current text, not the first one.
    val currentLines by rememberUpdatedState(lines)
    val currentFile by rememberUpdatedState(file)


    LaunchedEffect(caret, focused) {
        caretOn = true
        while (focused) { delay(500); caretOn = !caretOn }
    }

    // Keep the caret's line on screen: after typing or arrows past an edge, and when the view gets shorter
    // (on Android the soft keyboard takes the lower half of the screen).
    val caretLine = lines.lineOf(caret)
    val viewportHeight = listState.layoutInfo.viewportSize.height
    LaunchedEffect(caretLine, viewportHeight, focused) {
        if (!focused || viewportHeight == 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == caretLine }
        val lineHeight = layout.visibleItemsInfo.firstOrNull()?.size ?: 0
        when {
            item == null && caretLine < listState.firstVisibleItemIndex -> listState.scrollToItem(caretLine)
            item == null -> {
                listState.scrollToItem(caretLine)
                listState.scrollBy(-(layout.viewportEndOffset - layout.viewportStartOffset - lineHeight).toFloat())
            }
            item.offset < layout.viewportStartOffset -> listState.scrollBy((item.offset - layout.viewportStartOffset).toFloat())
            item.offset + item.size > layout.viewportEndOffset -> listState.scrollBy((item.offset + item.size - layout.viewportEndOffset).toFloat())
        }
    }

    fun moveTo(offset: Int, extend: Boolean, keepColumn: Boolean = false) {
        val now = currentFile
        val target = offset.coerceIn(0, now.text.length)
        val currentAnchor = if (now.selectionStart == now.selectionEnd) null else if (now.selectionStart == now.caret) now.selectionEnd else now.selectionStart
        actions.moveCaret(target, if (extend) (currentAnchor ?: now.caret) else target)
        if (!keepColumn) preferredColumn = target - currentLines.starts[currentLines.lineOf(target)]
    }

    /** Typed characters. ponytail: key events, no IME; Android needs an InputConnection for its soft keyboard. */
    fun typeCharacter(event: KeyEvent): Boolean {
        val character = event.utf16CodePoint
        // Arrow and function keys report private-use or non-character codes (e.g. U+FFFF); those aren't text.
        val isText = !Character.isISOControl(character) && character != 0xFFFF && Character.getType(character) != Character.PRIVATE_USE.toInt()
        if (!file.editable || event.isMetaPressed || event.isCtrlPressed || !isText) return false
        actions.type(character.toChar())
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        val key = event.key
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed
        val line = lines.lineOf(caret)
        if (lookup != null) {
            when (key) {
                Key.DirectionUp -> { actions.lookupKey(LookupKey.UP); return true }
                Key.DirectionDown -> { actions.lookupKey(LookupKey.DOWN); return true }
                Key.Enter, Key.Tab -> { actions.lookupKey(LookupKey.ACCEPT); return true }
                Key.Escape -> { actions.lookupKey(LookupKey.CANCEL); return true }
            }
        }
        if (file.editable && event.isCtrlPressed && key == Key.Spacebar) { actions.complete(); return true }
        if (file.editable) {
            when {
                key == Key.Enter -> { actions.key(EditorKey.ENTER); return true }
                key == Key.Backspace -> { actions.key(EditorKey.BACKSPACE); return true }
                key == Key.Delete -> { actions.key(EditorKey.DELETE); return true }
                meta && key == Key.Z -> { if (shift) actions.redo() else actions.undo(); return true }
            }
        }
        fun lineOffset(targetLine: Int) = lines.starts[targetLine] + minOf(preferredColumn, lines.end(targetLine) - lines.starts[targetLine])
        when (key) {
            Key.DirectionLeft -> moveTo(if (meta) lines.starts[line] else caret - 1, shift)
            Key.DirectionRight -> moveTo(if (meta) lines.end(line) else caret + 1, shift)
            Key.DirectionUp -> if (line > 0) moveTo(lineOffset(line - 1), shift, keepColumn = true) else moveTo(0, shift)
            Key.DirectionDown -> if (line + 1 < lines.count) moveTo(lineOffset(line + 1), shift, keepColumn = true) else moveTo(file.text.length, shift)
            Key.MoveHome -> {
                // IntelliJ's smart Home: the first non-space character, then column 0 when already there.
                val start = lines.starts[line]
                val firstNonSpace = (start until lines.end(line)).firstOrNull { !file.text[it].isWhitespace() } ?: start
                moveTo(if (file.caret == firstNonSpace) start else firstNonSpace, shift)
            }
            Key.MoveEnd -> moveTo(lines.end(line), shift)
            Key.A -> if (meta) actions.moveCaret(file.text.length, 0) else return typeCharacter(event)
            else -> return typeCharacter(event)
        }
        return true
    }

    fun offsetAt(position: Offset): Int {
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
        val line = item?.index ?: if (position.y < 0) 0 else currentLines.count - 1
        val column = ((position.x - metrics.gutterWidth) / metrics.charWidth).roundToInt().coerceAtLeast(0)
        val offset = minOf(currentLines.starts[line] + column, currentLines.end(line))
        if (System.getenv("CLIENT_EDIT_DEBUG") != null) println("[edit] pointer $position -> line ${line + 1} column $column offset $offset (first visible item offset ${visible.firstOrNull()?.offset})")
        return offset
    }

    Box(modifier) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(scheme.background))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .then(LocalEditorTextInput.current)
            .focusable()
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event) }
            .pointerInput(file.path, metrics) {
                detectTapGestures(onPress = { position -> focusRequester.requestFocus(); moveTo(offsetAt(position), extend = false) })
            }
            .pointerInput(file.path, metrics) {
                detectDragGestures(onDragStart = { position -> dragAnchor = offsetAt(position); moveTo(dragAnchor, extend = false) }) { change, _ ->
                    actions.moveCaret(offsetAt(change.position), dragAnchor)
                }
            },
    ) {
        items(lines.count) { line ->
            val selection = anchor?.let { minOf(it, caret) until maxOf(it, caret) }
            EditorLine(
                line = line,
                lines = lines,
                highlights = highlightsByLine[line].orEmpty(),
                guides = guidesByLine[line].orEmpty(),
                gutterIconPath = gutterIconsByLine[line]?.firstOrNull()?.iconPaths?.firstOrNull(),
                scheme = scheme,
                metrics = metrics,
                measurer = measurer,
                textStyle = textStyle,
                lineNumberStyle = lineNumberStyle,
                caretColumn = if (lines.lineOf(caret) == line) caret - lines.starts[line] else null,
                caretVisible = focused && caretOn,
                selection = selection,
            )
        }
    }
    // The completion popup sits under the caret line, its item text lined up with the first letter of the prefix.
    lookup?.takeIf { it.items.isNotEmpty() }?.let { current ->
        val caretItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == caretLine } ?: return@let
        val prefixColumn = caret - lines.starts[caretLine] - current.prefix.length
        val textOffset = with(density) { LOOKUP_PREFIX_INSET.toPx() }
        val x = (metrics.gutterWidth + prefixColumn * metrics.charWidth - textOffset).roundToInt().coerceAtLeast(0)
        Popup(offset = IntOffset(x, caretItem.offset + caretItem.size)) {
            LookupPopup(current, actions.chooseLookupItem)
        }
    }
    }
}

@Composable
private fun EditorLine(
    line: Int,
    lines: Lines,
    highlights: List<Highlight>,
    guides: List<Int>,
    gutterIconPath: String?,
    scheme: EditorScheme,
    metrics: EditorMetrics,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    lineNumberStyle: TextStyle,
    caretColumn: Int?,
    caretVisible: Boolean,
    selection: IntRange?,
) {
    val density = LocalDensity.current
    val lineStart = lines.starts[line]
    val lineText = remember(lines, line) { lines.text(line) }
    val annotated = remember(lineText, highlights) { styledLine(lineText, lineStart, highlights) }
    val layout = remember(annotated, textStyle) { measurer.measure(annotated, textStyle, softWrap = false) }
    val lineNumber = remember(line, lineNumberStyle) { measurer.measure(AnnotatedString((line + 1).toString()), lineNumberStyle) }
    val height = with(density) { metrics.lineHeight.toDp() }
    val gutterIcon = gutterIconPath?.let(LocalIcons.current::painter)

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val isCaretLine = caretColumn != null
        if (isCaretLine) scheme.color("CARET_ROW_COLOR")?.let { drawRect(Color(it)) }

        val textLeft = metrics.gutterWidth
        fun x(column: Int) = textLeft + layout.getHorizontalPosition(column.coerceIn(0, lineText.length), usePrimaryDirection = true)

        // Selection: to the end of the text on the last line, to the right edge on lines it continues past.
        selection?.let { range ->
            val from = maxOf(range.first, lineStart) - lineStart
            val lineEnd = lineStart + lineText.length
            if (range.first <= lineEnd && range.last + 1 >= lineStart && !range.isEmpty()) {
                val to = if (range.last + 1 > lineEnd) size.width else x(range.last + 1 - lineStart)
                scheme.color("SELECTION_BACKGROUND")?.let { drawRect(Color(it), Offset(x(from), 0f), Size(to - x(from), size.height)) }
            }
        }
        // Highlight backgrounds (identifier under caret, search results, matched braces, …).
        for (highlight in highlights) {
            val background = highlight.style.background ?: continue
            val from = maxOf(highlight.start - lineStart, 0)
            val to = minOf(highlight.end - lineStart, lineText.length)
            if (to > from) drawRect(Color(background), Offset(x(from), 0f), Size(x(to) - x(from), size.height))
        }
        // Indent guides: 1 px at the guide's column.
        scheme.color("INDENT_GUIDE")?.let { guideColor ->
            for (level in guides) {
                val guideX = textLeft + level * metrics.charWidth
                drawLine(Color(guideColor), Offset(guideX, 0f), Offset(guideX, size.height), strokeWidth = 1.dp.toPx())
            }
        }

        // Gutter: line number right-aligned on the editor's baseline, brighter on the caret line.
        val numberColor = scheme.color(if (isCaretLine) "LINE_NUMBER_ON_CARET_ROW_COLOR" else "LINE_NUMBERS_COLOR")?.let { Color(it) } ?: Color.Gray
        drawText(lineNumber, color = numberColor, topLeft = Offset(metrics.lineNumbersRight - lineNumber.size.width, metrics.baseline - lineNumber.firstBaseline))
        // Gutter icon (e.g. the green run marker), 14 px, centered in the 17 px icons area.
        if (gutterIcon != null) {
            val iconSize = 14.dp.toPx()
            translate(metrics.iconAreaLeft + (17.dp.toPx() - iconSize) / 2, (size.height - iconSize) / 2) {
                with(gutterIcon) { draw(Size(iconSize, iconSize)) }
            }
        }

        drawText(layout, topLeft = Offset(textLeft, metrics.baseline - layout.firstBaseline))
        for (highlight in highlights) drawEffect(highlight, lineStart, lineText.length, ::x, metrics)

        if (caretColumn != null && caretVisible) {
            scheme.color("CARET_COLOR")?.let { caretColor ->
                drawRect(Color(caretColor), Offset(x(caretColumn), metrics.caretTop), Size(2.dp.toPx(), metrics.caretHeight))
            }
        }
    }
}

private fun DrawScope.drawEffect(highlight: Highlight, lineStart: Int, lineLength: Int, x: (Int) -> Float, metrics: EditorMetrics) {
    val effect = highlight.style.effect ?: return
    val color = Color(highlight.style.effectColor ?: return)
    val from = maxOf(highlight.start - lineStart, 0)
    val to = minOf(highlight.end - lineStart, lineLength)
    if (to <= from) return
    val left = x(from)
    val right = x(to)
    val baseline = metrics.baseline
    when (effect) {
        // IntelliJ's EffectPainter2D: a 2 px period zig-zag 3 to 6 px below the baseline.
        TextEffect.WAVE_UNDERSCORE -> {
            val top = baseline + 3.dp.toPx()
            val bottom = baseline + 5.dp.toPx()
            val step = 1.dp.toPx()
            val path = Path().apply {
                moveTo(left, bottom)
                var position = left
                var up = true
                while (position < right) {
                    position += step
                    lineTo(position.coerceAtMost(right), if (up) top else bottom)
                    up = !up
                }
            }
            drawPath(path, color, style = Stroke(width = 0.7.dp.toPx()))
        }
        TextEffect.LINE_UNDERSCORE, TextEffect.BOLD_LINE_UNDERSCORE -> {
            val y = baseline + 1.5.dp.toPx()
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = (if (effect == TextEffect.BOLD_LINE_UNDERSCORE) 2 else 1).dp.toPx())
        }
        TextEffect.STRIKEOUT -> {
            val y = baseline - 3.5.dp.toPx()
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        TextEffect.BOXED -> drawRect(color, Offset(left, metrics.caretTop), Size(right - left, metrics.caretHeight), style = Stroke(1.dp.toPx()))
        TextEffect.BOLD_DOTTED_LINE -> {
            val y = baseline + 1.5.dp.toPx()
            var position = left
            while (position < right) { drawLine(color, Offset(position, y), Offset(minOf(position + 2.dp.toPx(), right), y), strokeWidth = 2.dp.toPx()); position += 4.dp.toPx() }
        }
    }
}

/** The line's text with colors and font styles of the highlights over it; higher layers paint last and win. */
private fun styledLine(lineText: String, lineStart: Int, highlights: List<Highlight>): AnnotatedString {
    val builder = AnnotatedString.Builder(lineText)
    for (highlight in highlights) {
        val style = highlight.style
        if (style.foreground == null && style.fontType == 0) continue
        val from = maxOf(highlight.start - lineStart, 0)
        val to = minOf(highlight.end - lineStart, lineText.length)
        if (to <= from) continue
        builder.addStyle(
            SpanStyle(
                color = style.foreground?.let { Color(it) } ?: Color.Unspecified,
                fontWeight = if (style.isBold) FontWeight.Bold else null,
                fontStyle = if (style.isItalic) FontStyle.Italic else null,
            ),
            from, to,
        )
    }
    return builder.toAnnotatedString()
}

/** Highlights per line, in layer order. ponytail: one pass over all highlights; fine for files of a few thousand lines. */
private fun bucketByLine(highlights: List<Highlight>, lines: Lines): Map<Int, List<Highlight>> {
    val result = HashMap<Int, MutableList<Highlight>>()
    for (highlight in highlights) {
        if (highlight.end <= highlight.start) continue
        for (line in lines.lineOf(highlight.start)..lines.lineOf(highlight.end - 1)) result.getOrPut(line) { mutableListOf() } += highlight
    }
    return result
}
