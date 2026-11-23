package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.health.BriefBlocks
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Пункты из списка требований: уровни важности напоминаний, выбор платформы видео,
 * настройка блоков сводок, отмена последнего действия телефона и журнал действий.
 */
class ReviewFixes31Test {

    private class Device : DeviceController {
        val got = mutableListOf<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult {
            got += command
            return DeviceResult("Открываю.")
        }
    }

    private fun env(device: Device = Device()) = TestEnv(device = device).apply { settings = AssistantSettings(useAI = false) }

    // ---------------------------------------------------------------- Критичные напоминания

    @Test fun criticalReminderFromPhrase() = runTest {
        val e = env()
        val reply = e.engine.handle("напомни критично выпить таблетку через 2 минуты").text
        val reminder = e.store.reminders.active().single()
        assertEquals("Выпить таблетку", reminder.text)
        assertTrue(e.critical.single() == reminder.id, "критичный уровень должен дойти до приложения")
        assertTrue(e.persistent.contains(reminder.id), "критичное напоминание повторяется до «Готово»")
        assertTrue(reply.contains("критичн", ignoreCase = true), reply)
    }

    @Test fun cancellingReminderStopsTheAlarm() = runTest {
        val e = env()
        e.engine.handle("напомни критично выпить таблетку через 2 минуты")
        val reminder = e.store.reminders.active().single()
        e.engine.handle("отмени напоминание выпить таблетку")
        e.engine.handle("да")
        assertTrue(e.cancelled.contains(reminder.id), "после отмены приложение должно снять повторы и критичный сигнал")
    }

    @Test fun normalReminderIsNotCritical() = runTest {
        val e = env()
        e.engine.handle("напомни выпить таблетку через 2 минуты")
        assertTrue(e.critical.isEmpty())
        assertTrue(e.persistent.isEmpty())
    }

    @Test fun modelCannotMakeRecurringReminderCritical() {
        // Важность из ответа модели: critical допускается только для разового напоминания.
        val parser = ActionParser(java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"))
        val plan = parser.parse(
            """{"reply":"","actions":[{"type":"create_reminder","text":"Полить цветы","in_minutes":60,"importance":"critical"}]}""",
        )
        val single = assertIs<AssistantAction.CreateReminder>(plan.actions.single())
        assertTrue(single.critical)

        val recurring = parser.parse(
            """{"reply":"","actions":[{"type":"create_reminder","text":"Полить цветы","recurrence":{"frequency":"daily","time":"09:00"},"importance":"critical"}]}""",
        )
        val daily = assertIs<AssistantAction.CreateReminder>(recurring.actions.single())
        assertFalse(daily.critical, "повторяющееся напоминание не должно быть будильником")
    }

    // ---------------------------------------------------------------- Платформа видео

    @Test fun videoSearchAsksPlatformOnce() = runTest {
        val device = Device()
        val e = env(device)
        val question = e.engine.handle("найди видео про рыбалку").text
        assertTrue(question.contains("YouTube") && question.contains("Rutube"), question)
        assertTrue(device.got.isEmpty(), "до ответа ничего не открываем")

        val answer = e.engine.handle("рутуб").text
        assertEquals(VideoPlatform.RUTUBE, e.chosenVideoPlatform)
        val play = assertIs<DeviceCommand.Play>(device.got.single())
        assertEquals(VideoPlatform.RUTUBE, play.platform)
        assertEquals("рыбалка", play.query)
        assertTrue(answer.contains("Rutube"), answer)

        // Дальше платформа уже выбрана — второго вопроса нет.
        val again = e.engine.handle("найди видео про грибы").text
        assertFalse(again.contains("Rutube?"), again)
        assertEquals(2, device.got.size)
        assertEquals(VideoPlatform.RUTUBE, assertIs<DeviceCommand.Play>(device.got.last()).platform)
    }

    @Test fun platformAnswerParsing() {
        assertEquals(VideoPlatform.YOUTUBE, VideoChoice.parse("ютуб"))
        assertEquals(VideoPlatform.YOUTUBE, VideoChoice.parse("на YouTube"))
        assertEquals(VideoPlatform.VK, VideoChoice.parse("вк"))
        assertEquals(VideoPlatform.VK, VideoChoice.parse("в контакте"))
        assertEquals(VideoPlatform.RUTUBE, VideoChoice.parse("Рутуб."))
        assertNull(VideoChoice.parse("не знаю"))
    }

    @Test fun unrelatedPhraseDropsVideoQuestion() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("найди видео про рыбалку")
        val other = e.engine.handle("добавь задачу купить хлеб").text
        assertTrue(other.contains("Купить хлеб", ignoreCase = true), other)
        assertTrue(e.chosenVideoPlatform == null)
        assertTrue(device.got.isEmpty())
    }

    // ---------------------------------------------------------------- Блоки сводок

    @Test fun briefBlocksParseAndStore() {
        assertEquals(BriefBlocks.ALL, BriefBlocks.parse(null))
        assertEquals(setOf(BriefBlocks.WEATHER), BriefBlocks.parse("weather"))
        assertEquals(emptySet(), BriefBlocks.parse(""))
        assertEquals(setOf(BriefBlocks.WEATHER), BriefBlocks.parse("weather,nonsense"))
        assertEquals("weather,tasks", BriefBlocks.store(setOf(BriefBlocks.TASKS, BriefBlocks.WEATHER)))
    }

    @Test fun summarySkipsDisabledBlocks() = runTest {
        val e = env()
        e.store.habits.log(ai.nova.core.health.Habits.MOOD, "ровно", 4.0, e.time.now())
        e.engine.handle("выпила стакан воды")
        val all = ai.nova.core.health.Habits(e.store.habits, e.time).daySummaryText(1, "Потрачено: 300 ₽.", BriefBlocks.ALL)
        assertTrue(all.contains("Воды"), all)
        assertTrue(all.contains("Потрачено"), all)
        assertTrue(all.contains("Закрыто задач"), all)

        val noHealth = ai.nova.core.health.Habits(e.store.habits, e.time)
            .daySummaryText(1, "Потрачено: 300 ₽.", setOf(BriefBlocks.TASKS, BriefBlocks.SPEND))
        assertFalse(noHealth.contains("Воды"), noHealth)
        assertFalse(noHealth.contains("Настроение"), noHealth)
        assertTrue(noHealth.contains("Потрачено"), noHealth)

        val nothing = ai.nova.core.health.Habits(e.store.habits, e.time).daySummaryText(1, null, emptySet())
        assertEquals("", nothing)
    }

    // ---------------------------------------------------------------- Отмена и журнал

    @Test fun undoLastActionPhrase() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("отмени последнее действие")
        assertTrue(device.got.any { it == DeviceCommand.UndoDevice }, device.got.toString())
    }

    @Test fun journalKeepsHumanDescription() = runTest {
        val e = env()
        e.engine.handle("напомни позвонить маме через час")
        e.engine.handle("запиши идею про приложение для дачников")
        assertTrue(e.journal.any { it.contains("напоминание «Позвонить маме»") }, e.journal.toString())
        assertTrue(e.journal.any { it.contains("заметка") }, e.journal.toString())
    }

    // ---------------------------------------------------------------- Карты, включая 2ГИС

    @Test fun mapsAppParsing() {
        assertEquals(MapsApp.TWOGIS, MapsApp.parse("в 2ГИС"))
        assertEquals(MapsApp.TWOGIS, MapsApp.parse("2 гис"))
        assertEquals(MapsApp.YANDEX, MapsApp.parse("в яндекс картах"))
        assertEquals(MapsApp.GOOGLE, MapsApp.parse("через гугл карты"))
        assertNull(MapsApp.parse("домой"))
    }

    @Test fun navigateInto2gisFromPhrase() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("построй маршрут в 2ГИС до вокзала")
        val route = assertIs<DeviceCommand.Navigate>(device.got.single())
        assertEquals(MapsApp.TWOGIS, route.app)
        assertEquals("вокзал", route.destination)
    }

    @Test fun navigateKeepsPlainDestination() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("как доехать до кафе в парке")
        val route = assertIs<DeviceCommand.Navigate>(device.got.single())
        assertNull(route.app)
        assertEquals("кафе в парке", route.destination)
    }

    @Test fun summaryOfDeviceCommand() {
        assertEquals("команда телефону: фонарик включён", ActionSummary.describe(AssistantAction.Device(DeviceCommand.Flashlight(true))))
        assertEquals("команда телефону: видео «рыбалку»", ActionSummary.describe(AssistantAction.Device(DeviceCommand.Play("рыбалку", youtube = true))))
        assertEquals("задача «Купить хлеб»", ActionSummary.describe(AssistantAction.CreateTask("Купить хлеб", "", null, null)))
    }
}
