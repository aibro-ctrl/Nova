package ai.nova.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * Распознавание речи — отдельный слой, не связанный с бизнес-логикой.
 * Реализации: системный Android SpeechRecognizer, офлайн Vosk; в будущем — облачные STT или Windows Speech.
 */
interface SpeechRecognitionProvider {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean
    /** Холодный поток событий одной сессии распознавания. Отмена сбора — остановка микрофона. */
    fun listen(options: ListenOptions = ListenOptions()): Flow<SpeechEvent>
}

data class ListenOptions(
    val languageTag: String = "ru-RU",
    val preferOffline: Boolean = false,
    val partialResults: Boolean = true,
    /** Сколько тишины считать концом фразы. */
    val silenceMillis: Long = 2000,
    /** Слова-подсказки распознавателю: имя ассистента, частые команды. */
    val biasing: List<String> = emptyList(),
)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Уровень громкости 0..1 — для анимации индикатора. */
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    /** [alternatives] — другие варианты услышанного (от лучшего к худшему), если сервис их даёт. */
    data class Final(val text: String, val alternatives: List<String> = emptyList()) : SpeechEvent
    data object EndOfSpeech : SpeechEvent
    data class Error(val kind: SpeechError, val message: String) : SpeechEvent
}

enum class SpeechError { NO_MATCH, TIMEOUT, NO_PERMISSION, NETWORK, BUSY, UNAVAILABLE, AUDIO, OTHER }

/** Синтез речи — заменяемый голосовой движок. */
interface TextToSpeechProvider {
    val isReady: Boolean
    /** Проговаривает текст и возвращается, когда речь закончена (или прервана). */
    suspend fun speak(text: String)
    /** Озвучить на другом языке (перевод). [language] — ISO-код; null — как обычно. */
    suspend fun speakIn(text: String, language: String?) = speak(text)
    fun stop()
    fun shutdown()
}

/** Очищает ответ перед озвучиванием: убирает маркеры списков и эмодзи, сокращает длинные списки. */
object SpeechText {
    /**
     * Текст для озвучивания: разметка убрана, пункты соединены в предложения.
     * Длина не режется — синтезатор читает ответ целиком (длинный текст режется на части по предложениям).
     */
    fun forSpeech(text: String): String {
        val lines = clean(text).lines().map { it.trim().removePrefix("•").removePrefix("✓").removePrefix("!").trim() }.filter { it.isNotEmpty() }
        val sb = StringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append(if (lines[i - 1].last() in ".:!?…;") " " else ". ")
            sb.append(listNumber(line))
        }
        val spoken = sayNumbers(declineUnits(pronounce(sb.toString().replace(Regex("[«»]"), ""))))
            .replace(Regex("""\s{2,}"""), " ").trim()
        // Первое слово — с заглавной: после замены числа на слово («2026» → «две тысячи…») регистр теряется.
        return spoken.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
    }

    /** Склоняемые единицы: род нужен числительному, иначе «1 минут» вместо «одна минута». */
    private val FEMININE_UNITS = setOf(
        "минута", "минуты", "минут", "секунда", "секунды", "секунд", "неделя", "недели", "недель",
        "штука", "штуки", "штук", "порция", "порции", "порций", "копейка", "копейки", "копеек",
        "чашка", "чашки", "чашек", "ложка", "ложки", "ложек", "страница", "страницы", "страниц",
        "калория", "калории", "калорий", "тысяча", "тысячи", "тысяч", "половина", "половины",
        "порция", "таблетка", "таблетки", "таблеток", "тренировка", "тренировки", "тренировок",
        "задача", "задачи", "задач", "встреча", "встречи", "встреч", "заметка", "заметки", "заметок",
        "идея", "идеи", "идей", "ошибка", "ошибки", "ошибок", "покупка", "покупки", "покупок",
    )

    /** Женские слова в винительном падеже: «одну чайную ложку», «одну минуту» — а не «одна». */
    private val FEMININE_ACCUSATIVE = setOf(
        "минуту", "секунду", "неделю", "штуку", "порцию", "копейку", "чашку", "ложку", "страницу",
        "калорию", "тысячу", "половину", "таблетку", "тренировку", "задачу", "встречу", "заметку",
        "идею", "ошибку", "покупку", "порцию",
    )

    /** Предлоги и сравнительные слова, после которых число стоит в родительном падеже. */
    private val GENITIVE_AFTER = setOf(
        "около", "более", "менее", "свыше", "порядка", "больше", "меньше", "от", "до", "свыше",
        "достигает", "достигло", "достиг", "превышает", "превысило", "превысил", "хватает",
    )

    /** Пункт списка: «1. Нарежьте лук» → «Первый. Нарежьте лук». Номера вслух не читаются как «один». */
    private fun listNumber(line: String): String =
        Regex("""^(\d{1,2})[.)]\s+""").replace(line) { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (n !in 1..31) return@replace m.value
            val ordinal = RuNumbers.ordinal(n.toLong(), RuNumbers.Case.NOMINATIVE)
            val rest = line.substring(m.range.last + 1)
            // «1. Первый» — номер уже назван словом, второй раз не читаем.
            if (rest.lowercase().startsWith(ordinal)) "" else ordinal.replaceFirstChar { it.uppercase() } + ". "
        }

    private val MONTHS = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /**
     * Числа словами: так их верно читает любой движок, и род/падеж не приходится угадывать синтезатору.
     * «1,5 кг» → «одна целая и пять десятых килограмма», «в 2026 году» → «в две тысячи двадцать шестом году»,
     * «20 ноября» → «двадцатого ноября», «+7 916 123-45-67» → цифрами по одной.
     *
     * Формулы и коды («3:0», «1.1.1», «v2.20.2») не трогаем: числа в них — часть обозначения.
     */
    fun sayNumbers(text: String): String {
        var t = text
        // Телефон: цифры по одной — так его диктуют и так его узнают.
        t = Regex("""(?<![\d+])\+?\d[\d ()-]{7,}\d(?![\d])""").replace(t) { m ->
            val value = m.value.trim()
            val digits = value.filter(Char::isDigit)
            val looksPhone = value.startsWith("+") || value.any { it == '(' || it == '-' } ||
                (digits.length in 10..15 && value.count { it == ' ' } >= 2)
            if (!looksPhone) value else {
                val plus = if (value.startsWith("+")) "плюс " else ""
                plus + digits.map { DIGITS[it - '0'] }.joinToString(" ")
            }
        }
        // Десятичная дробь с точкой: «1.5» → «1,5» (версии и даты не трогаем — там две точки).
        t = Regex("""(?<![\d.])(\d{1,4})\.(\d{1,2})(?![\d.])""").replace(t) { m -> "${m.groupValues[1]},${m.groupValues[2]}" }
        // Дата числом: «20.11.2026» → «двадцатое ноября две тысячи двадцать шестого года».
        t = Regex("""(?<![\d.])(\d{1,2})\.(\d{1,2})\.(\d{4})(?![\d.])""").replace(t) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val month = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val year = m.groupValues[3].toLongOrNull() ?: return@replace m.value
            if (day !in 1..31 || month !in 1..12) return@replace m.value
            "${RuNumbers.ordinal(day.toLong(), RuNumbers.Case.GENITIVE)} ${MONTHS[month - 1]} " +
                RuNumbers.ordinal(year, RuNumbers.Case.GENITIVE) + " года"
        }
        // День и месяц: «20 ноября» → «двадцатого ноября».
        for ((i, month) in MONTHS.withIndex()) {
            t = Regex("""(?<![\d.])(\d{1,2})\s+$month(?![\p{L}])""").replace(t) { m ->
                val day = m.groupValues[1].toIntOrNull()
                if (day == null || day !in 1..31) m.value
                else "${RuNumbers.ordinal(day.toLong(), RuNumbers.Case.GENITIVE)} $month"
            }
        }
        // Год: «в 2026 году» → «в две тысячи двадцать шестом году».
        t = Regex("""(?<![\d.])(1[89]\d\d|20\d\d|21\d\d)\s+(году|года|год|годом|годах|г\.)(?![\p{L}])""").replace(t) { m ->
            val year = m.groupValues[1].toLong()
            val word = m.groupValues[2]
            val case = when (word) {
                "году", "годах", "г." -> RuNumbers.Case.PREPOSITIONAL
                "года", "годом" -> RuNumbers.Case.GENITIVE
                else -> RuNumbers.Case.NOMINATIVE
            }
            RuNumbers.ordinal(year, case) + " " + if (word == "г.") "году" else word
        }
        // Остальные числа — словами, род берём у следующего слова (минута — она, килограмм — он).
        t = Regex("""(?<![\d.,:])(\d{1,3}(?:[  ]\d{3})+|\d+)(?:,(\d+))?(?![\d.,]\d|:)""").replace(t) { m ->
            val raw = m.groupValues[1].replace(" ", "").replace(" ", "")
            val n = raw.toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            if (frac.isNotEmpty()) return@replace RuNumbers.fraction(n, frac)
            // «2 столовые ложки»: род задаёт существительное за прилагательным, поэтому смотрим два слова вперёд.
            val after = t.substring(m.range.last + 1).trimStart().split(' ').take(2)
                .map { it.lowercase().trim('.', ',', '!', '?', ':', ';') }
            val prev = t.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase().trim('.', ',', '!', '?', ':', ';')
            val gender = if (after.any { it in FEMININE_UNITS || it in FEMININE_ACCUSATIVE } || prev in FEMININE_UNITS)
                RuNumbers.Gender.FEMININE else RuNumbers.Gender.MASCULINE
            // «около 5 км» и «более 20 минут» требуют родительного падежа: «около пяти километров».
            val words = when {
                prev in GENITIVE_AFTER -> RuNumbers.cardinalGenitive(n, gender)
                // «1 чайную ложку», «1 минуту» — винительный падеж: «одну», а не «одна».
                n == 1L && gender == RuNumbers.Gender.FEMININE && after.firstOrNull()?.let {
                    it.endsWith("ую") || it.endsWith("юю") || it in FEMININE_ACCUSATIVE
                } == true -> "одну"
                else -> RuNumbers.cardinal(n, gender)
            }
            // Число в начале фразы — с заглавной буквы, как и всё предложение.
            val head = t.substring(0, m.range.first).trimEnd()
            if (head.isEmpty() || head.last() in ".!?:…\n") words.replaceFirstChar { it.uppercase() } else words
        }
        return t
    }

    private val DIGITS = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

    /**
     * Режет длинный текст на куски не длиннее [max] символов по границам предложений:
     * системный синтезатор Android не принимает строку целиком (около 4000 символов).
     */
    fun chunks(text: String, max: Int): List<String> {
        if (max < 100 || text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '…', '\n'))
            if (cut < max / 2) cut = window.lastIndexOf(' ')
            if (cut < max / 2) cut = max - 1
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Разметка из ответов AI и символы, которые синтезатор читает вслух или спотыкается: **, #, `, ссылки, эмодзи. */
    fun clean(text: String): String = text
        .replace(Regex("""\[([^\]]+)]\((?:https?://)?[^)]+\)"""), "$1")
        .replace(Regex("""https?://\S+"""), "ссылка")
        .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        // Нумерованный список оставляем с номерами (шаги рецепта), маркеры «-», «*» убираем; «- 5°» — это минус, не маркер.
        .replace(Regex("""(?m)^\s*(\d+)[.)]\s+"""), "$1. ")
        .replace(Regex("""(?m)^\s*[-*+]\s+(?!\d+(?:,\d+)?\s?°)"""), "• ")
        .replace(Regex("""(?m)^\s*-\s+(?=\d+(?:,\d+)?\s?°)"""), "-")
        // Разметка — только парная: «2*3», «snake_case» и «~5 км» остаются по смыслу.
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""__(.+?)__"""), "$1").replace(Regex("""~~(.+?)~~"""), "$1")
        .replace(Regex("""`{1,3}([^`]*)`{1,3}"""), "$1")
        .replace(Regex("""(?<![\p{L}\d*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\p{L}\d*])"""), "$1")
        .replace(Regex("""(\d)\s*[*×]\s*(\d)"""), "$1 умножить на $2")
        .replace(Regex("""(?<![\p{L}\d])~\s?(?=\d)"""), "около ")
        .replace(Regex("""(?m)^\s*[-*]{3,}\s*$"""), "")
        // Эмодзи и прочие пиктограммы: стрелки, часы ⏰⌛, клавиши-цифры.
        .replace(Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{2190}-\x{21FF}\x{2300}-\x{23FF}\x{20E3}\x{FE0F}\x{200D}]"""), "")

    private val WORDS = listOf(
        "т. е.", "т.е.", "т. к.", "т.к.", "и т. д.", "и т.д.", "и т. п.", "и т.п.", "т. н.", "т.н.",
    ).zip(listOf("то есть", "то есть", "так как", "так как", "и так далее", "и так далее", "и тому подобное", "и тому подобное", "так называемый", "так называемый"))

    private val NAMES = mapOf(
        "Telegram" to "Телеграм", "WhatsApp" to "Вотсап", "YouTube" to "Ютуб", "Wi-Fi" to "вай-фай", "WiFi" to "вай-фай", "Bluetooth" to "блютус",
        "Google" to "Гугл", "Android" to "Андроид", "iPhone" to "айфон", "Viber" to "Вайбер", "VK" to "ВК", "SMS" to "эсэмэс", "СМС" to "эсэмэс",
        "GPS" to "джи-пи-эс", "OK" to "окей", "Ok" to "окей", "AI" to "ИИ", "FM" to "эф-эм", "USB" to "ю-эс-би", "PDF" to "пэ-дэ-эф",
        "Zoom" to "Зум", "Skype" to "Скайп", "Instagram" to "Инстаграм", "TikTok" to "Тикток", "Spotify" to "Спотифай", "email" to "имейл", "e-mail" to "имейл",
        "Nova" to "Нова", "OpenAI" to "Опен-эй-ай", "ChatGPT" to "чат джи-пи-ти", "Wikipedia" to "Википедия",
    )

    /** Время, сокращения, телефоны, английские названия — так, как это говорят вслух. */
    fun pronounce(text: String): String {
        var t = text
        for ((a, b) in WORDS) t = t.replace(a, b)
        for ((a, b) in NAMES) t = t.replace(Regex("""(?<![\p{L}])${Regex.escape(a)}(?![\p{L}])"""), b)
        // Телефон «+7 916 123-45-67» — группами, без «минус».
        t = Regex("""(\+?\d[\d ()]{5,}\d)-(\d{2})-(\d{2})""").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" }
        // Время: «в 10:00» → «в 10 ч.» (потом «10 часов»), «10:30» → «10 30», «10:05» → «10 05».
        // Счёт матча и масштаб («3:0», «1:25») — не время.
        fun notTime(m: MatchResult) = NOT_TIME.containsMatchIn(t.substring(maxOf(0, m.range.first - 24), m.range.first))
        t = Regex("""(?<![\d:])([01]?\d|2[0-3]):00(?![\d:])""").replace(t) { if (notTime(it)) it.value else "${it.groupValues[1].trimStart('0').ifEmpty { "0" }} ч." }
        t = Regex("""(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])""").replace(t) { m ->
            if (notTime(m)) return@replace m.value
            val mm = m.groupValues[2]
            "${m.groupValues[1].trimStart('0').ifEmpty { "0" }} ${if (mm.startsWith("0")) "ноль ${mm.substring(1)}" else mm}"
        }
        // Сокращения после чисел: «5 км», «60 км/ч», «2 кг», «2026 г.».
        // «2026 г.» — год («в 2026 году», «2026 года»); «200 г.» — граммы.
        t = Regex("""(?<![\d,])(1[89]\d\d|20\d\d)\s?г\.""").replace(t) { m ->
            val before = t.substring(maxOf(0, m.range.first - 3), m.range.first).lowercase()
            "${m.groupValues[1]} ${if (Regex("""(?:^|\s)в\s$""").containsMatchIn(before)) "году" else "года"}${keepDot(t, m)}"
        }
        t = Regex("""(\d+)\s?км/ч""").replace(t) { "${it.groupValues[1]} ${ai.nova.core.assistant.RuFormat.plural(it.groupValues[1].takeLast(3).toLong(), "километр", "километра", "километров")} в час" }
        val units = listOf(
            "км" to Triple("километр", "километра", "километров"), "кг" to Triple("килограмм", "килограмма", "килограммов"),
            "мл" to Triple("миллилитр", "миллилитра", "миллилитров"), "см" to Triple("сантиметр", "сантиметра", "сантиметров"),
            "л" to Triple("литр", "литра", "литров"), "шт\\." to Triple("штука", "штуки", "штук"),
            "чел\\." to Triple("человек", "человека", "человек"), "коп\\." to Triple("копейка", "копейки", "копеек"),
            "сек\\." to Triple("секунда", "секунды", "секунд"),
            "гПа" to Triple("гектопаскаль", "гектопаскаля", "гектопаскалей"), "мм рт\\. ст\\." to Triple("миллиметр ртутного столба", "миллиметра ртутного столба", "миллиметров ртутного столба"),
        )
        for ((u, forms) in units.sortedByDescending { it.first.length }) {
            t = Regex("""(\d+)(?:,(\d+))?\s?$u(?![\p{L}])""").replace(t) { m ->
                val n = m.groupValues[1].takeLast(9).toLongOrNull() ?: return@replace m.value
                val frac = m.groupValues[2]
                // «1,5 км» — «полтора километра»: с дробью родительный падеж единственного числа.
                val w = if (frac.isNotEmpty()) forms.second else ai.nova.core.assistant.RuFormat.plural(n, forms.first, forms.second, forms.third)
                "${m.groupValues[1]}${if (frac.isNotEmpty()) ",$frac" else ""} $w${if (u.endsWith(".")) keepDot(t, m) else ""}"
            }
        }
        t = t.replace(Regex("""№\s?"""), "номер ").replace(Regex("""(?<![\p{L}])ул\.\s"""), "улица ").replace(Regex("""(?<![\p{L}])пр-т(?![\p{L}])"""), "проспект")
            .replace(" & ", " и ").replace("+/-", "плюс-минус").replace("±", "плюс-минус")
        return t
    }

    private val NOT_TIME = Regex("""(?iu)(?:сч[её]т|матч|игр[аеуы]|масштаб|соотношени|пропорци|тайм|партии|сет)""")

    /** Сокращение с точкой в конце предложения («… 2 ч. Потом…»): точку, съеденную заменой, возвращаем. */
    private fun keepDot(t: String, m: MatchResult): String {
        val rest = t.substring(m.range.last + 1)
        return if (Regex("""^\s+[А-ЯЁA-Z]""").containsMatchIn(rest)) "." else ""
    }

    /** «1 ₽» → «1 рубль», «22 ₽» → «22 рубля», «3 дн.» → «3 дня»: движок речи не склоняет символы и сокращения. */
    fun declineUnits(text: String): String {
        val units = listOf(
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?₽""") to Triple("рубль", "рубля", "рублей"),
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?\$""") to Triple("доллар", "доллара", "долларов"),
            Regex("""(\d[\d  ]*)(?:,(\d+))?\s?€""") to Triple("евро", "евро", "евро"),
            Regex("""(\d+)(?:,(\d+))?\s?мин\.""") to Triple("минута", "минуты", "минут"),
            Regex("""(\d+)(?:,(\d+))?\s?ч\.""") to Triple("час", "часа", "часов"),
            Regex("""(\d+)(?:,(\d+))?\s?дн\.""") to Triple("день", "дня", "дней"),
            Regex("""(\d+)(?:,(\d+))?\s?нед\.""") to Triple("неделя", "недели", "недель"),
            Regex("""(\d+)(?:,(\d+))?\s?мес\.""") to Triple("месяц", "месяца", "месяцев"),
            Regex("""(\d+)(?:,(\d+))?\s?м/с""") to Triple("метр в секунду", "метра в секунду", "метров в секунду"),
            Regex("""(\d+)(?:,(\d+))?\s?мм""") to Triple("миллиметр", "миллиметра", "миллиметров"),
            Regex("""(\d+)(?:,(\d+))?\s?г\.(?!\p{L})""") to Triple("грамм", "грамма", "грамм"),
            Regex("""(?<![\d.])(\d{1,3})(?:,(\d+))?\s?г(?![\p{L}.])""") to Triple("грамм", "грамма", "грамм"),
            Regex("""(\d+)(?:,(\d+))?\s?%""") to Triple("процент", "процента", "процентов"),
        )
        var t = text
        // Сумма с копейками: «890,50 ₽» → «890 рублей 50 копеек». Иначе движок читает
        // «восемьсот девяносто целых и пятьдесят сотых рубля», а в банковском пуше это обычная запись.
        val moneyWords = mapOf(
            "₽" to Triple("рубль", "рубля", "рублей"),
            "\$" to Triple("доллар", "доллара", "долларов"),
            "€" to Triple("евро", "евро", "евро"),
        )
        val minorWords = mapOf("₽" to Triple("копейка", "копейки", "копеек"), "\$" to Triple("цент", "цента", "центов"), "€" to Triple("цент", "цента", "центов"))
        t = Regex("""(\d[\d  ]*),(\d{1,2})\s?([₽$€])""").replace(t) { m ->
            val whole = m.groupValues[1]
            val n = whole.replace(" ", "").replace(" ", "").toLongOrNull() ?: return@replace m.value
            val k = m.groupValues[2].padEnd(2, '0').toLongOrNull() ?: return@replace m.value
            val symbol = m.groupValues[3]
            val (one, few, many) = moneyWords[symbol] ?: return@replace m.value
            val major = ai.nova.core.assistant.RuFormat.plural(n, one, few, many)
            if (k == 0L) return@replace "$whole $major"
            val (kOne, kFew, kMany) = minorWords[symbol] ?: return@replace m.value
            "$whole $major $k " + ai.nova.core.assistant.RuFormat.plural(k, kOne, kFew, kMany)
        }
        // «+12°» → «плюс 12 градусов», «−3°» → «минус 3 градуса».
        t = Regex("""(?:(?<=^|[\s(…:])([+−-]))?(\d+)(?:,(\d+))?\s?°\s?[CС]?(?![\p{L}])""").replace(t) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[3]
            val sign = when (m.groupValues[1]) { "+" -> "плюс "; "−", "-" -> if (n == 0L && frac.trim('0').isEmpty()) "" else "минус "; else -> "" }
            // «12,5°» — «двенадцать и пять десятых градуса»: с дробью родительный падеж единственного числа.
            val word = if (frac.isNotEmpty()) "градуса" else ai.nova.core.assistant.RuFormat.plural(n, "градус", "градуса", "градусов")
            "$sign$n${if (frac.isNotEmpty()) ",$frac" else ""} $word"
        }
        for ((re, forms) in units) {
            t = re.replace(t) { m ->
                val whole = m.groupValues[1].trim()
                val n = whole.replace(" ", "").replace(" ", "").toLongOrNull() ?: return@replace m.value
                val frac = m.groupValues[2]
                // С копейками — родительный падеж единственного числа: «12,50 рубля»
                val word = if (frac.isNotEmpty()) forms.second else ai.nova.core.assistant.RuFormat.plural(n, forms.first, forms.second, forms.third)
                val dot = if (re.pattern.endsWith("\\.")) keepDot(t, m) else ""
                "$whole${if (frac.isNotEmpty()) ",$frac" else ""} $word$dot"
            }
        }
        return t
    }
}

/**
 * Поправки к распознанному тексту: частые ошибки распознавания русской речи и нашего имени.
 * Правим только то, что почти наверняка ошибка, — осмысленные слова не трогаем.
 */
object SpeechFixes {
    private val NAME_VARIANTS = listOf("нова", "нава", "ново", "нову", "новы", "ноль", "ноли", "ноле", "нолик", "нолли", "nova", "ну ли", "но ли")

    fun apply(raw: String, assistantName: String = "Нова"): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        // Имя в начале фразы: «Ноли, запиши…» → «Нова, запиши…».
        val first = t.substringBefore(' ').trim(',', '.', '!').lowercase().replace('ё', 'е')
        val name = assistantName.lowercase().replace('ё', 'е')
        if (name == "нова" && first in NAME_VARIANTS) t = assistantName + t.substring(t.substringBefore(' ').trimEnd(',', '.', '!').length)
        val rules = listOf(
            Regex("""(?iu)(?<=^|\s)тыщ(?:а|и|у)?(?=\s|$|[.,!?])""") to "тысяч",
            Regex("""(?iu)(?<=\d)\s?(?:руб\.?|р\.)(?=\s|$|[.,!?])""") to " рублей",
            Regex("""(?iu)(?<=^|\s)пол\s(часа|минуты|года|месяца)""") to "пол$1",
            Regex("""(?iu)(?<=^|\s)на поминание""") to "напоминание",
            Regex("""(?iu)(?<=^|\s)на помни(?=\s|$)""") to "напомни",
            Regex("""(?iu)(?<=^|\s)за помни(?=\s|$)""") to "запомни",
            Regex("""(?iu)(?<=^|\s)за пиши(?=\s|$)""") to "запиши",
            Regex("""(?iu)(?<=^|\s)по ставь(?=\s|$)""") to "поставь",
            Regex("""(?iu)(?<=^|\s)до бавь(?=\s|$)""") to "добавь",
            // «потратил 5000 за дачу» — это дача; «добавь за дачу» — задача.
            Regex("""(?iu)(?<=^|(?:добавь|поставь|создай|запиши|новую|одну)\s)за дачу(?=\s|$)""") to "задачу",
            // Распознаватель офлайн часто разрывает команды на приставку и корень.
            Regex("""(?iu)(?<=^|\s)по года(?=\s|$|[.,!?])""") to "погода",
            Regex("""(?iu)(?<=^|\s)раз буди(?=\s|$)""") to "разбуди",
            Regex("""(?iu)(?<=^|\s)от мени(?=\s|$)""") to "отмени",
            Regex("""(?iu)^((?:нова,?\s+)?)вы ключи(?=\s+(?:свет|музыку|звук|радио|таймер|будильник|экран|телефон|фонарик|интернет|блютус|вай-фай|вайфай|вс[её]|телевизор|утюг|плиту)|$)""") to "$1выключи",
            Regex("""(?iu)(?<=^|\s)за секи(?=\s|$)""") to "засеки",
            Regex("""(?iu)(?<=^|\s)про читай(?=\s|$)""") to "прочитай",
            Regex("""(?iu)^((?:нова,?\s+)?)на бери(?=\s|$)""") to "$1набери",
            Regex("""(?iu)(?<=^|\s)по звони(?=\s|$)""") to "позвони",
            Regex("""(?iu)(?<=^|\s)пере веди(?=\s|$)""") to "переведи",
            Regex("""(?iu)(?<=^|\s)на пиши(?=\s|$)""") to "напиши",
            Regex("""(?iu)(?<=^|\s)тай мер(?=\s|$)""") to "таймер",
            Regex("""(?iu)(?<=^|\s)буди льник(?=\s|$)""") to "будильник",
            Regex("""(?iu)(?<=^|\s)(?:что|че|чё|чо) там (?:по|с) погод\S*""") to "какая погода",
            Regex("""\s{2,}""") to " ",
        )
        for ((re, to) in rules) t = t.replace(re, to)
        return t.trim()
    }

    /** Фраза оборвалась на полуслове («купи хлеб и», «напомни мне завтра в») — стоит дослушать продолжение. */
    fun looksUnfinished(text: String): Boolean {
        val last = text.trim().trimEnd('.', '!', '?').substringAfterLast(' ').lowercase().replace('ё', 'е')
        if (text.trim().endsWith(",")) return true
        return last in DANGLING
    }

    private val DANGLING = setOf(
        "и", "а", "но", "или", "на", "в", "во", "к", "ко", "по", "за", "с", "со", "у", "о", "об", "про", "для", "от", "до", "из",
        "потом", "затем", "что", "чтобы", "это", "мне", "мой", "моя", "мою", "еще", "также", "когда", "если", "как", "через", "после",
        "перед", "около", "где", "куда", "очень", "самый", "самая", "не",
    )
}
