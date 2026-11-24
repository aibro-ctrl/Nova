package ai.nova.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withStyle

/** Тип строки после разбора: от него зависит отступ, размер и оформление. */
private enum class LineKind { PLAIN, HEADING, TABLE, BULLET, CODE }

private class ParsedLine(val kind: LineKind, val text: String, val level: Int = 0)

/**
 * Разметка ответов помощника.
 *
 * Модели отвечают Markdown-ом, а видеть «**важно**» и «- пункт» в чате неприятно. Здесь
 * лёгкий разбор без внешних библиотек: жирный, курсив, `код`, ссылки, списки, заголовки,
 * таблицы. Всё, что не разобрали, показываем как есть — текст никогда не теряется.
 */
fun markdownText(
    text: String,
    styles: TextLinkStyles,
    codeStyle: SpanStyle,
    headingScale: Float = 1.0f,
    baseSizeSp: Float = 0f,
): AnnotatedString {
    // Сначала строки, потом сборка: так разделитель таблицы не оставляет пустую строку,
    // а ссылки не теряются при склейке (аннотации живут отдельно от текста).
    val parsed = mutableListOf<ParsedLine>()
    var fenced = false
    text.replace("\r\n", "\n").split('\n').forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            fenced = !fenced
            return@forEach
        }
        if (fenced) {
            parsed += ParsedLine(LineKind.CODE, line)
            return@forEach
        }
        if (TABLE_RULE.matches(trimmed) || trimmed == "---" || trimmed == "***") return@forEach
        val heading = HEADING.find(line)
        when {
            heading != null -> parsed += ParsedLine(LineKind.HEADING, heading.groupValues[2], heading.groupValues[1].length)
            trimmed.startsWith("|") -> parsed += ParsedLine(LineKind.TABLE, trimmed)
            else -> parsed += ParsedLine(if (BULLET.containsMatchIn(trimmed)) LineKind.BULLET else LineKind.PLAIN, line)
        }
    }
    while (parsed.isNotEmpty() && parsed.first().text.isBlank()) parsed.removeAt(0)
    while (parsed.isNotEmpty() && parsed.last().text.isBlank()) parsed.removeAt(parsed.size - 1)

    return buildAnnotatedString {
        parsed.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            when (line.kind) {
                LineKind.CODE -> withStyle(codeStyle) { append(line.text) }
                LineKind.HEADING -> withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (headingSize(baseSizeSp, line.level) * headingScale).sp,
                    ),
                ) { inline(line.text, styles, codeStyle) }
                // Строка таблицы: ячейки через точку-разделитель — читается в узком пузыре.
                LineKind.TABLE -> line.text.trim('|').split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEachIndexed { i, cell ->
                        if (i > 0) append("  ·  ")
                        inline(cell, styles, codeStyle)
                    }
                LineKind.BULLET -> {
                    append("•  ")
                    inline(line.text.trimStart().replaceFirst(BULLET, ""), styles, codeStyle)
                }
                LineKind.PLAIN -> inline(line.text, styles, codeStyle)
            }
        }
    }
}

private fun headingSize(baseSizeSp: Float, level: Int): Float {
    val base = if (baseSizeSp > 0f) baseSizeSp else 16f
    return when (level) {
        1 -> base + 6f
        2 -> base + 3f
        3 -> base + 1f
        else -> base
    }
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*•]\\s+")
private val TABLE_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

/** Встроенные стили внутри строки: код, ссылка, жирный, курсив — в порядке приоритета. */
private fun AnnotatedString.Builder.inline(part: String, styles: TextLinkStyles, codeStyle: SpanStyle) {
    var i = 0
    fun next(from: Int, marker: String): Int = part.indexOf(marker, from)

    while (i < part.length) {
        val c = part[i]
        if (c == '`') {
            val end = next(i + 1, "`")
            if (end > i) {
                withStyle(codeStyle) { append(part.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val close = part.indexOf("](", i)
            val end = if (close > i) part.indexOf(')', close) else -1
            if (close > i && end > close) {
                val label = part.substring(i + 1, close)
                val url = part.substring(close + 2, end)
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, styles)) { append(label) }
                    i = end + 1
                    continue
                }
            }
        }
        if (c == '*' && i + 1 < part.length && part[i + 1] == '*') {
            val end = next(i + 2, "**")
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (c == '_' && i + 1 < part.length && part[i + 1] == '_') {
            val end = next(i + 2, "__")
            if (end > i) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (c == '*' || c == '_') {
            // Курсив только внутри слова-границы: «2*3» и «snake_case» не трогаем.
            val end = next(i + 1, c.toString())
            val wordStart = i == 0 || !part[i - 1].isLetterOrDigit()
            if (end > i + 1 && wordStart && (end + 1 >= part.length || !part[end + 1].isLetterOrDigit())) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        // Ссылка без разметки: оставляем как есть, но нажимаемую.
        if (c == 'h' && (part.startsWith("http://", i) || part.startsWith("https://", i))) {
            var end = i
            while (end < part.length && !part[end].isWhitespace() && part[end] !in ")]»") end++
            val url = part.substring(i, end)
            withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, styles)) { append(url) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

/** Ответ помощника с разметкой: ссылки нажимаются, списки и код читаются. */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val styles = linkStyles()
    val code = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = (style.fontSize.value * 0.94f).sp,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val annotated = androidx.compose.runtime.remember(text, style.fontSize, styles, code) {
        markdownText(text, styles, code, baseSizeSp = style.fontSize.value)
    }
    Text(annotated, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
}
