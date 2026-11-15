package intellijandroid.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.Key
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
private class EditorMetrics(val lineHeight: Float, val baseline: Float, val caretTop: Float, val caretHeight: Float, val charWidth: Float, val gutterWidth: Float, val lineNumbersRight: Float)

@Composable
fun EditorView(file: EditorFile, scheme: EditorScheme, modifier: Modifier = Modifier) {
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
            // Gutter, no VCS markers: 4 + 4 + numbers + 4 + icons 17 + 4 + folding 11 + 9.
            val gutterWidth = 4.dp.toPx() + 4.dp.toPx() + lineNumberWidth + 4.dp.toPx() + 17.dp.toPx() + 4.dp.toPx() + 11.dp.toPx() + 9.dp.toPx()
            EditorMetrics(
                lineHeight = lineHeight,
                baseline = sample.firstBaseline + extra,
                caretTop = extra,
                caretHeight = fontHeight,
                charWidth = sample.size.width / 100f,
                gutterWidth = gutterWidth,
                lineNumbersRight = 8.dp.toPx() + lineNumberWidth,
            )
        }
    }
    val highlightsByLine = remember(file, lines) { bucketByLine(file.highlights, lines) }
    val guidesByLine = remember(file, lines) {
        val result = HashMap<Int, MutableList<Int>>()
        for (guide in file.indentGuides) {
            for (line in lines.lineOf(guide.start) + 1 until lines.lineOf(guide.end)) result.getOrPut(line) { mutableListOf() } += guide.indentLevel
        }
        result
    }

    var caret by remember(file.path) { mutableIntStateOf(0) }
    var anchor by remember(file.path) { mutableStateOf<Int?>(null) }
    var preferredColumn by remember(file.path) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(caret, focused) {
        caretOn = true
        while (focused) { delay(500); caretOn = !caretOn }
    }

    fun moveTo(offset: Int, extend: Boolean, keepColumn: Boolean = false) {
        val target = offset.coerceIn(0, file.text.length)
        anchor = if (extend) (anchor ?: caret) else null
        caret = target
        if (!keepColumn) preferredColumn = target - lines.starts[lines.lineOf(target)]
    }

    fun onKey(key: Key, shift: Boolean, meta: Boolean): Boolean {
        val line = lines.lineOf(caret)
        fun lineOffset(targetLine: Int) = lines.starts[targetLine] + minOf(preferredColumn, lines.end(targetLine) - lines.starts[targetLine])
        when (key) {
            Key.DirectionLeft -> moveTo(if (meta) lines.starts[line] else caret - 1, shift)
            Key.DirectionRight -> moveTo(if (meta) lines.end(line) else caret + 1, shift)
            Key.DirectionUp -> if (line > 0) moveTo(lineOffset(line - 1), shift, keepColumn = true) else moveTo(0, shift)
            Key.DirectionDown -> if (line + 1 < lines.count) moveTo(lineOffset(line + 1), shift, keepColumn = true) else moveTo(file.text.length, shift)
            Key.MoveHome -> moveTo(lines.starts[line], shift)
            Key.MoveEnd -> moveTo(lines.end(line), shift)
            Key.A -> if (meta) { anchor = 0; caret = file.text.length } else return false
            else -> return false
        }
        return true
    }

    fun offsetAt(position: Offset): Int {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
            ?: return if (position.y < 0) 0 else file.text.length
        val line = item.index
        val column = ((position.x - metrics.gutterWidth) / metrics.charWidth).roundToInt().coerceAtLeast(0)
        return minOf(lines.starts[line] + column, lines.end(line))
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .background(Color(scheme.background))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event.key, event.isShiftPressed, event.isMetaPressed) }
            .pointerInput(file.path, metrics) {
                detectTapGestures(onPress = { position -> focusRequester.requestFocus(); moveTo(offsetAt(position), extend = false) })
            }
            .pointerInput(file.path, metrics) {
                detectDragGestures(onDragStart = { position -> moveTo(offsetAt(position), extend = false); anchor = caret }) { change, _ ->
                    caret = offsetAt(change.position)
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
}

@Composable
private fun EditorLine(
    line: Int,
    lines: Lines,
    highlights: List<Highlight>,
    guides: List<Int>,
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
