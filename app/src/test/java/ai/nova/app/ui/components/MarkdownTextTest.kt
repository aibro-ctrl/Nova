package ai.nova.app.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разметка ответов: модели присылают Markdown, а в чате он должен читаться как текст.
 * Проверяем то, что реально ломается: жирный, списки, заголовки, ссылки, таблицы и «мусор»,
 * который нельзя молча потерять.
 */
class MarkdownTextTest {
    private val links = TextLinkStyles()
    private val code = SpanStyle(fontFamily = FontFamily.Monospace)

    private fun render(text: String) = markdownText(text, links, code, baseSizeSp = 16f)

    @Test
    fun boldIsRenderedWithoutMarkers() {
        val out = render("Это **важно** и это нет")
        assertEquals("Это важно и это нет", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun bulletsBecomeDots() {
        val out = render("Покупки:\n- молоко\n- хлеб")
        assertEquals("Покупки:\n•  молоко\n•  хлеб", out.text)
    }

    @Test
    fun headingsLoseHashes() {
        val out = render("## Итог\nвсё хорошо")
        assertEquals("Итог\nвсё хорошо", out.text)
        assertTrue(out.spanStyles.any { it.item.fontSize.value > 16f })
    }

    @Test
    fun linkKeepsLabelAndUrl() {
        val out = render("Нашла: [Открыть снова](https://yandex.ru/search?text=погода)")
        assertEquals("Нашла: Открыть снова", out.text)
        val found = out.getLinkAnnotations(0, out.length)
        assertTrue(found.any { (it.item as? LinkAnnotation.Url)?.url?.startsWith("https://yandex.ru") == true })
    }

    @Test
    fun tableSeparatorIsDropped() {
        val out = render("| День | Сумма |\n|---|---|\n| Пн | 300 ₽ |")
        assertEquals("День  ·  Сумма\nПн  ·  300 ₽", out.text)
    }

    @Test
    fun codeSpanKeepsContentWithoutBackticks() {
        val out = render("Команда `nova --help` работает")
        assertEquals("Команда nova --help работает", out.text)
    }

    @Test
    fun plainTextSurvivesUnchanged() {
        val text = "Всё хорошо, спасибо! Как у тебя дела?"
        assertEquals(text, render(text).text)
    }

    @Test
    fun unclosedMarkerStays() {
        // Модель не закрыла жирный — текст не должен исчезнуть.
        val out = render("2*3 = 6 и **незакрытый")
        assertEquals("2*3 = 6 и **незакрытый", out.text)
    }

    @Test
    fun fencedCodeBlockDropsFences() {
        val out = render("```\nnova\n```")
        assertEquals("nova", out.text)
        assertFalse(out.text.contains("```"))
    }
}
