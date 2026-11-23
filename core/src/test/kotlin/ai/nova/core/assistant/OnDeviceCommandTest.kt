package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.ai.ChatMessage
import ai.nova.core.ai.CommandPlanner
import ai.nova.core.ai.LocalChat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Фразу, которую не поняли правила, пробует понять офлайн-модель: она переписывает её в короткую
 * команду, а выполняет её обычный разбор. Непонятный ответ модели ничего не выполняет.
 */
class OnDeviceCommandTest {
    private class Planner(private val answer: String?) : LocalChat {
        var planned = 0
        var chatted = 0
        var lastSystem = ""

        override val available = true

        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int, onChunk: ((String) -> Unit)?): String {
            chatted++
            return "Ответ модели."
        }

        override suspend fun plan(system: String, phrase: String): String? {
            planned++
            lastSystem = system
            return answer
        }
    }

    private class Unavailable : LocalChat {
        override val available = false
        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int, onChunk: ((String) -> Unit)?): String? = null
    }

    /** Правила такую фразу не разбирают, и это не разговор — её отдают модели. */
    private val UNCLEAR = "петя просил передать привет бабушке"

    private fun env(chat: LocalChat) = TestEnv(localChat = chat).apply { settings = AssistantSettings(useAI = false) }

    @Test
    fun unclearPhraseBecomesReminderThroughTheModel() = runBlocking {
        val planner = Planner("напомни завтра в 10 утра позвонить маме")
        val e = env(planner)
        val reply = e.engine.handle(UNCLEAR)
        assertEquals(1, planner.planned, "модель должна была разобрать фразу")
        val reminder = e.store.reminders.active().single()
        assertEquals("Позвонить маме", reminder.text)
        assertTrue(reply.text.contains("Напомню", ignoreCase = true), "ответ: ${reply.text}")
    }

    @Test
    fun modelSayingNoKeepsTheRulesBehaviour() = runBlocking {
        val planner = Planner("нет")
        val e = env(planner)
        val reply = e.engine.handle(UNCLEAR)
        assertEquals(1, planner.planned)
        assertTrue(e.store.reminders.active().isEmpty())
        assertTrue(reply.text.contains("Сохранить это как заметку?"), "ответ: ${reply.text}")
    }

    @Test
    fun commandTheRulesDoNotUnderstandIsIgnored() = runBlocking {
        val planner = Planner("сделай что-нибудь волшебное")
        val e = env(planner)
        e.engine.handle(UNCLEAR)
        assertEquals(1, planner.planned)
        assertTrue(e.store.reminders.active().isEmpty())
        assertTrue(e.store.notes.all().isEmpty())
    }

    @Test
    fun chatPhraseIsNotSentToTheCommandPlanner() = runBlocking {
        val planner = Planner("задача позвонить маме")
        val e = env(planner)
        e.engine.handle("почему небо голубое?")
        assertEquals(0, planner.planned, "разговор разбирает не планировщик команд")
        assertEquals(1, planner.chatted)
    }

    @Test
    fun withoutTheModelNothingIsAsked() = runBlocking {
        val e = TestEnv(localChat = Unavailable()).apply { settings = AssistantSettings(useAI = false) }
        e.engine.handle(UNCLEAR)
        assertTrue(e.store.reminders.active().isEmpty())
    }

    @Test
    fun plannerPromptIsShort() {
        val prompt = CommandPlanner.systemPrompt()
        assertTrue(prompt.length < 1200, "промпт читает модель перед каждым ответом: ${prompt.length} символов")
        assertTrue(prompt.contains("напомни"), "в промпте есть примеры команд")
    }

    @Test
    fun plannerAnswerIsCleaned() {
        assertEquals("напомни позвонить маме", CommandPlanner.command("  «напомни позвонить маме»  "))
        assertEquals("расход 500 рублей на кофе", CommandPlanner.command("Ответ: расход 500 рублей на кофе"))
        assertEquals("нет ничего", CommandPlanner.command("- нет ничего"))
        assertNull(CommandPlanner.command("нет"))
        assertNull(CommandPlanner.command("Извините, не могу помочь"))
        assertNull(CommandPlanner.command("{\"reply\":\"ок\"}"))
        assertNull(CommandPlanner.command("   "))
        assertNull(CommandPlanner.command(null))
        assertNull(CommandPlanner.command("напомни " + "очень ".repeat(40) + "позвонить"))
    }
}
