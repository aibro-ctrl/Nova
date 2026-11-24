package ai.nova.core.review

import ai.nova.core.assistant.RuFormat
import ai.nova.core.domain.ConversationRepository
import ai.nova.core.model.MessageRole
import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.nlp.Rx
import ai.nova.core.nlp.TextAnalysis
import ai.nova.core.util.TimeSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** «Что я говорила про ремонт на прошлой неделе», «о чём мы говорили вчера» — поиск по истории разговора. */
class ChatSearch(private val conversations: ConversationRepository, private val time: TimeSource) {
    data class Ask(val query: String?, val from: LocalDate?, val to: LocalDate?, val topic: Topic? = null)

    /**
     * О чём именно спрашивали: «какие маршруты я искал вчера». Тема узнаётся по словам в прошлых
     * просьбах — человеку не нужно помнить формулировку, а Нове не нужно его переспрашивать.
     */
    enum class Topic(val phrase: String, private val markers: List<String>, private val subject: Regex?) {
        ROUTES("маршруты", listOf("маршрут", "проложи", "добраться", "доехать", "как доехать", "навигац", "путь до"), Rx.of("""(?:до|в|во|к|на)\s+(.+)$""")),
        RECIPES("рецепты", listOf("рецепт", "приготов", "сварить", "испечь"), Rx.of("""рецепт\S*\s+(.+)$""")),
        MUSIC("песни", listOf("песн", "трек", "музык", "групп", "исполнител", "альбом"), Rx.of("""(?:песню|песни|трек|музыку|группу|исполнителя|альбом)\s+(.+)$""")),
        VIDEO("видео", listOf("видео", "фильм", "сериал", "ютуб", "мультик"), Rx.of("""(?:видео|фильм|сериал|мультик)\s+(.+)$""")),
        SEARCH("в интернете", listOf("в интернете", "загугли", "поиск", "нашла в"), Rx.of("""(?:в интернете|загугли|поищи|найди|поиск)\s+(.+)$""")),
        WEATHER("погоду", listOf("погод"), null),
        SHOPPING("покупки", listOf("покупк", "в список", "купить"), null),
        SPENDINGS("расходы", listOf("расход", "потрат", "потратил", "траты"), null),
        NOTES("заметки", listOf("заметк", "запиши"), null),
        REMINDERS("напоминания", listOf("напомн"), null);

        fun matches(normalized: String): Boolean = markers.any { normalized.contains(it) }

        /** Что именно искали — «аптека» из «построй маршрут до аптеки»; иначе сама фраза. */
        fun what(normalized: String): String {
            val found = subject?.find(normalized)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return found.ifEmpty { normalized }.trim().trimEnd('.', ',', '!', '?').take(60)
        }

        companion object {
            /** Тема по слову из вопроса: «маршруты» → [ROUTES]; null — такого слова не знаем. */
            fun of(word: String): Topic? {
                val w = RuTokenizer.normalize(word).trim()
                if (w.isEmpty()) return null
                return entries.firstOrNull { t -> t.markers.any { w.length >= 4 && (it.startsWith(w.take(4)) || w.startsWith(it.take(4))) } }
            }
        }
    }

    companion object {
        private const val PERIOD = """(вчера|сегодня|позавчера|на\s+прошлой\s+неделе|на\s+этой\s+неделе|на\s+неделе|за\s+неделю|в\s+этом\s+месяце|в\s+прошлом\s+месяце|за\s+месяц|недавно)"""
        private val ABOUT = Rx.of("""^(?:а\s+)?(?:что|когда)\s+(?:я\s+)?(?:тебе\s+)?(?:говорил|говорила|говорили|рассказывал|рассказывала|спрашивал|спрашивала|писал|писала|упоминал|упоминала)\s+(?:тебе\s+)?(?:про|о|об|насчет|насчёт)\s+(.+?)(?:\s+$PERIOD)?$""")
        /** Период стоит между глаголом и темой: «о чём я говорил вчера насчёт здоровья». */
        private val ABOUT_TOPIC_AFTER_PERIOD = Rx.of("""^(?:о\s+ч[её]м|про\s+что)\s+(?:я\s+)?(?:тебе\s+)?(?:говорил|говорила|рассказывал|рассказывала|спрашивал|спрашивала)\s+$PERIOD\s+(?:про|о|об|насчет|насчёт)\s+(.+?)$""")
        /** «О чём я говорил насчёт здоровья» — без периода вовсе. */
        private val ABOUT_TOPIC = Rx.of("""^(?:о\s+ч[её]м|про\s+что)\s+(?:я\s+)?(?:тебе\s+)?(?:говорил|говорила|рассказывал|рассказывала|спрашивал|спрашивала)\s+(?:про|о|об|насчет|насчёт)\s+(.+?)(?:\s+$PERIOD)?$""")
        private val FIND = Rx.of("""^(?:найди|поищи|покажи)\s+(?:в\s+)?(?:наших\s+)?(?:разговорах|переписке|истории\s+(?:чата|разговоров)|чате|диалогах)\s+(?:про\s+|о\s+|об\s+)?(.+?)(?:\s+$PERIOD)?$""")
        private val TALKED = Rx.of("""^(?:о\s+ч[её]м|про\s+что)\s+мы\s+(?:с\s+тобой\s+)?(?:говорили|разговаривали|болтали)\s+$PERIOD$""")

        /** «Какие маршруты я искал вчера», «какой рецепт я спрашивала на неделе». */
        private val TOPIC = Rx.of(
            """^(?:какие|какой|какую|каких|что|куда|где)\s+(.+?)\s+(?:я\s+)?(?:искал|искала|спрашивал|спрашивала|строил|строила|смотрел|смотрела|просил|просила|запрашивал|запрашивала)\s*(?:$PERIOD)?$""",
        )
        /** Без темы: «что я искал вчера», «куда я строил на неделе» — все просьбы за период. */
        private val TOPIC_ANY = Rx.of(
            """^(?:что|куда|где)\s+я\s+(?:искал|искала|спрашивал|спрашивала|строил|строила|смотрел|смотрела)\s*(?:$PERIOD)?$""",
        )

        fun parse(text: String, today: LocalDate): Ask? {
            val n = RuTokenizer.normalize(text).trim().trimEnd('?', '.', '!')
            TOPIC.find(n)?.let { m ->
                val word = m.groupValues[1]
                val topic = Topic.of(word)
                // Слово не похоже на тему — ищем его как обычный текст в разговорах.
                return if (topic != null) period(m.groupValues[2], today).let { Ask(null, it.first, it.second, topic) }
                else withPeriod(word, m.groupValues[2], today)
            }
            TOPIC_ANY.find(n)?.let { m -> return period(m.groupValues[1], today).let { Ask(null, it.first, it.second) } }
            ABOUT.find(n)?.let { m -> return withPeriod(m.groupValues[1], m.groupValues[2], today) }
            ABOUT_TOPIC_AFTER_PERIOD.find(n)?.let { m -> return withPeriod(m.groupValues[2], m.groupValues[1], today) }
            ABOUT_TOPIC.find(n)?.let { m -> return withPeriod(m.groupValues[1], m.groupValues[2], today) }
            FIND.find(n)?.let { m -> return withPeriod(m.groupValues[1], m.groupValues[2], today) }
            TALKED.find(n)?.let { m -> return withPeriod(null, m.groupValues[1], today) }
            return null
        }

        private fun withPeriod(query: String?, period: String, today: LocalDate): Ask {
            val (from, to) = bounds(period, today)
            return Ask(query?.trim()?.takeIf { it.isNotEmpty() }, from, to)
        }

        private fun period(period: String, today: LocalDate): Pair<LocalDate?, LocalDate?> = bounds(period, today)

        private fun bounds(period: String, today: LocalDate): Pair<LocalDate?, LocalDate?> {
            val p = period.replace(Regex("""\s+"""), " ")
            return when {
                p == "сегодня" -> today to today
                p == "вчера" -> today.minusDays(1) to today.minusDays(1)
                p == "позавчера" -> today.minusDays(2) to today.minusDays(2)
                p == "на прошлой неделе" -> today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
                p == "на этой неделе" || p == "на неделе" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
                p == "за неделю" || p == "недавно" -> today.minusDays(6) to today
                p == "в этом месяце" -> today.withDayOfMonth(1) to today
                p == "в прошлом месяце" -> today.minusMonths(1).withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
                p == "за месяц" -> today.minusDays(29) to today
                else -> null to null
            }
        }
    }

    suspend fun search(ask: Ask): String {
        val zone = time.zone()
        val today = time.today()
        val all = conversations.recent(5000).filter { it.role == MessageRole.USER }
            .filter { m ->
                val d = m.createdAt.atZone(zone).toLocalDate()
                (ask.from == null || !d.isBefore(ask.from)) && (ask.to == null || !d.isAfter(ask.to))
            }
        val period = when {
            ask.from == null -> ""
            ask.from == ask.to -> " " + RuFormat.date(ask.from, today).removePrefix("в ")
            else -> " с ${ask.from.dayOfMonth}.${"%02d".format(ask.from.monthValue)} по ${ask.to!!.dayOfMonth}.${"%02d".format(ask.to.monthValue)}"
        }
        ask.topic?.let { return digest(it, ask, all, zone, today, period) }
        val hits = if (ask.query == null) all.sortedByDescending { it.createdAt }.take(8) else {
            val want = TextAnalysis.stems(ask.query)
            if (want.isEmpty()) return "Про что искать? Скажите, например: «что я говорила про ремонт»."
            all.map { m ->
                val have = TextAnalysis.stems(m.content)
                m to want.count { w -> have.any { TextAnalysis.stemSimilarity(w, it) >= 0.75 } }
            }.filter { it.second > 0 && (want.size == 1 || it.second * 2 >= want.size) }
                .sortedWith(compareByDescending<Pair<ai.nova.core.model.ConversationMessage, Int>> { it.second }.thenByDescending { it.first.createdAt })
                .map { it.first }.take(5)
        }
        if (hits.isEmpty()) {
            return if (ask.query == null) "Разговоров$period не нашла." else "Не нашла в разговорах$period ничего про ${RuFormat.quote(ask.query)}."
        }
        val head = if (ask.query == null) "Вы говорили$period:" else "Про ${RuFormat.quote(ask.query)} вы говорили$period:"
        return head + "\n" + hits.sortedBy { it.createdAt }.joinToString("\n") { m ->
            val d = m.createdAt.atZone(zone)
            val day = RuFormat.date(d.toLocalDate(), today).removePrefix("в ")
            "• $day в ${RuFormat.time(d.toLocalTime())}: ${RuFormat.quote(m.content.take(120))}"
        }
    }

    /**
     * Сводка по теме: «какие маршруты я искал вчера» → один ответ со списком маршрутов.
     * Повторы схлопываем (одна и та же аптека дважды — одна строка), иначе список читать невозможно.
     */
    private fun digest(
        topic: Topic,
        ask: Ask,
        all: List<ai.nova.core.model.ConversationMessage>,
        zone: ZoneId,
        today: LocalDate,
        period: String,
    ): String {
        val hits = all.map { it to topic.what(RuTokenizer.normalize(it.content)) }
            .filter { topic.matches(RuTokenizer.normalize(it.first.content)) }
            .sortedBy { it.first.createdAt }
        if (hits.isEmpty()) return "Про ${topic.phrase}$period ничего не нашла."
        // Один и тот же предмет называется по-разному («аптека» и «аптеку») — сравниваем основы слов.
        val seen = LinkedHashMap<String, Triple<ai.nova.core.model.ConversationMessage, String, Int>>()
        for ((m, what) in hits) {
            val key = TextAnalysis.stems(what).joinToString(" ")
            val prev = seen[key]
            seen[key] = Triple(prev?.first ?: m, what, (prev?.third ?: 0) + 1)
        }
        val head = "Вы искали ${topic.phrase}$period:"
        val lines = seen.values.take(6).joinToString("\n") { (m, what, count) ->
            val d = m.createdAt.atZone(zone)
            val day = if (ask.from != ask.to) RuFormat.date(d.toLocalDate(), today).removePrefix("в ") + " " else ""
            val times = if (count > 1) " — ${RuFormat.count(count, "раз", "раза", "раз")}" else ""
            "• $day${RuFormat.time(d.toLocalTime())} — ${what.replaceFirstChar { it.uppercase() }}$times"
        }
        val tail = if (seen.size > 6) "\n…и ещё ${seen.size - 6}." else ""
        return head + "\n" + lines + tail
    }
}
