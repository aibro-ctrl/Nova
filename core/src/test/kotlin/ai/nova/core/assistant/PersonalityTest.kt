package ai.nova.core.assistant

import ai.nova.core.ScriptedAI
import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Характер — это поведение модели, а не тембр голоса: в системный промпт уходит полноценный профиль,
 * а заботливая Нова ещё и предлагает помощь по реальным данным.
 */
class PersonalityTest {
    @Test fun threeDistinctInstructionSets() {
        val texts = Persona.entries.map { it.personality.instructions }
        assertEquals(3, texts.map { it.hashCode() }.toSet().size)
        texts.forEach { assertTrue(it.length > 600, "профиль слишком короткий: ${it.length}") }
        assertTrue(Persona.CARING.personality.instructions.contains("Проактивность"))
        assertTrue(Persona.BUSINESS.personality.instructions.contains("Сразу суть"))
        assertTrue(Persona.PLAYFUL.personality.instructions.contains("золотце"))
        // Запреты на выдумки есть у всех: характер не отменяет точность.
        texts.forEach { assertTrue(it.contains("факт", ignoreCase = true) || it.contains("данн", ignoreCase = true)) }
    }

    @Test fun parametersReachPrompt() {
        Persona.entries.forEach { p ->
            val block = p.personality.block
            assertTrue(block.contains("Параметры ответа"), block)
            assertTrue(block.contains(p.personality.responseLength.ru), block)
        }
        assertNotEquals(Persona.BUSINESS.personality.parameters, Persona.PLAYFUL.personality.parameters)
    }

    @Test fun promptBuilderUsesSelectedPersona() {
        val base = PromptBuilder.build(
            "Нова", java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"),
            emptyList(), emptyList(), null, null, false,
        )
        val prompts = Persona.entries.map { p ->
            PromptBuilder.build(
                "Нова", java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"),
                emptyList(), emptyList(), null, null, false, persona = p,
            )
        }
        assertEquals(3, prompts.toSet().size)
        prompts.forEach { assertTrue(it.contains("Характер:"), it.take(200)) }
        assertTrue(prompts[1].contains("без вступлений"))
        assertTrue(prompts[2].contains("мой хороший"))
        // Без явного характера — заботливая, как и раньше.
        assertTrue(base.contains(Persona.CARING.personality.parameters))
    }

    @Test fun engineSendsPersonaToModel() = runTest {
        Persona.entries.forEach { p ->
            val ai = ScriptedAI { """{"reply":"Хорошо.","actions":[],"expect_followup":false,"topic":null}""" }
            val e = TestEnv().apply { settings = AssistantSettings(useAI = true, persona = p) }
            e.ai = ai
            e.engine.handle("расскажи что-нибудь о море")
            val system = ai.requests.last().system
            assertTrue(system.contains(p.personality.parameters), "характер ${p.wire} не попал в промпт")
        }
    }

    @Test fun suggestionsOnlyFromRealData() {
        assertNull(Suggestions.caring(SuggestionData(), emptySet()))
        val overdue = Suggestions.caring(SuggestionData(overdue = 2), emptySet())
        assertTrue(overdue!!.text.contains("2 просроченные задачи"), overdue.text)
        assertTrue(Suggestions.caring(SuggestionData(overdue = 2), setOf("overdue")) == null)
        assertEquals("1 минуту", Suggestions.plural(1, "минуту", "минуты", "минут"))
        assertEquals("3 минуты", Suggestions.plural(3, "минуту", "минуты", "минут"))
        assertEquals("11 минут", Suggestions.plural(11, "минуту", "минуты", "минут"))
        val reminder = Suggestions.caring(SuggestionData(reminderInMinutes = 40, reminderText = "позвонить маме"), emptySet())
        assertTrue(reminder!!.text.contains("через 40 минут"), reminder.text)
        // Про траты Нова не встревает: деньги человек спрашивает сам.
        assertNull(Suggestions.caring(SuggestionData(spentToday = 3000, usualDaySpend = 1000), emptySet()))
    }

    @Test fun caringSuggestsOnceAndThenStaysQuiet() = runTest {
        val e = TestEnv()
        e.store.tasks.create("Сдать отчёт", dueDate = LocalDate.of(2026, 9, 24), dueTime = LocalTime.of(18, 0))
        val first = e.engine.handle("привет").text
        assertTrue(first.contains("просроченная задача"), first)
        // Второй ответ — без предложения (пауза между советами).
        assertTrue(!e.engine.handle("привет").text.contains("просроченная"), e.engine.handle("привет").text)
        // Деловая и шутливая в это не вмешиваются.
        val business = TestEnv().apply { settings = AssistantSettings(useAI = false, persona = Persona.BUSINESS) }
        business.store.tasks.create("Сдать отчёт", dueDate = LocalDate.of(2026, 9, 24), dueTime = LocalTime.of(18, 0))
        assertTrue(!business.engine.handle("привет").text.contains("просроченная"))
    }

    @Test fun sensitiveRepliesHaveNoSuggestion() = runTest {
        val e = TestEnv()
        e.store.tasks.create("Сдать отчёт", dueDate = LocalDate.of(2026, 9, 24), dueTime = LocalTime.of(18, 0))
        e.engine.handle("мои расходы за сегодня")
        e.time.advanceMillis(10 * 60 * 1000)
        e.engine.handle("сколько у меня денег на карте")
        val text = e.engine.handle("какие сообщения мне пришли").text
        assertTrue(!text.contains("просроченная"), text)
    }
}
