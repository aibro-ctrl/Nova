package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «В календарь» после созданного плана: событие уходит в календарь телефона по одной фразе. Сейчас 25.09.2026, 12:00 МСК. */
class PlanCalendarTest {
    private val zone = ZoneId.of("Europe/Moscow")

    private class Device : DeviceController {
        val got = mutableListOf<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult { got += command; return DeviceResult("Добавила в календарь.") }
    }

    private fun env(device: Device) = TestEnv(
        time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), zone),
        device = device,
    ).apply { settings = AssistantSettings(useAI = false) }

    @Test fun taskWithDateGoesToCalendar() = runTest {
        val device = Device()
        val e = env(device)
        val reply = e.engine.handle("добавь задачу отвезти документы завтра в 15:00").text
        assertTrue(reply.contains("в календарь"), reply)
        assertEquals(1, e.plans.size)

        e.engine.handle("в календарь")
        val event = device.got.filterIsInstance<DeviceCommand.CalendarEvent>().single()
        assertEquals("Отвезти документы", event.title)
        assertEquals(LocalDateTime.of(2026, 9, 26, 15, 0), event.start?.atZone(zone)?.toLocalDateTime())
        assertEquals(false, event.allDay)
    }

    @Test fun reminderGoesToCalendar() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("напомни завтра в 9 позвонить в поликлинику")
        e.engine.handle("добавь это в календарь")
        val event = device.got.filterIsInstance<DeviceCommand.CalendarEvent>().single()
        assertEquals("Позвонить в поликлинику", event.title)
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), event.start?.atZone(zone)?.toLocalDateTime())
    }

    @Test fun calendarIsNotOfferedTwice() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("добавь задачу отвезти документы завтра")
        e.engine.handle("в календарь")
        e.engine.handle("в календарь")
        assertEquals(1, device.got.count { it is DeviceCommand.CalendarEvent })
    }

    @Test fun withoutPlanInCalendarIsJustACalendarQuestion() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("в календарь")
        assertTrue(device.got.none { it is DeviceCommand.CalendarEvent })
        assertNull(e.plans.firstOrNull())
    }
}
