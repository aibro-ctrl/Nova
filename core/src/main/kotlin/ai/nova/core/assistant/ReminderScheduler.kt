package ai.nova.core.assistant

import ai.nova.core.model.Reminder

/** Платформенное планирование напоминаний (на Android — AlarmManager). */
interface ReminderScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: String)
}

object NoopReminderScheduler : ReminderScheduler {
    override fun schedule(reminder: Reminder) = Unit
    override fun cancel(reminderId: String) = Unit
}
