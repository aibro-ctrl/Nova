package ai.nova.core.health

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Мой день»: сводка должна говорить не только про воду и настроение,
 * но и про шаги, сон, вес, давление и напоминания без «Готово».
 */
class MyDayTest {
    @Test fun myDaySpeaksAboutStepsSleepWeightAndPressure() = runTest {
        val e = TestEnv()
        e.engine.handle("сегодня прошёл 8000 шагов")
        e.engine.handle("спала 7 часов")
        e.engine.handle("мой вес сегодня 106")
        e.engine.handle("сегодня давление 125 на 80")

        val text = Habits(e.store.habits, e.time).daySummaryText(1, null)
        assertTrue(text.contains("8000 шагов"), text)
        assertTrue(text.contains("Сон: 7 часов"), text)
        assertTrue(text.contains("Вес: 106 кг"), text)
        assertTrue(text.contains("Давление: 125 на 80"), text)
    }

    @Test fun myDayVoiceCommandReturnsTheSameSummary() = runTest {
        val e = TestEnv()
        e.engine.handle("сегодня прошёл 8000 шагов")

        val reply = e.engine.handle("мой день").text
        assertTrue(reply.startsWith("Итоги дня."), reply)
        assertTrue(reply.contains("Шаги: 8000 шагов"), reply)
    }

    @Test fun myDayReportsUnconfirmedReminders() = runTest {
        val e = TestEnv()
        val text = Habits(e.store.habits, e.time).daySummaryText(0, null, missed = 2)
        assertTrue(text.contains("Не подтверждено напоминаний: 2"), text)

        val clean = Habits(e.store.habits, e.time).daySummaryText(0, null, missed = 0)
        assertFalse(clean.contains("Не подтверждено"), clean)
    }

    @Test fun disabledHealthBlockHidesVitals() = runTest {
        val e = TestEnv()
        e.engine.handle("сегодня прошёл 8000 шагов")
        val text = Habits(e.store.habits, e.time)
            .daySummaryText(0, null, setOf(BriefBlocks.TASKS))
        assertFalse(text.contains("Шаги"), text)
    }

    /** Заметки и идеи лежат на телефоне и выгружаются по просьбе: «выгрузи заметки в таблицу». */
    @Test fun notesAndIdeasCanBeExportedFromThePhone() = runTest {
        val e = TestEnv()
        e.engine.handle("создай заметку список покупок: молоко, хлеб")
        e.engine.handle("у меня идея: приложение для холодильника")

        val action = ai.nova.core.assistant.PersonalCommands.parse(
            "выгрузи заметки в таблицу", "выгрузи заметки в таблицу", e.time.today(),
        )
        assertEquals("notes", (action as? ai.nova.core.assistant.AssistantAction.Export)?.kind)

        val ls = e.store.notes.all()
        assertTrue(ls.isNotEmpty())
        val csv = ai.nova.core.export.CsvExport.notes(ls, e.time.zone())
        assertTrue(csv.contains("Дата;Тип;Название;Текст;Теги"), csv)
        assertTrue(csv.contains("идея"), csv)
    }

    /** Шаги от датчика заменяют ручную запись за тот же день — в отчёте не двоится. */
    @Test fun sensorStepsReplaceManualOnesForTheDay() = runTest {
        val e = TestEnv()
        e.engine.handle("сегодня прошёл 5000 шагов")
        val dayStart = e.time.today().atStartOfDay(e.time.zone()).toInstant()
        e.store.habits.putDay(Vitals.STEPS, "счётчик телефона", 8000.0, dayStart, e.time.now())

        val today = e.store.habits.since(dayStart, Vitals.STEPS)
        assertEquals(1, today.size)
        assertEquals(8000.0, today.single().amount)
    }
}
