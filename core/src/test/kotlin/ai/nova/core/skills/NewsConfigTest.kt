package ai.nova.core.skills

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Новости по выбору в настройках: регион, рубрики и «расскажи подробнее». */
class NewsConfigTest {
    private val urls = ArrayList<String>()

    private fun rss(vararg titles: String) = "<rss><channel>" + titles.joinToString("") { t ->
        "<item><title>$t - Лента</title><link>https://lenta.ru/$t</link><source>Лента</source>" +
            "<description><![CDATA[<p>Короткое описание новости ${t}, которое уже длиннее сорока символов.</p>]]></description></item>"
    } + "</channel></rss>"

    private fun http() = HttpClient(MockEngine { r ->
        val u = r.url.toString()
        urls += u
        // Поиск по названной теме приходит с тем же адресом, что и город, — различаем по запросу.
        val q = r.url.parameters["q"].orEmpty()
        val body = when {
            q.contains("видеоигр") -> rss("Игровой один", "Игровой два")
            q.contains("квазар") -> "<rss><channel></channel></rss>"
            u.contains("news.google.com") && u.contains("SPORTS") -> rss("Спорт один", "Спорт два")
            u.contains("news.google.com") && u.contains("TECHNOLOGY") -> rss("Техно один", "Техно два")
            u.contains("bing.com/news/search") -> rss("Казанский один", "Казанский два")
            u.contains("news.google.com/rss/search") -> rss("Казанский один", "Казанский два")
            u.contains("news.google.com") -> rss("Главный один", "Главный два")
            u.contains("https://lenta.ru/Главный") -> "<html><body><script>var x=1;</script><p>Первый абзац статьи про главную новость, который достаточно длинный для чтения.</p><p>Второй абзац с подробностями.</p></body></html>"
            else -> return@MockEngine respond("", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "text/xml; charset=utf-8"))
    })

    private class Host : SkillHost {
        override suspend fun location() = GeoPoint(55.75, 37.62)
    }

    private fun env(settings: AssistantSettings) =
        TestEnv(skillsFactory = { t -> Skills(Host(), http(), t) }).apply { this.settings = settings }

    @Test fun feedUsesChosenRegionAndSection() = runTest {
        val service = NewsService(http())
        val sport = service.feed(NewsTopic.SPORT, NewsQuery(topics = listOf(NewsTopic.SPORT)))
        assertTrue(urls.any { it.contains("news.google.com/rss/headlines/section/topic/SPORTS") && it.contains("gl=RU") }, urls.toString())
        assertEquals("Новости спорта", sport.title)
        assertEquals("Спорт один", sport.items.first().title)
        assertEquals("Лента", sport.items.first().source)

        urls.clear()
        val city = service.feed(NewsQuery(scope = NewsScope.CITY, city = "Казань", topics = listOf(NewsTopic.MAIN)))
        // Город ищется там, где поиск по городу действительно отвечает: Bing Новости, затем Google.
        assertTrue(urls.first().contains("bing.com/news/search") && urls.first().contains("%D0%9A%D0%B0%D0%B7"), urls.first())
        assertEquals("Казанский один", city.items.first().title)
        assertEquals("Новости: Казань", city.title)
    }

    @Test fun severalCategoriesAreMixed() = runTest {
        val feed = NewsService(http()).feed(NewsQuery(topics = listOf(NewsTopic.MAIN, NewsTopic.SPORT)))
        // По одной новости из каждой рубрики — в первой пятёрке видны все выбранные темы.
        assertEquals("Главный один", feed.items[0].title)
        assertEquals("Спорт один", feed.items[1].title)
        assertEquals("Главный два", feed.items[2].title)
    }

    @Test fun namedRubricBeatsCityInSettings() = runTest {
        val e = env(
            AssistantSettings(useAI = false, newsScope = NewsScope.CITY, newsCity = "Казань", newsTopics = listOf(NewsTopic.MAIN)),
        )
        val r = e.engine.handle("новости спорта")
        assertTrue(r.text.contains("Новости спорта"), r.text)
        assertTrue(urls.none { it.contains("bing.com") }, urls.toString())
        assertTrue(urls.any { it.contains("section/topic/SPORTS") }, urls.toString())
    }

    @Test fun namedTopicIsSearchedInsteadOfSettings() = runTest {
        val e = env(
            AssistantSettings(useAI = false, newsScope = NewsScope.CITY, newsCity = "Казань", newsTopics = listOf(NewsTopic.MAIN)),
        )
        val r = e.engine.handle("новости про видеоигры")
        assertTrue(r.text.contains("Новости по теме «видеоигры»"), r.text)
        assertTrue(r.text.contains("1. Игровой один"), r.text)
        // Выбор из настроек (город/главные) не подмешивается и лента «Ленты» не запрашивается.
        assertTrue(urls.none { it.contains("lenta.ru") }, urls.toString())
        assertTrue(urls.none { it.contains("news.google.com/rss?") }, urls.toString())
        assertTrue(urls.any { it.contains("bing.com/news/search") && it.contains("format=RSS") }, urls.toString())
        // Подробности найденной темы работают как обычно, со ссылкой в чате.
        val more = e.engine.handle("расскажи подробнее о второй")
        assertTrue(more.text.contains("Игровой два"), more.text)
        assertTrue(more.link?.contains("lenta.ru/Игровой") == true, more.link ?: "нет ссылки")
    }

    @Test fun emptyTopicSearchIsPolite() = runTest {
        val e = env(AssistantSettings(useAI = false))
        val r = e.engine.handle("новости про квазары")
        assertTrue(r.text.contains("про «квазары» не нашла"), r.text)
        assertTrue(r.outcomes.isEmpty(), "без браузера: ${r.outcomes}")
    }

    @Test fun detailsReadsArticleInsteadOfOpeningBrowser() = runTest {
        val e = env(AssistantSettings(useAI = false))
        val list = e.engine.handle("новости").text
        assertTrue(list.contains("1. Главный один"), list)
        val second = e.engine.handle("расскажи подробнее о второй")
        assertTrue(second.text.contains("Главный два"), second.text)
        // Подробности читаются, а не открываются в браузере.
        assertTrue(second.outcomes.isEmpty(), "подробности не открывают браузер: ${second.outcomes}")
        assertTrue(second.link?.contains("lenta.ru") == true, second.link ?: "нет ссылки")
        assertTrue(second.text.contains("Лента"), second.text)

        val first = e.engine.handle("расскажи подробнее о первой новости")
        assertTrue(first.text.contains("Первый абзац статьи"), first.text)
        assertTrue(first.link?.contains("lenta.ru") == true, first.link ?: "нет ссылки")
    }

    @Test fun detailsByTitleWords() = runTest {
        val e = env(AssistantSettings(useAI = false))
        e.engine.handle("новости")
        val found = e.engine.handle("расскажи подробнее про главную новость")
        assertTrue(found.text.contains("Главный"), found.text)
    }

    @Test fun ordinalFollowUpAfterNews() = runTest {
        val e = env(AssistantSettings(useAI = false))
        e.engine.handle("новости")
        val second = e.engine.handle("а вторую?")
        assertTrue(second.text.contains("Главный два"), second.text)
    }

    @Test fun askDetailsWithoutNewsIsPolite() = runTest {
        val e = env(AssistantSettings(useAI = false))
        val r = e.engine.handle("расскажи подробнее о второй")
        assertTrue(r.text.contains("Сначала попросите новости"), r.text)
    }

    @Test fun bingRedirectLinksBecomeArticleLinks() {
        val items = NewsService.parseRss(
            "<rss><channel><item><title>Новость города</title>" +
                "<link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;url=https%3A%2F%2Fexample.ru%2Fnews%2F1</link>" +
                "</item></channel></rss>",
        )
        assertEquals("https://example.ru/news/1", items.single().link)
    }

    @Test fun readableTextDropsMarkup() {
        val text = NewsService.readable("<html><head><style>p{}</style></head><body><p>Абзац новости, в котором достаточно слов для чтения вслух человеку.</p><p>Ещё один абзац с подробностями события.</p></body></html>")
        assertNotNull(text)
        assertTrue(text.startsWith("Абзац новости"), text)
        assertTrue(!text.contains("<"), text)
    }
}
