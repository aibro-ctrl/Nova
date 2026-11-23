package ai.nova.core.ai

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalChatTest {
    private class FakeChat : LocalChat {
        val asked = ArrayList<String>()
        override val available = true
        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int, onChunk: ((String) -> Unit)?): String {
            asked += history.last().content
            return "Из-за рассеяния света в атмосфере."
        }
    }

    @Test fun questionsGoToLocalModelCommandsDoNot() = runTest {
        val chat = FakeChat()
        val env = TestEnv(localChat = chat).apply { settings = AssistantSettings(useAI = false) }
        val r = env.engine.handle("почему небо голубое")
        assertEquals("Из-за рассеяния света в атмосфере.", r.text)
        assertEquals(listOf("почему небо голубое"), chat.asked)
        env.engine.handle("запиши расход 300 рублей на кофе")
        assertEquals(1, env.store.expenses.all().size)
        env.engine.handle("что у меня на сегодня")
        assertEquals(1, chat.asked.size)
    }

    /** Сбой облака: второй вопрос не ждёт тот же таймаут, а сразу идёт в модель на телефоне. */
    class CountingAi(private val fail: Boolean) : AIProvider {
        override val type = AIProviderType.OPENAI
        override val model = "x"
        var calls = 0
        override suspend fun complete(request: AIRequest): AIResponse {
            calls++
            if (fail) throw AIException.Network(null)
            return AIResponse("""{"reply":"Из облака","actions":[]}""", model = "x", stopReason = null)
        }
    }

    /**
     * Сбой облака запоминается: следующий вопрос не ждёт тот же таймаут.
     * Проверяем без модели на телефоне — иначе вопрос вообще не доходит до облака.
     */
    @Test fun failedCloudIsSkippedForAWhile() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = true) }
        val ai = CountingAi(fail = true)
        env.ai = ai
        env.engine.handle("почему небо голубое")
        env.engine.handle("а почему закат красный")
        assertEquals(1, ai.calls, "второй раз облако не дёргаем — оно только что не ответило")
    }

    /** Модель на телефоне ответила — облако не тратится. */
    @Test fun phoneModelAnswersBeforeCloud() = runTest {
        val chat = FakeChat()
        val env = TestEnv(localChat = chat).apply { settings = AssistantSettings(useAI = true) }
        val ai = CountingAi(fail = false)
        env.ai = ai
        val first = env.engine.handle("почему небо голубое")
        assertEquals("Из-за рассеяния света в атмосфере.", first.text)
        assertEquals(0, ai.calls)
    }

    @Test fun looksLikeChat() {
        listOf("почему небо голубое", "как приготовить плов", "посоветуй фильм на вечер", "что лучше чай или кофе", "расскажи анекдот про программиста", "можно ли есть грибы сырыми", "ты любишь музыку")
            .forEach { assertTrue(LocalChat.looksLikeChat(it), it) }
        listOf("купила хлеб", "мама приедет в субботу", "позвонить врачу")
            .forEach { assertFalse(LocalChat.looksLikeChat(it), it) }
    }
}

/** Род в системном промпте: маленькая модель должна говорить о себе и о собеседнике в правильном роде. */
class PromptGenderTest {
    @Test fun assistantGenderFollowsName() {
        val nova = LocalChat.systemPrompt("Нова", "Саня")
        assertTrue(nova.contains("от женского лица"), nova)
        assertTrue(nova.contains("не создана"), nova)

        val jarvis = LocalChat.systemPrompt("Джарвис", "Саня")
        assertTrue(jarvis.contains("от мужского лица"), jarvis)
        assertTrue(jarvis.contains("не создан Google"), jarvis)
    }

    @Test fun userGenderFollowsName() {
        assertTrue(LocalChat.systemPrompt("Нова", "Мария").contains("Это женщина"), "женское имя должно давать женский род")
        assertTrue(LocalChat.systemPrompt("Нова", "Максим").contains("Это мужчина"), "мужское имя должно давать мужской род")
        // Неоднозначное имя — никаких догадок.
        assertFalse(LocalChat.systemPrompt("Нова", "Саша").contains("Это женщина"))
        assertFalse(LocalChat.systemPrompt("Нова", "Саша").contains("Это мужчина"))
        // Без имени — только ассистент.
        assertFalse(LocalChat.systemPrompt("Нова", null).contains("Собеседника зовут"))
    }
}
