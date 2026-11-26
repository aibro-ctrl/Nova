package ai.nova.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Когда звонить будильнику: сегодня, в названный день или в следующий раз через неделю. */
class NovaAlarmsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = ZonedDateTime.of(2026, 11, 25, 9, 0, 0, 0, zone) // среда, 09:00

    @Test fun todayIfTimeIsAhead() {
        val at = NovaAlarms.nextAt(LocalTime.of(18, 30), emptySet(), now)
        assertEquals("2026-11-25T18:30", at.atZone(zone).toLocalDateTime().toString())
    }

    @Test fun tomorrowIfTimePassed() {
        val at = NovaAlarms.nextAt(LocalTime.of(7, 0), emptySet(), now)
        assertEquals("2026-11-26T07:00", at.atZone(zone).toLocalDateTime().toString())
    }

    @Test fun namedSundayIsTheNearestSunday() {
        val at = NovaAlarms.nextAt(LocalTime.of(9, 0), setOf(DayOfWeek.SUNDAY), now)
        assertEquals("2026-11-29T09:00", at.atZone(zone).toLocalDateTime().toString())
        assertEquals(DayOfWeek.SUNDAY, at.atZone(zone).dayOfWeek)
    }

    @Test fun weekdayAlarmSkipsWeekend() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        // В среду 09:00 будильник «на 7 по будням» уже прошёл — он ждёт четверга, а не субботы.
        val at = NovaAlarms.nextAt(LocalTime.of(7, 0), weekdays, now)
        assertEquals("2026-11-26T07:00", at.atZone(zone).toLocalDateTime().toString())
    }

    @Test fun sameDayLaterInTheWeek() {
        val at = NovaAlarms.nextAt(LocalTime.of(10, 0), setOf(DayOfWeek.WEDNESDAY), now)
        assertEquals("2026-11-25T10:00", at.atZone(zone).toLocalDateTime().toString())
    }

    @Test fun enabledAlarmsAreListedWithTheirDays() {
        val active = OwnAlarm("1", 7, 0, setOf(DayOfWeek.MONDAY), "Зарядка").toActive()
        assertTrue(active.enabled)
        assertEquals(LocalTime.of(7, 0), active.time)
        assertEquals("Зарядка", active.label)
    }
}
