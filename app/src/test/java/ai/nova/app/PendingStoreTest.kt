package ai.nova.app

import ai.nova.app.reminders.PendingStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** «Мой день» считает только сегодняшние неподтверждённые напоминания. */
class PendingStoreTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    private fun at(hour: Int, day: LocalDate = today): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsOnlyToday() {
        assertEquals(2, PendingStore.countToday(listOf(at(9), at(18), at(9, today.minusDays(1))), today, zone))
    }

    @Test
    fun emptyWhenEverythingConfirmed() {
        assertEquals(0, PendingStore.countToday(emptyList(), today, zone))
    }
}
