package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** «Отправь последнюю заметку на почту», «поделись заметкой про борщ в телеграм». */
class ShareNoteTest {

    private class Device : DeviceController {
        val got = mutableListOf<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult {
            got += command
            return DeviceResult("Выберите, кому отправить.")
        }
    }

    private fun env(device: Device) = TestEnv(device = device).apply { settings = AssistantSettings(useAI = false) }

    @Test fun lastNoteGoesToMail() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.saveDictation("купить молоко и хлеб потом заехать в аптеку")
        e.engine.handle("отправь последнюю заметку на почту")
        val share = assertIs<DeviceCommand.Share>(device.got.single())
        assertEquals("почту", share.app)
        assertTrue(share.text.startsWith("Купить молоко и хлеб"))
        assertTrue(share.text.contains("Потом заехать в аптеку"))
    }

    @Test fun noteFoundByMeaningGoesToTelegram() = runTest {
        val device = Device()
        val e = env(device)
        e.store.notes.create(ai.nova.core.model.NoteKind.NOTE, "Борщ", "Свёкла, капуста, мясо")
        e.store.notes.create(ai.nova.core.model.NoteKind.NOTE, "Молоко", "Купить к завтраку")
        e.engine.handle("отправь заметку про борщ в телеграм")
        val share = assertIs<DeviceCommand.Share>(device.got.single())
        assertEquals("телеграм", share.app)
        assertEquals("Борщ\n\nСвёкла, капуста, мясо", share.text)
    }

    @Test fun lastIdeaGoesToTelegramNotTheNote() = runTest {
        val device = Device()
        val e = env(device)
        e.store.notes.create(ai.nova.core.model.NoteKind.IDEA, "Идея про ремонт", "Поменять плитку")
        e.store.notes.create(ai.nova.core.model.NoteKind.NOTE, "Список покупок", "Молоко, хлеб")
        e.engine.handle("отправь идею в телеграм")
        val share = assertIs<DeviceCommand.Share>(device.got.single())
        assertEquals("телеграм", share.app)
        assertEquals("Идея про ремонт\n\nПоменять плитку", share.text)
    }

    @Test fun nothingToSendIsExplained() = runTest {
        val device = Device()
        val e = env(device)
        val r = e.engine.handle("отправь последнюю заметку на почту")
        assertTrue(device.got.isEmpty())
        assertTrue(r.text.contains("нет заметок", ignoreCase = true), r.text)
    }

    @Test fun freeTextShareIsNotANote() = runTest {
        val device = Device()
        val e = env(device)
        e.engine.handle("отправь в телеграм привет всем")
        val share = assertIs<DeviceCommand.Share>(device.got.single())
        assertEquals("привет всем", share.text)
    }

    @Test fun shareIsBlockedOnLockScreen() = runTest {
        val device = Device()
        val e = TestEnv(device = device, lockPolicy = { LockPolicy.SAFE }).apply { settings = AssistantSettings(useAI = false) }
        e.engine.saveDictation("купить молоко")
        val r = e.engine.handle("отправь последнюю заметку на почту")
        assertTrue(device.got.isEmpty())
        assertTrue(r.text.contains("Разблокируйте"), r.text)
    }
}
