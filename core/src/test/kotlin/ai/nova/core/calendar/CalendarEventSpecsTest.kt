package ai.nova.core.calendar

import ai.nova.core.model.Recurrence
import ai.nova.core.model.Reminder
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Поля события календаря: длительность, серия и отпечаток для обновлений. */
class CalendarEventSpecsTest {
    private val created = Instant.parse("2026-05-01T00:00:00Z")

    private fun reminder(
        text: String = "Позвонить маме",
        at: String = "2026-05-04T06:00:00Z",
        zone: String = "Europe/Moscow",
        recurrence: Recurrence? = null,
    ) = Reminder(
        id = "r1",
        text = text,
        triggerAt = Instant.parse(at),
        recurrence = recurrence,
        timeZone = zone,
        active = true,
        createdAt = created,
        updatedAt = created,
    )

    @Test
    fun oneTimeEventIsHalfAnHourLong() {
        val spec = CalendarEventSpecs.forReminder(reminder())
        assertEquals("Позвонить маме", spec.title)
        assertEquals(Instant.parse("2026-05-04T06:00:00Z"), spec.start)
        assertEquals(Instant.parse("2026-05-04T06:30:00Z"), spec.end)
        assertEquals("Europe/Moscow", spec.timeZone)
        assertNull(spec.rrule)
    }

    @Test
    fun dailyReminderBecomesSeries() {
        val spec = CalendarEventSpecs.forReminder(reminder(recurrence = Recurrence(Recurrence.Frequency.DAILY, interval = 2)))
        assertEquals("FREQ=DAILY;INTERVAL=2", spec.rrule)
    }

    @Test
    fun weeklyReminderListsWeekdays() {
        val rule = Recurrence(
            Recurrence.Frequency.WEEKLY,
            interval = 2,
            daysOfWeek = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY),
        )
        val spec = CalendarEventSpecs.forReminder(reminder(recurrence = rule))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", spec.rrule)
    }

    @Test
    fun monthlyOnShortDaysStaysSingle() {
        val rule = Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = 31)
        assertNull(CalendarEventSpecs.forReminder(reminder(recurrence = rule)).rrule)
    }

    @Test
    fun monthlyOnSafeDayBecomesSeries() {
        val rule = Recurrence(Recurrence.Frequency.MONTHLY, interval = 1, dayOfMonth = 15)
        assertEquals(
            "FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=15",
            CalendarEventSpecs.forReminder(reminder(recurrence = rule)).rrule,
        )
    }

    @Test
    fun leapDayStaysSingle() {
        val rule = Recurrence(Recurrence.Frequency.YEARLY, month = 2, dayOfMonth = 29)
        assertNull(CalendarEventSpecs.forReminder(reminder(recurrence = rule)).rrule)
        val march = Recurrence(Recurrence.Frequency.YEARLY, month = 3, dayOfMonth = 8)
        assertEquals(
            "FREQ=YEARLY;INTERVAL=1;BYMONTH=3;BYMONTHDAY=8",
            CalendarEventSpecs.forReminder(reminder(recurrence = march)).rrule,
        )
    }

    @Test
    fun hourlyReminderIsSingle() {
        val rule = Recurrence(Recurrence.Frequency.HOURLY, interval = 2)
        assertNull(CalendarEventSpecs.forReminder(reminder(recurrence = rule)).rrule)
    }

    @Test
    fun dailyFingerprintSurvivesNextOccurrence() {
        val rule = Recurrence(Recurrence.Frequency.DAILY, interval = 1)
        val first = CalendarEventSpecs.forReminder(reminder(at = "2026-05-04T06:00:00Z", recurrence = rule))
        val next = CalendarEventSpecs.forReminder(reminder(at = "2026-05-05T06:00:00Z", recurrence = rule))
        assertEquals(first.fingerprint, next.fingerprint, "серия не переписывается после срабатывания")
    }

    @Test
    fun fingerprintChangesWhenTimeOrTextChanges() {
        val rule = Recurrence(Recurrence.Frequency.DAILY, interval = 1)
        val base = CalendarEventSpecs.forReminder(reminder(at = "2026-05-04T06:00:00Z", recurrence = rule))
        val later = CalendarEventSpecs.forReminder(reminder(at = "2026-05-05T07:00:00Z", recurrence = rule))
        val renamed = CalendarEventSpecs.forReminder(reminder(text = "Позвонить папе", at = "2026-05-04T06:00:00Z", recurrence = rule))
        assertTrue(base.fingerprint != later.fingerprint, "время изменилось — событие обновляем")
        assertTrue(base.fingerprint != renamed.fingerprint, "текст изменился — событие обновляем")
    }

    @Test
    fun oneTimeFingerprintFollowsStart() {
        val first = CalendarEventSpecs.forReminder(reminder(at = "2026-05-04T06:00:00Z"))
        val moved = CalendarEventSpecs.forReminder(reminder(at = "2026-05-04T07:00:00Z"))
        assertTrue(first.fingerprint != moved.fingerprint)
    }

    @Test
    fun blankTextGetsReadableTitle() {
        assertEquals("Напоминание", CalendarEventSpecs.forReminder(reminder(text = "   ")).title)
    }

    @Test
    fun servicePrefixesAreHiddenFromCalendar() {
        assertEquals("Созвон", CalendarEventSpecs.forReminder(reminder(text = "Задача: Созвон")).title)
        assertEquals("спокойной ночи", CalendarEventSpecs.forReminder(reminder(text = "Сценарий: спокойной ночи")).title)
    }

    @Test
    fun rruleIsNotNullForWeeklyWithoutExplicitDays() {
        val rule = Recurrence(Recurrence.Frequency.WEEKLY, interval = 1)
        val spec = CalendarEventSpecs.forReminder(reminder(recurrence = rule))
        assertNotNull(spec.rrule)
        // 2026-05-04 — понедельник.
        assertTrue(spec.rrule!!.contains("BYDAY=MO"))
    }
}
