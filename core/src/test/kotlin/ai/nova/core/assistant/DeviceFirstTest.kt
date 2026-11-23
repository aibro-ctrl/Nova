package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.ai.AIProvider
import ai.nova.core.ai.AIProviderType
import ai.nova.core.ai.AIRequest
import ai.nova.core.ai.AIResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Облачный AI не должен перехватывать команды телефону: они выполняются на устройстве. */
class DeviceFirstTest {

    /** Модель, которая на любую фразу отвечает задачей «Осмотреть музей» — так работало облако. */
    private class TaskAi : AIProvider {
        var calls = 0
        override val type = AIProviderType.OPENAI
        override val model = "task-ai"
        override suspend fun complete(request: AIRequest): AIResponse {
            calls++
            return AIResponse(
                """{"reply":"Добавила задачу «Осмотреть музей»","actions":[{"type":"task","title":"Осмотреть музей"}],"expect_followup":false,"topic":null}""",
                model = "task-ai", stopReason = "stop",
            )
        }
    }

    private class Recorder : DeviceController {
        val got = mutableListOf<DeviceCommand>()
        override suspend fun perform(command: DeviceCommand): DeviceResult { got += command; return DeviceResult("Строю маршрут.") }
    }

    private fun env(device: DeviceController, ai: AIProvider) =
        TestEnv(device = device).apply { settings = AssistantSettings(useAI = true); this.ai = ai }

    @Test fun routePhrasesStayOnDevice() = runTest {
        for (phrase in listOf("маршрут музей", "найди маршрут домой", "постройки маршрут до дом", "построй маршрут до вокзала")) {
            val ai = TaskAi()
            val rec = Recorder()
            val e = env(rec, ai)
            e.engine.handle(phrase)
            assertEquals(0, ai.calls, "$phrase ушло в облако")
            val navigate = rec.got.filterIsInstance<DeviceCommand.Navigate>().firstOrNull()
            assertTrue(navigate != null, "$phrase → ${rec.got}")
            assertTrue(e.store.tasks.all().none { it.title.contains("Осмотреть") }, "$phrase создало задачу")
        }
    }

    /** Без места маршрут не выдумываем и в облако не отправляем — переспрашиваем. */
    @Test fun routeWithoutPlaceAsksOnDevice() = runTest {
        val ai = TaskAi()
        val e = env(Recorder(), ai)
        val reply = e.engine.handle("найди маршрут")
        assertEquals(0, ai.calls)
        assertTrue(reply.text.contains("Куда построить маршрут"), reply.text)
    }

    /** Вопросы облако по-прежнему получает: локальные правила их не разбирают. */
    @Test fun questionsStillGoToCloud() = runTest {
        val ai = TaskAi()
        val e = env(Recorder(), ai)
        e.engine.handle("расскажи что-нибудь интересное про космос")
        assertEquals(1, ai.calls)
    }
}
