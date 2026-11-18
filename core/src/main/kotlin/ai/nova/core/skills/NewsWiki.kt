package ai.nova.core.skills

import ai.nova.core.data.NovaJson
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Рубрика новостей. */
enum class NewsTopic(val title: String, val feeds: List<String>) {
    MAIN("Главные новости", listOf("https://lenta.ru/rss/news", "https://tass.ru/rss/v2.xml", "https://ria.ru/export/rss2/archive/index.xml")),
    SPORT("Новости спорта", listOf("https://lenta.ru/rss/news/sport")),
    SCIENCE("Новости науки и техники", listOf("https://lenta.ru/rss/news/science", "https://habr.com/ru/rss/news/?fl=ru")),
    TECH("Новости технологий", listOf("https://habr.com/ru/rss/news/?fl=ru", "https://lenta.ru/rss/news/science")),
    ECONOMY("Новости экономики", listOf("https://lenta.ru/rss/news/economics", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss")),
}

/** Заголовки новостей из RSS (Лента, ТАСС, РИА, Хабр, РБК) — без ключей и регистрации. */
class NewsService(private val http: HttpClient) {
    data class Item(val title: String, val link: String?)

    private val cache = TtlCache<NewsTopic, List<Item>>(10 * 60_000L)

    /** Лента, из которой пришли заголовки: если рубрика недоступна — это главные новости. */
    data class Feed(val topic: NewsTopic, val items: List<Item>)

    suspend fun headlines(topic: NewsTopic, limit: Int = 5): List<Item> = feed(topic).items.take(limit)

    /** Все заголовки рубрики (для «ещё»); рубрика не ответила — главные новости, и кэш под ними же. */
    suspend fun feed(topic: NewsTopic): Feed {
        cache.get(topic)?.let { return Feed(topic, it) }
        var lastError: Exception? = null
        for (t in listOf(topic, NewsTopic.MAIN).distinct()) {
            if (t != topic) cache.get(t)?.let { return Feed(t, it) }
            for (url in t.feeds) {
                try {
                    val items = parseRss(http.fetchText(url))
                    if (items.isNotEmpty()) { cache.put(t, items); return Feed(t, items) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e as? InfoUnavailable ?: InfoUnavailable("лента новостей не читается")
                }
            }
        }
        throw lastError ?: InfoUnavailable("новостей нет")
    }

    companion object {
        private val ITEM = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private fun tag(name: String) = Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TITLE = tag("title")
        private val LINK = tag("link")

        fun parseRss(xml: String): List<Item> = ITEM.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val title = TITLE.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(title, LINK.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.startsWith("http") })
        }.distinctBy { it.title }.toList()

        fun clean(s: String): String = s.trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&laquo;", "«").replace("&raquo;", "»").replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ").trim()

        fun answer(topic: NewsTopic, items: List<Item>, asked: NewsTopic = topic, from: Int = 0): String {
            if (items.isEmpty()) return if (from > 0) "Больше новостей нет." else "Свежих новостей не нашла."
            val head = when {
                asked != topic -> "${asked.title} сейчас недоступны, вот главные:"
                from > 0 -> "Ещё новости:"
                else -> "${topic.title}:"
            }
            return "$head\n" + items.mapIndexed { i, it -> "${from + i + 1}. ${it.title.trimEnd('.')}." }.joinToString("\n")
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
