package ai.nova.app

import ai.nova.app.health.StepSync
import org.junit.Assert.assertEquals
import org.junit.Test

/** Шаги с телефона: счётчик отдаёт сумму с загрузки, поэтому «сегодня» считаем от утренней точки. */
class StepSyncTest {
    @Test fun firstRunOfDayStartsFromZero() {
        val (steps, base) = StepSync.stepsSinceMorning(total = 54_677f, savedDay = null, savedBase = -1f, today = "2026-11-23")
        assertEquals(0, steps)
        assertEquals(54_677f, base)
    }

    @Test fun sameDayCountsTheDifference() {
        val (steps, base) = StepSync.stepsSinceMorning(55_477f, "2026-11-23", 54_677f, "2026-11-23")
        assertEquals(800, steps)
        assertEquals(54_677f, base)
    }

    @Test fun newDayResetsTheBase() {
        val (steps, base) = StepSync.stepsSinceMorning(60_000f, "2026-11-22", 54_677f, "2026-11-23")
        assertEquals(0, steps)
        assertEquals(60_000f, base)
    }

    @Test fun rebootResetDoesNotGoNegative() {
        val (steps, base) = StepSync.stepsSinceMorning(120f, "2026-11-23", 54_677f, "2026-11-23")
        assertEquals(0, steps)
        assertEquals(120f, base)
    }
}
