package ai.nova.core.agent

import ai.nova.core.nlp.RuTokenizer

/**
 * Разбор составной фразы на отдельные намерения: «найди рецепт борща и запиши его в заметки».
 *
 * Делим только по явным связкам («и потом», «затем», «после этого», «;») или по «и»,
 * за которым идёт явная команда-глагол. Обычное «купил молоко и хлеб» остаётся одной фразой.
 */
object IntentRecognizer {
    private const val MAX_INTENTS = 3

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val SEQ = rx("""\s*(?:,\s*)?(?:(?:а|и)\s+)?(?:потом|затем|после\s+этого)\s+""")
    private val ALSO = rx("""\s*(?:,\s*)?(?:и\s+ещё|и\s+также|и\s+заодно)\s+""")
    private val SEMI = rx("""\s*;\s*""")
    private val AND = rx("""\s+и\s+""")

    /** Начало самостоятельной команды — по нему решаем, что «и» разделяет две команды. */
    private val COMMAND_START = rx(
        """^(?:запиши|записать|сохрани|сохранить|создай|создать|добавь|добавить|занеси|поставь|поставить|напомни|напомнить""" +
            """|включи|включить|открой|открыть|найди|найти|поищи|покажи|показать|расскажи|рассказать|прочитай|напиши|написать""" +
            """|удали|удалить|отметь|отметить|купи|купить|сделай|сделать|приготовь|приготовить|переведи|перевести|положи|потратил\p{L}*)""",
    )

    /** «запиши его в заметки», «добавь их в покупки» — нужна сущность из предыдущего шага. */
    private val PRONOUN = rx("""(?:^|\s)(?:его|е[её]|их|это|эту|этот|эти|туда|вс[её]\s+это)(?:\s|$)""")

    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /** Делит фразу на части; одна часть — значит делить нечего. */
    fun split(text: String): List<String> {
        var clauses = listOf(text.trim())
        for (re in listOf(SEQ, ALSO, SEMI)) {
            clauses = clauses.flatMap { it.split(re) }
            if (clauses.size > 1) return normalize(clauses)
        }
        // Обычное «и»: делим, только если справа начинается явная команда.
        for (m in AND.findAll(text)) {
            val left = text.substring(0, m.range.first).trim()
            val right = text.substring(m.range.last + 1).trim()
            if (words(left) < 3 || words(right) < 3) continue
            if (!COMMAND_START.containsMatchIn(RuTokenizer.normalize(right))) continue
            return normalize(listOf(left, right))
        }
        return listOf(text.trim())
    }

    private fun normalize(clauses: List<String>): List<String> =
        clauses.map { it.trim().trim(',', '.', ';').trim() }.filter { it.isNotBlank() }.take(MAX_INTENTS)

    /**
     * План из намерений или null, если фраза не составная.
     * Каждая часть должна опознаваться реестром; иначе отдаём фразу обычному пути обработки.
     */
    fun plan(text: String, registry: SkillRegistry): AgentPlan? {
        // «Когда я говорю спокойной ночи, добавь задачу и напомни…» — это сценарий, а не две команды.
        if (SCENARIO_START.containsMatchIn(RuTokenizer.normalize(text))) return null
        val clauses = split(text)
        if (clauses.size < 2) return null
        val intents = clauses.mapIndexed { i, clause ->
            val skill = registry.best(clause) ?: return null
            AgentIntent(
                index = i,
                skill = skill.id,
                text = clause,
                needsEntity = PRONOUN.containsMatchIn(RuTokenizer.normalize(clause)) && !SKIP_PRONOUNS.containsMatchIn(clause),
            )
        }
        // Составную фразу берём в работу, только если в ней есть действие или источник сущности:
        // «какая погода и что нового» остаётся обычным запросом.
        val meaningful = intents.any { i -> registry.byId(i.skill)?.let { it.mutates || it.producesEntity != null } == true }
        if (!meaningful) return null
        return AgentPlan(intents, text.trim())
    }

    /** «запиши это в заметки» без сущности — обычная заметка, ссылаться не на что. */
    private val SKIP_PRONOUNS = rx("""^запиши\s+(?:это|вс[её]\s+это)\b""")

    /** Фраза задаёт сценарий («когда я говорю…», «если…») — внутри него команды не делим. */
    private val SCENARIO_START = rx(
        """^(?:когда|если|пока|после\s+того\s+как|каждый\s+раз|каждое\s+(?:утро|вечер)|каждую\s+неделю|по\s+команде|сценари\p{L}*)""",
    )
}
