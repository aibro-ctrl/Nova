package ai.nova.core.assistant

import java.time.Instant

/**
 * План, который создан голосом и ещё не попал в календарь телефона.
 * Нова предлагает это сразу после задачи или напоминания: «скажите „в календарь“ — добавлю».
 */
data class PlanCalendar(val title: String, val at: Instant, val allDay: Boolean)
