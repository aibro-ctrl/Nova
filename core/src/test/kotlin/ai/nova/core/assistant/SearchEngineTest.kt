package ai.nova.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEngineTest {
    @Test
    fun `ссылка ведёт на страницу результатов`() {
        // Главное: ссылка должна открывать результаты, а не главную страницу браузера.
        assertEquals("https://ya.ru/search/?text=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0", SearchEngine.YANDEX.searchUrl("погода"))
        assertEquals("https://www.google.com/search?q=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0", SearchEngine.GOOGLE.searchUrl("погода"))
        assertEquals("https://duckduckgo.com/?q=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B0", SearchEngine.DUCKDUCKGO.searchUrl("погода"))
    }

    @Test
    fun `запрос кодируется целиком`() {
        // Пробелы, плюсы и кириллица не должны ломать ссылку.
        assertTrue(SearchEngine.YANDEX.searchUrl("курс доллара +1%").endsWith("%2B1%25"))
        assertTrue(SearchEngine.YANDEX.searchUrl("  пробелы  ").endsWith("%D0%BF%D1%80%D0%BE%D0%B1%D0%B5%D0%BB%D1%8B"))
    }

    @Test
    fun `неизвестный идентификатор даёт Яндекс`() {
        assertEquals(SearchEngine.YANDEX, SearchEngine.fromId(null))
        assertEquals(SearchEngine.YANDEX, SearchEngine.fromId(""))
        assertEquals(SearchEngine.YANDEX, SearchEngine.fromId("нечто"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("google"))
    }
}
