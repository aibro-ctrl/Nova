package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.ai.AIProvider
import ai.nova.core.ai.AIProviderType
import ai.nova.core.ai.AIRequest
import ai.nova.core.ai.AIResponse
import ai.nova.core.ai.ChatMessage
import ai.nova.core.ai.LocalChat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Встроенная модель на телефоне отвечает первой; облачный AI подключается только если он включён,
 * доступен и модель на телефоне не справилась.
 */
class BuiltInFirstTest {

    /** Модель на телефоне: на вопрос отвечает сама, команду разбирать отказывается. */
    private class Phone(val answer: String?, val plan: String? = null) : LocalChat {
        var chatted = 0
        var planned = 0
        override val available = true
        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int, onChunk: ((String) -> Unit)?): String? {
            chatted++
            return answer
        }
        override suspend fun plan(system: String, phrase: String): String? {
            planned++
            return plan
        }
    }

    /** Облако, которое на всё отвечает своей болтовнёй и считает запросы. */
    private class Cloud(private val reply: String = "Ответ облака.") : AIProvider {
        var calls = 0
        override val type = AIProviderType.OPENAI
        override val model = "cloud"
        override suspend fun complete(request: AIRequest): AIResponse {
            calls++
            return AIResponse(
                """{"reply":"$reply","actions":[],"expect_followup":false,"topic":null}""",
                model = "cloud", stopReason = "stop",
            )
        }
    }

    private fun env(chat: LocalChat, cloud: AIProvider?) = TestEnv(localChat = chat).apply {
        settings = AssistantSettings(useAI = cloud != null)
        ai = cloud
    }

    @Test fun questionIsAnsweredOnThePhone() = runBlocking {
        val phone = Phone("Небо голубое из-за рассеяния света.")
        val cloud = Cloud()
        val reply = env(phone, cloud).engine.handle("почему небо голубое")
        assertEquals(0, cloud.calls, "облако не нужно: модель на телефоне ответила")
        assertEquals("Офлайн-модель", reply.provider)
        assertTrue(reply.text.contains("рассеяния"), reply.text)
    }

    @Test fun cloudAnswersWhenThePhoneModelFails() = runBlocking {
        val phone = Phone(null)
        val cloud = Cloud("Облако знает лучше.")
        val reply = env(phone, cloud).engine.handle("почему небо голубое")
        assertEquals(1, cloud.calls)
        assertTrue(reply.text.contains("Облако"), reply.text)
    }

    /** Облачная модель выключена — телефон отвечает сам, даже если модель на телефоне не справилась. */
    @Test fun cloudIsSilentWhenDisabled() = runBlocking {
        val phone = Phone("Ответ на телефоне.")
        val reply = env(phone, cloud = null).engine.handle("почему небо голубое")
        assertEquals("Ответ на телефоне.", reply.text)
    }

    /** Непонятную фразу модель на телефоне разбирает один раз: второй раз её не спрашиваем. */
    @Test fun unclearPhraseIsPlannedOnce() = runBlocking {
        val phone = Phone(answer = "Не знаю.", plan = null)
        val cloud = Cloud("Тогда решу я.")
        env(phone, cloud).engine.handle("петя просил передать привет бабушке")
        assertEquals(1, phone.planned, "модель спрашивают один раз, а не дважды")
    }
}
