package ai.nova.core.assistant

import ai.nova.core.ScriptedAI
import ai.nova.core.TestEnv
import ai.nova.core.ai.AIRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Разговор не должен превращаться в «не поняла команду».
 * Сценарий из отзыва: «Начни разговор» → «Доброе утро! … как у вас дела?» → «Всё хорошо, спасибо, как у тебя?».
 */
class DialogChatTest {

    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun replyToHowAreYouIsAnswered() = runTest {
        val e = env()
        e.engine.handle("начни разговор")
        val answer = e.engine.handle("все хорошо, спасибо, как у тебя?").text
        assertFalse(answer.contains("не поняла", ignoreCase = true), answer)
        assertTrue(answer.contains("А у вас как?", ignoreCase = true), answer)
    }

    @Test fun startOfDialogIsAnsweredImmediatelyAndKeepsListening() = runTest {
        val e = env()
        val reply = e.engine.handle("начни разговор")
        assertFalse(reply.text.contains("не поняла", ignoreCase = true), reply.text)
        assertTrue(reply.text.length > 3, reply.text)
        assertTrue(reply.expectFollowUp, "после «начни разговор» Нова должна продолжать слушать")
    }

    @Test fun dialogOpenersAreRecognised() {
        listOf(
            "начни разговор", "Начни разговор!", "давай поговорим", "давай поболтаем",
            "поговори со мной", "поболтай со мной", "ну давай поговорим", "хочу поговорить",
            "начнем беседу", "начнём разговор", "поговорим",
        ).forEach { assertTrue(ai.nova.core.ai.LocalChat.isDialogOpener(it), it) }
        listOf("включи фонарик", "какая погода", "поговорим позже?", "начни с заметок").forEach {
            assertFalse(ai.nova.core.ai.LocalChat.isDialogOpener(it), it)
        }
    }

    @Test fun howAreYouIsChatNotACommand() = runTest {
        val e = env()
        assertTrue(e.engine.handle("как у тебя дела?").text.contains("как", ignoreCase = true))
        assertTrue(e.engine.handle("как настроение").text.isNotBlank())
    }

    @Test fun modelRefusalOnChatIsRetriedAsConversation() = runTest {
        // Модель с командным промптом отвечает «не поняла команду», а как собеседник — нормально.
        val ai = ScriptedAI { request: AIRequest ->
            if (request.system.contains("СТРОГО один JSON")) "Не поняла вашу команду." else "Кофе бодрит, но после обеда лучше не пить."
        }
        val e = TestEnv().apply { settings = AssistantSettings(useAI = true); this.ai = ai }
        val answer = e.engine.handle("что ты думаешь о кофе?").text
        assertFalse(answer.contains("не поняла", ignoreCase = true), answer)
        assertEquals("Кофе бодрит, но после обеда лучше не пить.", answer)
        assertEquals(2, ai.requests.size)
    }

    @Test fun commandStillWorksWhenModelRefusesChat() = runTest {
        val ai = ScriptedAI { request: AIRequest ->
            if (request.system.contains("СТРОГО один JSON")) {
                """{"reply":"Записала задачу «Позвонить маме».","actions":[{"type":"create_task","title":"Позвонить маме"}],"expect_followup":false,"topic":null}"""
            } else "не поняла"
        }
        val e = TestEnv().apply { settings = AssistantSettings(useAI = true); this.ai = ai }
        val answer = e.engine.handle("добавь задачу позвонить маме").text
        assertTrue(answer.contains("Позвонить маме"), answer)
        assertEquals(1, ai.requests.size)
    }

    @Test fun chatKeepsHistory() = runTest {
        val ai = ScriptedAI { request: AIRequest ->
            if (request.system.contains("СТРОГО один JSON")) "не поняла" else "Ответ на «${request.messages.last().content.take(20)}»"
        }
        val e = TestEnv().apply { settings = AssistantSettings(useAI = true); this.ai = ai }
        e.engine.handle("расскажи про луну")
        e.engine.handle("а что ты думаешь о солнце?")
        // Вторая разговорная просьба уходит с историей: модель видит прошлую реплику.
        assertTrue(ai.requests.last().messages.size >= 2, ai.requests.last().messages.toString())
    }
}
