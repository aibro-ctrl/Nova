package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.model.MessageRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryDeliveryTest {

    @Test fun greetingCallsByName() {
        assertEquals("Доброе утро, Саня!", Persona.CARING.briefGreeting(7, "Саня"))
        assertEquals("Добрый день, Саня!", Persona.CARING.briefGreeting(13, "Саня"))
        assertEquals("Добрый вечер, Саня!", Persona.CARING.briefGreeting(20, "Саня"))
        assertEquals("Доброй ночи, Саня!", Persona.CARING.briefGreeting(2, "Саня"))
    }

    @Test fun greetingWithoutNameStaysImpoverished() {
        // Имени нет — просто приветствие, без запятой и пустого места.
        assertEquals("Доброе утро!", Persona.CARING.briefGreeting(8, null))
        assertEquals("Доброе утро!", Persona.CARING.briefGreeting(8, "  "))
    }

    @Test fun scheduledReportIsStoredInHistoryAndCanBeRepeated() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false, userName = "Саня") }
        val text = "Доброе утро, Саня!\nЗадачи: купить хлеб, позвонить в поликлинику\nНапоминания: 10:00 выпить таблетку"
        env.engine.recordScheduledReport(text)
        val saved = env.store.conversations.recent(10)
        assertTrue(saved.any { it.role == MessageRole.ASSISTANT && it.content == text }, "сводка должна лежать в истории целиком")
        // «Повтори» читает сводку ещё раз — её не надо искать в пуше.
        assertEquals(text, env.engine.handle("повтори").text)
    }

    @Test fun emptyReportIsNotStored() = runTest {
        val env = TestEnv()
        env.engine.recordScheduledReport("   ")
        assertTrue(env.store.conversations.recent(10).isEmpty())
    }
}
