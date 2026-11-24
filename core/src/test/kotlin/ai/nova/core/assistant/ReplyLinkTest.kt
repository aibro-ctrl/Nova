package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.model.MessageRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ссылка на найденное остаётся в истории чата: её можно нажать ещё раз, а вслух она не читается. */
class ReplyLinkTest {
    private val url = "https://ya.ru/search/?text=%D0%B1%D0%BE%D1%80%D1%89"

    /** Телефон, который «открывает» поиск и возвращает ссылку результата. */
    private class Device(private val link: String) : DeviceController {
        override suspend fun perform(command: DeviceCommand) = DeviceResult("Открыла поиск «борщ» в Яндексе.", link = link)
    }

    @Test fun searchLinkStaysInHistory() = runTest {
        val env = TestEnv(device = Device(url)).apply { settings = AssistantSettings(useAI = false) }
        val reply = env.engine.handle("найди в интернете рецепт борща")
        // В слух — только текст: адрес в озвучке бесполезен.
        assertEquals("Открыла поиск «борщ» в Яндексе.", reply.text)
        assertEquals(url, reply.link)
        val stored = env.store.conversations.recent(10).last { it.role == MessageRole.ASSISTANT }
        assertTrue(stored.content.contains("[Открыть снова]($url)"), stored.content)
        // Модели и озвучке служебная разметка не нужна.
        assertEquals("Открыла поиск «борщ» в Яндексе.", MessageLinks.strip(stored.content))
        assertEquals(url, MessageLinks.url(stored.content))
    }

    @Test fun plainReplyHasNoLink() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        val reply = env.engine.handle("сколько дней до нового года")
        assertNull(reply.link)
        assertEquals(reply.text, MessageLinks.strip(reply.text))
        assertNull(MessageLinks.url(reply.text))
    }
}
