package ai.nova.core.assistant

/**
 * Реальные данные дня, из которых заботливая Нова строит предложение помощи.
 * Ничего не выдумываем: нет данных — нет предложения.
 */
data class SuggestionData(
    val tasksToday: Int = 0,
    val overdue: Int = 0,
    val reminderInMinutes: Long? = null,
    val reminderText: String? = null,
    val spentToday: Long = 0,
    val usualDaySpend: Long = 0,
)

/** Готовое предложение; [key] — чтобы одно и то же не звучало дважды. */
data class Suggestion(val key: String, val text: String)

/**
 * Contextual Suggestions: одно уместное предложение на ответ, только по данным приложения.
 * Придумано, чтобы заботливая Нова была проактивной, но не навязчивой.
 */
object Suggestions {
    fun caring(data: SuggestionData, shown: Set<String>): Suggestion? {
        if (data.overdue > 0 && "overdue" !in shown) {
            return Suggestion("overdue", "Кстати, ${plural(data.overdue, "просроченная задача", "просроченные задачи", "просроченных задач")} — показать?")
        }
        val minutes = data.reminderInMinutes
        if (minutes != null && minutes in 0..180 && "reminder" !in shown && !data.reminderText.isNullOrBlank()) {
            val whenText = if (minutes < 60) "через ${plural(minutes.toInt(), "минуту", "минуты", "минут")}" else "через ${plural((minutes / 60).toInt(), "час", "часа", "часов")}"
            return Suggestion("reminder", "Скоро напоминание $whenText: «${data.reminderText.take(60)}».")
        }
        if (data.tasksToday >= 3 && "tasks" !in shown) {
            return Suggestion("tasks", "На сегодня ${plural(data.tasksToday, "задача", "задачи", "задач")} — хотите, покажу, что осталось?")
        }
        if (data.usualDaySpend > 0 && data.spentToday > data.usualDaySpend * 2 && "spend" !in shown) {
            return Suggestion("spend", "Сегодня потрачено ${money(data.spentToday)} — больше обычного. Посмотреть, на что?")
        }
        return null
    }

    /** «1 задача», «2 задачи», «5 задач». */
    fun plural(n: Int, one: String, few: String, many: String): String = "$n " + when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

    private fun money(rub: Long): String = String.format(java.util.Locale.ROOT, "%,d", rub).replace(',', ' ') + " ₽"
}
