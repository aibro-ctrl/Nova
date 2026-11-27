package ai.nova.core.calendar

import ai.nova.core.assistant.TaskReminders
import ai.nova.core.model.Recurrence
import ai.nova.core.model.Reminder
import ai.nova.core.model.Routine
import java.time.Instant
import java.time.ZoneId

/**
 * Событие календаря, которым дублируется напоминание Нова.
 *
 * Это чистая логика без Android: её можно проверить тестами и переиспользовать на других
 * клиентах. Android-слой ([ai.nova.app.calendar.CalendarMirror]) только записывает эти поля
 * в календарь телефона через CalendarContract — без OAuth и без ключей.
 */
data class CalendarEventSpec(
    val title: String,
    val start: Instant,
    val end: Instant,
    /** IANA-зона из напоминания: событие должно попадать в то же местное время. */
    val timeZone: String,
    /** Правило серии для Google Календаря; null — событие на один раз (ближайшее срабатывание). */
    val rrule: String?,
    /** Меняется только тогда, когда событие действительно нужно переписать. */
    val fingerprint: String,
)

object CalendarEventSpecs {
    /** Напоминание — точка во времени; в календаре показываем его получасовым событием. */
    const val DURATION_MINUTES = 30L

    /**
     * Поля события для напоминания.
     *
     * Для ежедневных и еженедельных напоминаний делаем серию: человек видит в календаре
     * будущие повторения, а не только ближайшее. Для часовых, месячных с 29–31 числом и
     * 29 февраля серию не строим: Нова переносит такие даты на конец месяца, а правило
     * Google Календаря пропускало бы короткие месяцы. Для них событие — ближайшее
     * срабатывание, которое обновляется после каждого срабатывания.
     */
    fun forReminder(reminder: Reminder): CalendarEventSpec {
        val zone = runCatching { ZoneId.of(reminder.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val start = reminder.triggerAt
        val end = start.plusSeconds(DURATION_MINUTES * 60)
        val rrule = reminder.recurrence?.let { ruleFor(it, start, zone) }
        // Служебные напоминания задач и сценариев не должны пугать «Задача:» в календаре.
        val title = reminder.text.trim()
            .removePrefix(TaskReminders.PREFIX)
            .removePrefix(Routine.SCHEDULE_PREFIX)
            .trim()
            .ifBlank { "Напоминание" }
        return CalendarEventSpec(
            title = title,
            start = start,
            end = end,
            timeZone = zone.id,
            rrule = rrule,
            fingerprint = fingerprint(reminder, start, zone, rrule),
        )
    }

    /** Правило RRULE для CalendarContract или null, если событие должно быть одиночным. */
    fun ruleFor(rule: Recurrence, start: Instant, zone: ZoneId): String? = when (rule.frequency) {
        Recurrence.Frequency.HOURLY -> null
        Recurrence.Frequency.DAILY -> "FREQ=DAILY;INTERVAL=${rule.interval}"
        Recurrence.Frequency.WEEKLY -> {
            val days = rule.daysOfWeek.ifEmpty { setOf(start.atZone(zone).dayOfWeek) }
            val codes = days.sortedBy { it.value }.joinToString(",") { Recurrence.dayCode(it) }
            "FREQ=WEEKLY;INTERVAL=${rule.interval};BYDAY=$codes"
        }
        Recurrence.Frequency.MONTHLY -> {
            val day = rule.dayOfMonth ?: start.atZone(zone).dayOfMonth
            // Нова переносит 31-е на 28–30-е в короткие месяцы; RRULE так не умеет.
            if (day > 28) null else "FREQ=MONTHLY;INTERVAL=${rule.interval};BYMONTHDAY=$day"
        }
        Recurrence.Frequency.YEARLY -> {
            val z = start.atZone(zone)
            val month = rule.month ?: z.monthValue
            val day = rule.dayOfMonth ?: z.dayOfMonth
            // 29 февраля существует не каждый год — оставляем ближайшее событие.
            if (month == 2 && day == 29) null else "FREQ=YEARLY;INTERVAL=${rule.interval};BYMONTH=$month;BYMONTHDAY=$day"
        }
    }

    private fun fingerprint(
        reminder: Reminder,
        start: Instant,
        zone: ZoneId,
        rrule: String?,
    ): String {
        val z = start.atZone(zone)
        val parts = mutableListOf(reminder.text.trim(), zone.id)
        if (rrule == null) {
            // Одиночное событие двигается вместе с ближайшим срабатыванием.
            parts += start.toEpochMilli().toString()
        } else {
            parts += rrule
            // Время суток важно, дата — нет: после срабатывания серия не переписывается.
            parts += "%02d:%02d".format(z.hour, z.minute)
            // Для «каждые N дней/недель» дата задаёт фазу серии.
            if ((reminder.recurrence?.interval ?: 1) > 1) parts += z.toLocalDate().toString()
        }
        return parts.joinToString("\u0001")
    }
}
