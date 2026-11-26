package ai.nova.core.skills

import ai.nova.core.data.NovaJson
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Рубрика новостей.
 *
 * [section] — раздел ленты Google Новостей (открывается без ключей и регистрации), [keyword] — слово
 * для поиска новостей по городу, [feeds] — запасные RSS-ленты, если Google недоступен.
 */
enum class NewsTopic(
    val title: String,
    val feeds: List<String>,
    val section: String? = null,
    val keyword: String = "",
) {
    MAIN("Главные новости", listOf("https://lenta.ru/rss/news", "https://tass.ru/rss/v2.xml", "https://ria.ru/export/rss2/archive/index.xml")),
    WORLD("Новости мира", listOf("https://lenta.ru/rss/news/world"), section = "WORLD"),
    COUNTRY("Новости страны", listOf("https://lenta.ru/rss/news/russia"), section = "NATION"),
    SPORT("Новости спорта", listOf("https://lenta.ru/rss/news/sport"), section = "SPORTS", keyword = "спорт"),
    SCIENCE("Новости науки и техники", listOf("https://lenta.ru/rss/news/science", "https://habr.com/ru/rss/news/?fl=ru"), section = "SCIENCE", keyword = "наука"),
    TECH("Новости технологий", listOf("https://habr.com/ru/rss/news/?fl=ru", "https://lenta.ru/rss/news/science"), section = "TECHNOLOGY", keyword = "технологии"),
    ECONOMY("Новости экономики", listOf("https://lenta.ru/rss/news/economics", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss"), section = "BUSINESS", keyword = "экономика"),
    CULTURE("Новости культуры", listOf("https://lenta.ru/rss/news/culture"), section = "ENTERTAINMENT", keyword = "культура"),
    HEALTH("Новости здоровья", listOf("https://lenta.ru/rss/news/health"), section = "HEALTH", keyword = "здоровье"),
    ;

    /** Рубрики, которые можно выбрать в настройках. «Мир» и «Страна» — это регион, а не рубрика. */
    companion object {
        val PICKABLE: List<NewsTopic> = listOf(MAIN, ECONOMY, TECH, SCIENCE, SPORT, CULTURE, HEALTH)
        fun of(id: String): NewsTopic? = entries.firstOrNull { it.name.equals(id.trim(), true) }
    }
}

/** Что показывать: мир целиком, своя страна или конкретный город. */
enum class NewsScope(val id: String, val title: String, val hint: String) {
    WORLD("world", "Мир", "Главное из мира — без привязки к городу"),
    COUNTRY("country", "Страна", "Главное по стране: политика, экономика, общество"),
    CITY("city", "Город", "Новости города, который вы выбрали");

    companion object {
        fun of(id: String?): NewsScope = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: WORLD
    }
}

/** Выбор новостей из настроек: регион, город и рубрики. */
data class NewsQuery(
    val scope: NewsScope = NewsScope.WORLD,
    val city: String? = null,
    /** Код страны для ленты Google (RU, KZ, BY…): новости на русском в этом регионе. */
    val country: String = "RU",
    val topics: List<NewsTopic> = listOf(NewsTopic.MAIN),
    /** Тема своими словами: «видеоигр», «недвижимость», «пк». Задана — рубрики не применяются. */
    val about: String? = null,
) {
    /** Регион без рубрик не бывает: если ничего не выбрано — главное. */
    val effectiveTopics: List<NewsTopic> get() = topics.ifEmpty { listOf(NewsTopic.MAIN) }

    /** Как назвать ленту человеку: тема — своими словами, одна рубрика — своим именем, иначе по региону. */
    val title: String
        get() = about?.takeIf { it.isNotBlank() }?.let { "Новости по теме «$it»" }
            ?: titleOf(effectiveTopics.singleOrNull())

    fun titleOf(topic: NewsTopic?): String = when {
        scope == NewsScope.CITY -> city?.takeIf { it.isNotBlank() }?.let { "Новости: $it" } ?: "Главные новости"
        scope == NewsScope.COUNTRY && (topic == null || topic == NewsTopic.MAIN) -> "Новости страны"
        topic == null -> "Новости по вашему выбору"
        else -> topic.title
    }

    companion object {
        /** Разбор выбора из настроек: «спорт,технологии» → рубрики; неизвестное отбрасываем. */
        fun topicsOf(csv: String?): List<NewsTopic> =
            csv.orEmpty().split(',').mapNotNull { NewsTopic.of(it) }.distinct().ifEmpty { listOf(NewsTopic.MAIN) }
    }
}

/** Заголовки новостей из RSS (Google Новости, Лента, ТАСС, РИА, Хабр, РБК) — без ключей и регистрации. */
class NewsService(private val http: HttpClient) {
    data class Item(
        val title: String,
        val link: String?,
        val source: String? = null,
        val publishedAt: java.time.Instant? = null,
        val description: String? = null,
    )

    private val cache = TtlCache<NewsQuery, List<Item>>(10 * 60_000L)
    private val detailsCache = TtlCache<String, String?>(6 * 3600_000L)

    /** Лента, из которой пришли заголовки: если выбранная рубрика недоступна — это главные новости. */
    data class Feed(val topic: NewsTopic?, val items: List<Item>, val title: String)

    /**
     * Заголовки по выбору из настроек. Рубрики запрашиваются параллельно и перемешиваются,
     * чтобы в первой пятёрке были все выбранные темы, а не только первая.
     */
    suspend fun feed(query: NewsQuery): Feed {
        cache.get(query)?.let { return Feed(query.effectiveTopics.firstOrNull(), it, query.title) }
        // Названа конкретная тема — ищем только её, а не смешиваем с рубриками из настроек.
        query.about?.takeIf { it.isNotBlank() }?.let { term ->
            val items = search(term, query)
            if (items.isEmpty()) throw InfoUnavailable("по теме ничего нет")
            cache.put(query, items)
            return Feed(null, items, query.title)
        }
        val topics = query.effectiveTopics
        if (topics.size == 1) {
            val feed = feed(topics.single(), query)
            cache.put(query, feed.items)
            return Feed(feed.topic, feed.items, query.titleOf(feed.topic))
        }
        val lanes = coroutineScope {
            topics.map { t -> async { runCatching { feed(t, query).items }.getOrDefault(emptyList()) } }.map { it.await() }
        }
        val mixed = interleave(lanes).distinctBy { it.title }.toList()
        if (mixed.isEmpty()) throw InfoUnavailable("новостей нет")
        cache.put(query, mixed)
        return Feed(topics.firstOrNull(), mixed, query.title)
    }

    /** Одна рубрика: сначала лента Google с учётом региона, затем привычные RSS-ленты. */
    suspend fun feed(topic: NewsTopic, query: NewsQuery): Feed {
        var lastError: Exception? = null
        val title = query.titleOf(topic)
        for (url in urls(topic, query)) {
            try {
                // Bing отдаёт ленту только браузеру, остальные сайты — обычному клиенту Новы.
                val ua = if (url.contains("bing.com")) BROWSER_USER_AGENT else NOVA_USER_AGENT
                val items = parseRss(http.fetchText(url, ua))
                if (items.isNotEmpty()) return Feed(topic, items, title)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Лента может быть закрыта для этого региона или сети — видно, какая именно и почему.
                ai.nova.core.util.Logger.w("News", "лента не открылась: $url (${e.message})")
                lastError = e as? InfoUnavailable ?: InfoUnavailable("лента новостей не читается")
            }
        }
        // Главные новости — общий запасной вариант, если выбранная рубрика молчит.
        if (topic != NewsTopic.MAIN) return feed(NewsTopic.MAIN, query).copy(topic = topic)
        throw lastError ?: InfoUnavailable("новостей нет")
    }

    /**
     * Подробности по новости: заголовок, источник, время и первый абзац статьи.
     * Если страница не открылась — null, тогда Нова честно покажет только заголовок и ссылку.
     */
    suspend fun details(item: Item): String? {
        val link = item.link ?: return item.description
        detailsCache.get(link)?.let { return it ?: item.description }
        val text = runCatching { readable(http.fetchText(link)) }.getOrNull()
        detailsCache.put(link, text)
        // Страница не открылась — остаётся лид из ленты: он уже есть и обычно достаточен.
        return text ?: item.description
    }

    /**
     * Поиск новостей по теме: сначала Bing Новости (понимает любые слова и отдаёт лид),
     * затем Google Новости. Порядок тот же, что у города: Google в части сетей закрыт.
     */
    private suspend fun search(term: String, query: NewsQuery): List<Item> {
        val q = term.trim().encodeURLParameter()
        val urls = listOf(
            "https://www.bing.com/news/search?q=$q&format=RSS&setlang=ru&cc=${query.country}",
            "https://news.google.com/rss/search?q=$q&hl=ru&gl=${query.country}&ceid=${query.country}:ru",
        )
        for (url in urls) {
            try {
                val ua = if (url.contains("bing.com")) BROWSER_USER_AGENT else NOVA_USER_AGENT
                val items = parseRss(http.fetchText(url, ua))
                if (items.isNotEmpty()) return items
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ai.nova.core.util.Logger.w("News", "поиск новостей не ответил: $url (${e.message})")
            }
        }
        return emptyList()
    }

    private fun urls(topic: NewsTopic, query: NewsQuery): List<String> {
        val city = query.city?.takeIf { it.isNotBlank() && query.scope == NewsScope.CITY }
        // Bing Новости отдаёт RSS без ключей и находит новости по названию города; Google в части сетей закрыт.
        val bing = city?.let { c ->
            val q = (c.trim() + " " + topic.keyword).trim()
            "https://www.bing.com/news/search?q=${q.encodeURLParameter()}&format=RSS&setlang=ru&cc=${query.country}"
        }
        val google = when {
            city != null -> {
                val q = (city.trim() + " " + topic.keyword).trim()
                "https://news.google.com/rss/search?q=${q.encodeURLParameter()}&hl=ru&gl=${query.country}&ceid=${query.country}:ru"
            }
            topic.section != null -> "https://news.google.com/rss/headlines/section/topic/${topic.section}?hl=ru&gl=${query.country}&ceid=${query.country}:ru"
            query.scope == NewsScope.COUNTRY -> "https://news.google.com/rss/headlines/section/topic/NATION?hl=ru&gl=${query.country}&ceid=${query.country}:ru"
            else -> "https://news.google.com/rss?hl=ru&gl=${query.country}&ceid=${query.country}:ru"
        }
        // Для города сначала Bing: он единственный отвечает по городу там, где Google закрыт.
        return (if (bing != null) listOf(bing, google) else listOf(google)) + fallbackFeeds(topic, query)
    }

    /** Ленты-запас: если выбран регион «Страна», главные новости читаем из раздела «Россия». */
    private fun fallbackFeeds(topic: NewsTopic, query: NewsQuery): List<String> =
        if (query.scope == NewsScope.COUNTRY && topic == NewsTopic.MAIN) {
            listOf("https://lenta.ru/rss/news/russia") + topic.feeds
        } else topic.feeds

    companion object {
        private val ITEM = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private fun tag(name: String) = Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TITLE = tag("title")
        private val LINK = tag("link")
        private val SOURCE = tag("source")
        private val DESCRIPTION = tag("description")
        private val PUB_DATE = tag("pubDate")

        /** Bing отдаёт ссылки через свой переходник: вытаскиваем настоящий адрес статьи. */
        fun realLink(link: String): String? {
            if (!link.startsWith("http")) return null
            if (!link.contains("bing.com/news/apiclick")) return link
            return Regex("""[?&]url=([^&]+)""").find(link)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }

        fun parseRss(xml: String): List<Item> = ITEM.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val rawTitle = TITLE.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val source = SOURCE.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() }
            // Google Новости добавляют источник через « - Источник»: второй раз его не читаем.
            val title = source?.let { s -> rawTitle.removeSuffix(" - $s").removeSuffix(" — $s") } ?: rawTitle
            Item(
                title = title,
                link = LINK.find(body)?.groupValues?.get(1)?.let(::clean)?.let(::realLink),
                source = source,
                publishedAt = PUB_DATE.find(body)?.groupValues?.get(1)?.let(::clean)?.let(::parseDate),
                description = DESCRIPTION.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.length > 40 },
            )
        }.distinctBy { it.title }.toList()

        fun clean(s: String): String = s.trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&laquo;", "«").replace("&raquo;", "»").replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&pound;", "£").replace("&euro;", "€").replace("&hellip;", "…").replace("&quot;", "\"")
            // Числовые сущности вроде &#171; и &#8212; — иначе в заголовке остаётся код вместо знака.
            .replace(Regex("""&#(x?)([0-9A-Fa-f]{1,6});""")) { m ->
                val code = if (m.groupValues[1].isEmpty()) m.groupValues[2].toIntOrNull()
                else m.groupValues[2].toIntOrNull(16)
                code?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: ""
            }
            .replace(Regex("""\s+"""), " ").trim()

        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )

        /** «Mon, 25 Nov 2026 08:00:00 GMT» — для подписи «когда вышла». */
        fun parseDate(raw: String): java.time.Instant? {
            val m = Regex("""(\d{1,2})\s+(\p{L}{3})\p{L}*\s+(\d{4})\s+(\d{2}):(\d{2})""").find(raw) ?: return null
            val month = MONTHS[m.groupValues[2].lowercase()] ?: return null
            return runCatching {
                java.time.LocalDateTime.of(
                    m.groupValues[3].toInt(), month, m.groupValues[1].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                ).toInstant(java.time.ZoneOffset.UTC)
            }.getOrNull()
        }

        /** Текст статьи: абзацы без тегов, скриптов и меню — примерно первый экран. */
        fun readable(html: String, limit: Int = 600): String? {
            val body = html.replace(Regex("""<(script|style|noscript)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            val paragraphs = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(body).map { clean(it.groupValues[1]) }.filter { it.length >= 60 }.toList()
            val text = if (paragraphs.isNotEmpty()) paragraphs.joinToString(" ") else clean(body)
            if (text.length < 60) return null
            val cut = text.take(limit)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
            val out = if (end > limit / 2) cut.substring(0, end + 1) else cut.trimEnd() + "…"
            return out
        }

        /** Пункты ленты по кругу: 1-я рубрика, 2-я, снова 1-я… — в первой пятёрке видны все темы. */
        private fun interleave(lanes: List<List<Item>>): Sequence<Item> = sequence {
            var round = 0
            while (lanes.any { it.size > round }) {
                for (lane in lanes) lane.getOrNull(round)?.let { yield(it) }
                round++
            }
        }

        fun answer(title: String, items: List<Item>, from: Int = 0): String {
            if (items.isEmpty()) return if (from > 0) "Больше новостей нет." else "Свежих новостей не нашла."
            val head = if (from > 0) "Ещё новости:" else "$title:"
            return "$head\n" + items.mapIndexed { i, it -> "${from + i + 1}. ${it.title.trimEnd('.')}." }.joinToString("\n")
        }

        /** Фраза для «расскажи подробнее»: что это, откуда, когда и о чём. */
        fun detailsText(item: Item, summary: String?): String {
            val source = item.source?.let { " · $it" } ?: ""
            // «сегодня в 11:18», «вчера в 09:00», иначе дата: так понятнее, чем «25 ноябрь».
            val when_ = item.publishedAt
                ?.let { ai.nova.core.assistant.RuFormat.dateTime(it, java.time.ZoneId.systemDefault(), java.time.Instant.now()) }
                ?.let { " · $it" } ?: ""
            val head = "${item.title.trimEnd('.')}$source$when_."
            return if (summary.isNullOrBlank()) head else "$head\n\n$summary"
        }
    }
}

/** Короткая справка из Википедии: «кто такой Гагарин», «что такое фотосинтез». */
class WikiService(private val http: HttpClient) {
    data class Article(val title: String, val extract: String, val url: String)

    private val cache = TtlCache<String, Article?>(6 * 3600_000L)
    private val fullCache = TtlCache<String, Article?>(6 * 3600_000L)
    private val recipeCache = TtlCache<String, Article?>(12 * 3600_000L)

    suspend fun lookup(query: String): Article? {
        val key = query.lowercase().trim()
        if (cache.contains(key)) return cache.get(key)
        // Сначала прямое совпадение названия (с перенаправлениями), затем полнотекстовый поиск.
        val direct = extract(query.replaceFirstChar { it.uppercase() })
        // Результат поиска берём, только если заголовок похож на вопрос: иначе прочитаем чужую статью.
        val article = direct ?: searchTitle(query)?.takeIf { similarTitle(it, query) }?.let { extract(it) }
        cache.put(key, article)
        return article
    }

    /** Полный текст статьи — для «найди … и запиши в заметки». */
    suspend fun article(query: String): Article? {
        val key = query.lowercase().trim()
        if (fullCache.contains(key)) return fullCache.get(key)
        val direct = extractFull(query.replaceFirstChar { it.uppercase() })
        val found = direct ?: searchTitle(query)?.takeIf { similarTitle(it, query) }?.let { extractFull(it) }
        fullCache.put(key, found)
        return found
    }

    /**
     * Рецепт блюда: раздел «Приготовление» статьи Википедии, а если его нет — страница Викиучебника.
     * Понимает «рецепт борща», «как приготовить борщ», «борщ рецепт».
     */
    suspend fun recipe(query: String): Article? {
        val dish = dishName(query)
        if (dish.isEmpty()) return null
        val key = "recipe:" + dish.lowercase()
        if (recipeCache.contains(key)) return recipeCache.get(key)
        val found = wikipediaRecipe(dish) ?: wikibooksRecipe(dish)
        recipeCache.put(key, found)
        return found
    }

    private fun similarTitle(title: String, query: String): Boolean {
        fun stems(s: String) = s.lowercase().replace('ё', 'е').split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.map { it.take(maxOf(3, it.length - 3)) }
        val t = stems(title)
        return stems(query).any { q -> t.any { it.startsWith(q) || q.startsWith(it) } }
    }

    private suspend fun searchTitle(query: String): String? = searchTitleAt("ru.wikipedia.org", query)

    private suspend fun searchTitleAt(site: String, query: String): String? {
        val url = "https://$site/w/api.php?action=query&list=search&srsearch=${query.encodeURLParameter()}&srlimit=3&format=json&utf8=1"
        return parseSearch(http.fetchText(url)).firstOrNull()
    }

    private suspend fun extract(title: String): Article? {
        val url = "https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=4&redirects=1" +
            "&titles=${title.encodeURLParameter()}&format=json&utf8=1"
        return parseExtract(http.fetchText(url))
    }

    private suspend fun extractFull(title: String): Article? {
        val url = "https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1" +
            "&titles=${title.encodeURLParameter()}&format=json&utf8=1"
        return parseFull(http.fetchText(url))
    }

    private suspend fun wikipediaRecipe(dish: String): Article? {
        val art = article(dish) ?: return null
        val section = recipeSection(art.extract) ?: return null
        return Article(art.title, section.take(MAX_RECIPE), art.url)
    }

    /** В Викиучебнике рецепт лежит прямо на странице блюда (шаблоны в extract не попадают — читаем викитекст). */
    private suspend fun wikibooksRecipe(dish: String): Article? {
        val titles = listOf(dish.replaceFirstChar { it.uppercase() }, "Рецепт $dish")
        for (title in titles) {
            val url = "https://ru.wikibooks.org/w/index.php?title=${title.replace(' ', '_').encodeURLParameter()}&action=raw"
            val raw = runCatching { http.fetchText(url) }.getOrNull()?.takeIf { it.length > 40 } ?: continue
            if (Regex("""(?i)^\s*#(?:перенаправление|redirect)""").containsMatchIn(raw)) continue
            val section = recipeSection(raw) ?: raw
            val text = cleanWikitext(section).take(MAX_RECIPE)
            if (text.length < 80) continue
            return Article(title, text, "https://ru.wikibooks.org/wiki/" + title.replace(' ', '_').encodeURLParameter())
        }
        return null
    }

    companion object {
        /** Длинная заметка не должна превращаться в целую книгу. */
        const val MAX_RECIPE = 6000

        /** «рецепт борща» → «борщ», «как приготовить борщ» → «борщ», «борщ рецепт» → «борщ». */
        fun dishName(query: String): String = query
            .replace(Regex("""(?iu)^\s*(?:а\s+)?(?:найди|поищи|найти|поискать|подскажи|дай|пришли)\s+(?:мне\s+)?(?:пожалуйста\s+)?"""), "")
            .replace(Regex("""(?iu)^\s*(?:как\s+(?:приготовить|сварить|испечь|сделать|готовить)|как\s+готовится)\s+"""), "")
            .replace(Regex("""(?iu)^\s*рецепт\w*\s+"""), "")
            .replace(Regex("""(?iu)\s+рецепт\w*\s*$"""), "")
            .replace(Regex("""(?iu)\s+(?:в\s+(?:заметки|заметку|блокнот))\s*$"""), "")
            .trim().trim('.', ',', '!', '?', ':', '«', '»')

        /**
         * Раздел о приготовлении из текста статьи (в викитексте и в plaintext заголовки одинаковые: «== Приготовление ==»).
         * Берём до двух подходящих разделов — обычно «Ингредиенты» и «Приготовление».
         */
        fun recipeSection(text: String): String? {
            val heads = Regex("""(?m)^(={2,6})\s*([^=\n]{2,80}?)\s*\1\s*$""").findAll(text).toList()
            // «Приготовление» и «Рецепт» важнее общих рассуждений из «Технологии приготовления»,
            // а состав/ингредиенты — важнее технологии.
            fun rank(title: String): Int = when {
                Regex("""(?iu)^\s*(?:приготовление|рецепт|рецепт\s+приготовления|способ\s+приготовления)\s*$""").containsMatchIn(title) -> 0
                Regex("""(?iu)ингредиент|состав""").containsMatchIn(title) -> 1
                Regex("""(?iu)приготовлен|как\s+готовить|технолог|кулинар""").containsMatchIn(title) -> 2
                else -> 99
            }
            val picked = heads.map { it to rank(it.groupValues[2]) }.filter { it.second < 99 }
                .sortedWith(compareBy({ it.second }, { it.first.range.first }))
                .take(2)
                .sortedBy { it.first.range.first }
            val parts = picked.map { (h, _) ->
                val next = heads.firstOrNull { it.range.first > h.range.first }
                val body = text.substring(h.range.last + 1, next?.range?.first ?: text.length).trim()
                h.groupValues[2].replaceFirstChar { it.uppercase() } + ":\n" + body
            }.filter { it.substringAfter(":\n").trim().length > 60 }
            return parts.joinToString("\n\n").takeIf { it.isNotEmpty() }
        }

        /** Викитекст → читаемый текст: шаблоны, ссылки, таблицы и разметка убираются. */
        fun cleanWikitext(raw: String): String {
            var t = raw.replace(Regex("""(?s)<!--.*?-->"""), "")
            repeat(3) { t = t.replace(Regex("""(?s)\{\{[^{}]*}}"""), "") }
            t = t.replace(Regex("""(?s)\{\|.*?\|}"""), "")
            t = t.replace(Regex("""\[\[([^\]|]*)\|([^\]]+)]]"""), "$2")
            t = t.replace(Regex("""\[\[([^\]]+)]]"""), "$1")
            t = t.replace(Regex("""\[https?://\S+\s+([^\]]+)]]"""), "$1")
            t = t.replace(Regex("""'{2,}"""), "")
            t = t.replace(Regex("""<[^>]+>"""), "")
            t = t.replace(Regex("""(?m)^=+\s*(.+?)\s*=+\s*$"""), "$1:")
            t = t.replace(Regex("""(?m)^\s*[*#]+\s*"""), "• ")
            t = t.replace(Regex("""(?m)^\s*[|!].*$"""), "")
            t = t.replace(Regex("""[ \t]+"""), " ").replace(Regex("""\n{3,}"""), "\n\n")
            return t.trim()
        }

        fun parseSearch(json: String): List<String> {
            val q = NovaJson.parseToJsonElement(json).jsonObject["query"] as? JsonObject ?: return emptyList()
            return (q["search"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("title") } ?: emptyList()
        }

        fun parseExtract(json: String): Article? = parse(json) { shorten(it) }

        /** Полный текст статьи без обрезки. */
        fun parseFull(json: String): Article? = parse(json) { it }

        private fun parse(json: String, transform: (String) -> String): Article? {
            val q = NovaJson.parseToJsonElement(json).jsonObject["query"] as? JsonObject ?: return null
            val pages = q["pages"] as? JsonObject ?: return null
            val page = pages.values.firstOrNull() as? JsonObject ?: return null
            if (page.containsKey("missing")) return null
            val title = page.text("title") ?: return null
            val text = page.text("extract")?.replace("́", "")?.let(transform)?.takeIf { it.length > 20 } ?: return null
            // Страница-неоднозначность («Меркурий — может означать…») бесполезна для ответа.
            if (Regex("""(?:может означать|может относиться|может иметь значения)""").containsMatchIn(text.take(200))) return null
            return Article(title, text, "https://ru.wikipedia.org/wiki/" + title.replace(' ', '_').encodeURLParameter())
        }

        /** Убирает ударения, скобки с датами и произношением, оставляет 2–3 предложения. */
        fun shorten(extract: String, maxChars: Int = 420): String {
            var t = extract.replace("́", "").replace(Regex("""\n+"""), " ")
            // Вложенные скобки снимаем изнутри наружу.
            repeat(3) { t = t.replace(Regex("""\s*\([^()]*\)"""), "") }
            t = t.replace(Regex("""\s+"""), " ").replace(Regex("""\s+([,.;:])"""), "$1").trim()
            val sentences = Regex("""(?<=[.!?])\s+(?=[А-ЯЁA-Z0-9«])""").split(t)
            val sb = StringBuilder()
            for (s in sentences) {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > maxChars) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
            return sb.toString().ifEmpty { t.take(maxChars) }
        }
    }
}
