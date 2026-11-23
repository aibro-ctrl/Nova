package ai.nova.core.nlp

/**
 * Приводит надиктованный текст в читаемый вид.
 *
 * Распознавание речи (и системное, и офлайн-Vosk) часто отдаёт «сплошной» текст: без заглавных букв,
 * точек и абзацев. Здесь мы, не меняя смысл и не переписывая слова:
 *  - ставим границы предложений по естественным зачинам («потом», «кстати», «во-первых»…);
 *  - поднимаем регистр в начале предложений и ставим точку в конце;
 *  - добавляем запятую после вводных слов («кстати, …», «например, …»);
 *  - разбиваем длинный текст на абзацы.
 *
 * Всё офлайн и детерминированно — текст пользователя никуда не отправляется.
 * Уже расставленные знаки препинания сохраняются: формат только дополняет текст.
 */
object DictationText {

    /** Длиннее этого — текст делим на абзацы по [PARAGRAPH_SIZE] предложений. */
    private const val PARAGRAPH_MIN_SENTENCES = 4
    private const val PARAGRAPH_SIZE = 3

    /** Зачины, после которых почти наверняка начинается новое предложение (даже без точки). */
    private val STRONG_STARTS: List<List<String>> = listOf(
        listOf("а", "еще"), listOf("а", "ещё"), listOf("кроме", "того"), listOf("после", "этого"),
        listOf("к", "тому", "же"), listOf("в", "общем"), listOf("в", "итоге"), listOf("таким", "образом"),
        listOf("с", "другой", "стороны"), listOf("во-первых"), listOf("во", "первых"),
        listOf("во-вторых"), listOf("во", "вторых"), listOf("в-третьих"), listOf("в", "третьих"),
    )

    /**
     * Более слабые зачины: они часто стоят и в середине фразы («я потом пойду»),
     * поэтому режем по ним только достаточно длинное предложение.
     */
    private val WEAK_STARTS: List<List<String>> = listOf(
        listOf("потом"), listOf("затем"), listOf("далее"), listOf("кстати"), listOf("теперь"),
        listOf("дальше"), listOf("поэтому"), listOf("короче"), listOf("значит"), listOf("например"),
    )

    /** Союзы и союзные слова: перед ними фразу не рвём — иначе получится «…и. Потом…». */
    private val BINDERS = setOf("и", "а", "но", "да", "или", "что", "чтобы", "когда", "если", "как", "хотя", "пока", "потому")

    /** Вводные слова: после них нужна запятая. */
    private val INTRO = Regex(
        """^(во[- ]первых|во[- ]вторых|в[- ]третьих|кстати|например|короче|значит|кроме того|к тому же|таким образом|с другой стороны|в общем|в итоге)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?…])""")
    private val MULTI_SPACE = Regex("""\s+""")

    /**
     * Форматирует надиктованный текст. Пустая строка → пустая строка.
     * Идемпотентно: повторный вызов уже отформатированный текст не меняет.
     */
    fun format(raw: String): String {
        val base = raw.replace(MULTI_SPACE, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()
        if (base.isEmpty()) return ""
        val sentences = base.split(SENTENCE_END).flatMap { splitByStarts(it.trim()) }
            .map { it.trim().trim(',', ';', ':') }
            .filter { it.isNotEmpty() }
            .map { sentence(it) }
        if (sentences.isEmpty()) return ""
        return paragraphs(sentences).joinToString("\n\n") { it.joinToString(" ") }
    }

    /**
     * Заголовок заметки: первое предложение, не длиннее [maxWords] слов.
     * Так заголовок остаётся коротким и узнаваемым, а весь текст — в самой заметке.
     */
    fun title(text: String, maxWords: Int = 6, maxChars: Int = 80): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val firstSentence = Regex("""(?<=[.!?…])\s+""").split(firstLine).firstOrNull().orEmpty()
        val words = firstSentence.split(' ').filter { it.isNotBlank() }
        val short = words.take(maxWords).joinToString(" ")
            .trimEnd(',', '.', ':', ';', '!', '?', '…')
            .take(maxChars)
            .trimEnd()
        return short.ifEmpty { text.take(maxChars) }
    }

    /** Рвёт кусок на предложения по зачинам; уже имеющиеся точки не трогает. */
    private fun splitByStarts(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        if (chunk.last() in ".!?…") return listOf(chunk)
        val words = chunk.split(' ')
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < words.size) {
            val strong = markerAt(words, i, STRONG_STARTS)
            val weak = if (strong == 0) markerAt(words, i, WEAK_STARTS) else 0
            val len = if (strong > 0) strong else weak
            val enoughBefore = i - start >= if (strong > 0) 3 else 4
            val enoughAfter = words.size - i >= 2
            val binds = i > 0 && key(words[i - 1]) in BINDERS
            if (len > 0 && i > start && enoughBefore && enoughAfter && !binds) {
                out += words.subList(start, i).joinToString(" ")
                start = i
                i += len
            } else {
                i++
            }
        }
        out += words.subList(start, words.size).joinToString(" ")
        return out
    }

    /** Длина совпавшего зачина в словах или 0. */
    private fun markerAt(words: List<String>, at: Int, markers: List<List<String>>): Int {
        if (at >= words.size) return 0
        return markers.maxOfOrNull { m ->
            if (at + m.size <= words.size && m.indices.all { key(words[at + it]) == m[it] }) m.size else 0
        } ?: 0
    }

    private fun key(word: String) = RuTokenizer.normalize(word).trim('.', ',', '!', '?', '…', ';', ':', '«', '»', '"')

    /** Регистр, запятая после вводного слова и точка в конце. */
    private fun sentence(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        s = INTRO.replace(s) { m -> m.value.trimEnd() + ", " }.trimEnd()
        s = s.replaceFirstChar { it.uppercase() }
        if (s.last() !in ".!?…") s += "."
        return s
    }

    private fun paragraphs(sentences: List<String>): List<List<String>> {
        if (sentences.size < PARAGRAPH_MIN_SENTENCES) return listOf(sentences)
        val out = ArrayList<MutableList<String>>()
        var current = ArrayList<String>()
        for (s in sentences) {
            // «Дальше», «теперь про…», «ещё одно» — начало новой мысли.
            val newTopic = current.isNotEmpty() && TOPIC_START.containsMatchIn(s)
            if (current.isNotEmpty() && (newTopic || current.size >= PARAGRAPH_SIZE)) {
                out += current
                current = ArrayList()
            }
            current += s
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private val TOPIC_START = Regex(
        """^(Теперь|Дальше|Далее|Насч[её]т|По поводу|Ещ[её] одно|Вторая мысль|И ещ[её])\b""",
        RegexOption.IGNORE_CASE,
    )
}
