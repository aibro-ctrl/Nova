package ai.nova.core.ai

/**
 * Офлайн-модель как разбор команд.
 *
 * Модель ничего не выполняет сама: она переписывает фразу в короткую команду, а выполняет её
 * обычный разбор на устройстве ([ai.nova.core.assistant.LocalCommandParser]). Поэтому набор
 * действий не расширяется, опасное по-прежнему требует подтверждения, а непонятный ответ модели
 * просто отбрасывается. Маленькая модель (1,5 млрд параметров) не выдаёт надёжный JSON —
 * одну короткую команду она писать умеет, и это единственное, что мы у неё просим.
 */
object CommandPlanner {
    /** Слишком длинный ответ — это уже не команда, а рассуждение. */
    private const val MAX_COMMAND = 120

    private val NO = setOf("нет", "no", "не команда", "это не команда", "не команда.", "none", "null", "-", "—")
    private val REFUSAL = Regex("""^(?:извини|извините|к сожалению|не могу|я не|не уверен|не уверена|простите|sorry)""")

    /**
     * Промпт короткий намеренно: каждое лишнее слово модели — это задержка перед ответом.
     * Примеры подобраны под те формы, которые точно понимает разбор команд.
     */
    fun systemPrompt(): String = buildString {
        appendLine("Перепиши фразу в одну короткую команду ассистента. Ответ — только команда, без пояснений.")
        appendLine("Если это не команда (вопрос о жизни, разговор, совет, болтовня), ответь: нет")
        appendLine("Примеры:")
        appendLine("«надо не забыть позвонить маме завтра в десять» → напомни завтра в 10 утра позвонить маме")
        appendLine("«потратила пятьсот рублей на кофе» → расход 500 рублей на кофе")
        appendLine("«надо сходить в парикмахерскую в субботу» → задача сходить в парикмахерскую в субботу")
        appendLine("«запиши, что кот ест корм Роял» → заметка кот ест корм Роял")
        appendLine("«есть мысль сделать приложение для дачников» → идея приложение для дачников")
        appendLine("«во сколько обошлась поездка на такси» → сколько я потратила на такси")
        appendLine("«как у тебя дела» → нет")
    }

    /**
     * Что модель ответила → команда для разбора. null — ответа нет, это не команда или мусор:
     * дальше работает обычный разбор, ничего не выполняется «на доверии».
     */
    fun command(raw: String?): String? {
        val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val cleaned = line
            .removePrefix("-").removePrefix("•").removePrefix("→").removePrefix(">")
            .trim()
            .removePrefix("Ответ:").removePrefix("ответ:").trim()
            .trim('«', '»', '"', '\'', '`', '*')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_COMMAND) return null
        if (cleaned.lowercase() in NO) return null
        if (REFUSAL.containsMatchIn(cleaned.lowercase())) return null
        // JSON, markdown и прочий мусор командой не считаем.
        if (cleaned.any { it in "{}[]<>|" }) return null
        if (cleaned.none { it.isLetter() }) return null
        return cleaned
    }
}
