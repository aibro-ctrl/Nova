package ai.nova.core.health

/**
 * Блоки сводок («Итоги дня», утренняя сводка): человек сам выбирает, что озвучивать.
 * Выключенный блок не попадает в текст — и, значит, не озвучивается.
 */
object BriefBlocks {
    const val WEATHER = "weather"
    const val TASKS = "tasks"
    const val REMINDERS = "reminders"
    const val HEALTH = "health"
    const val SPEND = "spend"
    const val IDEAS = "ideas"

    val ALL: Set<String> = linkedSetOf(WEATHER, TASKS, REMINDERS, HEALTH, SPEND, IDEAS)

    fun on(blocks: Set<String>, block: String): Boolean = block in blocks

    /** Хранится строкой «погода,задачи»; неизвестные значения отбрасываем, пустое — «всё выключено». */
    fun parse(raw: String?): Set<String> {
        if (raw == null) return ALL
        return raw.split(',').map { it.trim() }.filter { it in ALL }.toSet()
    }

    fun store(blocks: Set<String>): String = ALL.filter { it in blocks }.joinToString(",")

    fun label(block: String): String = when (block) {
        WEATHER -> "Погода"
        TASKS -> "Задачи"
        REMINDERS -> "Напоминания"
        HEALTH -> "Здоровье: вода, привычки, настроение"
        SPEND -> "Траты"
        IDEAS -> "Идеи"
        else -> block
    }
}
