package ai.nova.core.agent

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import ai.nova.core.model.ShoppingItem
import ai.nova.core.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ядро агента: разбор составных фраз, реестр навыков, инструменты и постоянный контекст. */
class AgentCoreTest {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    // ---------------------------------------------------------------- Разбор намерений

    @Test fun compoundPhraseSplitsIntoTwoIntents() {
        val registry = SkillRegistry()
        val plan = IntentRecognizer.plan("найди рецепт борща и запиши его в заметки", registry)
        assertNotNull(plan)
        assertEquals(listOf(SkillRegistry.RECIPE, "note.save"), plan.intents.map { it.skill })
        assertTrue(plan.intents[1].needsEntity, "«его» ссылается на рецепт из первого шага")
    }

    @Test fun explicitConnectorSplitsPhrase() {
        val plan = IntentRecognizer.plan("как приготовить хлеб, потом добавь продукты в покупки", SkillRegistry())
        assertNotNull(plan)
        assertEquals(2, plan.intents.size)
    }

    @Test fun singleCommandIsNotSplit() {
        assertNull(IntentRecognizer.plan("купила молоко и хлеб", SkillRegistry()))
        assertNull(IntentRecognizer.plan("добавь в покупки молоко и хлеб", SkillRegistry()))
        assertNull(IntentRecognizer.plan("что такое борщ", SkillRegistry()))
    }

    @Test fun scenarioIsNotSplit() {
        assertNull(
            IntentRecognizer.plan(
                "когда я говорю спокойной ночи, добавь задачу проверить почту и напомни через 10 минут выпить воды",
                SkillRegistry(),
            ),
        )
    }

    @Test fun registryFindsSkills() {
        val registry = SkillRegistry()
        assertEquals(SkillRegistry.RECIPE, registry.best("как приготовить плов")?.id)
        assertEquals("weather", registry.best("какая погода завтра")?.id)
        assertEquals("expense.add", registry.best("потратила 500 на продукты")?.id)
        assertEquals("note.save", registry.best("запиши его в заметки")?.id)
        assertNotNull(registry.byEntity(AgentEntity.RECIPE))
    }

    // ---------------------------------------------------------------- Инструменты

    @Test fun toolTimesOutAndDoesNotHang() = runTest {
        val time = FixedTimeSource(java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"))
        val slow = object : AgentTool {
            override val id = "slow"
            override val title = "Медленный"
            override suspend fun run(input: String): ToolResult {
                kotlinx.coroutines.delay(5_000)
                return ToolResult.ok("готово")
            }
        }
        val result = ToolExecutor(time).run(slow, "x", timeoutMs = 50, attempts = 1)
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("время"), result.error.orEmpty())
    }

    @Test fun mutatingToolRunsOnceForSameInput() = runTest {
        val time = FixedTimeSource(java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"))
        var calls = 0
        val tool = object : AgentTool {
            override val id = "add"
            override val title = "Добавить"
            override val mutating = true
            override suspend fun run(input: String): ToolResult { calls++; return ToolResult.ok("добавила") }
        }
        val executor = ToolExecutor(time)
        executor.run(tool, "молоко")
        executor.run(tool, "молоко")
        assertEquals(1, calls, "повторная изменяющая команда не выполняется дважды")
        executor.run(tool, "хлеб")
        assertEquals(2, calls)
    }

    @Test fun failingToolReturnsErrorInsteadOfThrowing() = runTest {
        val time = FixedTimeSource(java.time.Instant.parse("2026-09-25T09:00:00Z"), java.time.ZoneId.of("Europe/Moscow"))
        val tool = object : AgentTool {
            override val id = "boom"
            override val title = "Падает"
            override suspend fun run(input: String): ToolResult = error("сеть недоступна")
        }
        val result = ToolExecutor(time).run(tool, "x", attempts = 1)
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("сеть"), result.error.orEmpty())
    }

    // ---------------------------------------------------------------- Контекст переживает перезапуск

    @Test fun activeTaskSurvivesRestart() = runTest {
        val env = TestEnv()
        val first = AgentCore(env.store.agent, env.time)
        val task = first.context.begin("cooking", "Борщ", totalSteps = 5)
        first.context.transition(task, TaskState.WAITING_FOR_USER, 2)
        first.context.remember(AgentEntity.recipe("Борщ", listOf("свёкла", "капуста"), listOf("сварить бульон")))

        // «Перезапуск»: новое ядро поверх той же базы.
        val second = AgentCore(env.store.agent, env.time)
        val restored = second.context.active()
        assertNotNull(restored)
        assertEquals("Борщ", restored.title)
        assertEquals(TaskState.WAITING_FOR_USER, restored.state)
        assertEquals(2, restored.stepIndex)
        assertEquals("Борщ", second.lastEntity()?.title)
    }

    @Test fun stepsAreRecorded() = runTest {
        val env = TestEnv()
        val core = AgentCore(env.store.agent, env.time)
        val task = core.context.begin("compound", "фраза", totalSteps = 2)
        core.context.recordStep(task, 0, "cooking.recipe", "найди рецепт борща", ToolResult.ok("Борщ. Вам понадобится…"), 12)
        core.context.recordStep(task, 1, "note.save", "запиши его в заметки", ToolResult.fail("нет места"), 3)
        val steps = core.context.steps(task.id)
        assertEquals(listOf(StepState.DONE, StepState.FAILED), steps.map { it.state })
        assertEquals(2, steps.size)
    }

    // ---------------------------------------------------------------- Движок: сквозные сценарии

    @Test fun recipeAndNoteInOnePhrase() = runTest {
        val env = env()
        val reply = env.engine.handle("найди рецепт борща и запиши его в заметки")
        assertTrue(reply.changedData, reply.text)
        assertTrue("Борщ" in reply.text, reply.text)
        val notes = env.store.notes.all()
        assertEquals(1, notes.size, notes.map { it.title }.toString())
        assertTrue(notes.single().title.lowercase().contains("борщ"), notes.single().title)
    }

    @Test fun recipeAndShoppingInOnePhrase() = runTest {
        val env = env()
        // Сначала рецепт — он и есть сущность для второго шага.
        env.engine.handle("как приготовить борщ")
        val reply = env.engine.handle("найди рецепт борща и добавь его в покупки")
        assertTrue(reply.changedData, reply.text)
        val items = env.store.shopping.all().map { it.text }.map { it.lowercase() }
        assertTrue(items.isNotEmpty(), "ингредиенты должны попасть в покупки")
        assertTrue(items.any { "свекл" in it || "капуст" in it || "мяс" in it }, items.toString())
    }

    @Test fun plainPhrasesStillWork() = runTest {
        val env = env()
        env.engine.handle("добавь в покупки молоко и хлеб")
        assertEquals(setOf("Молоко", "Хлеб"), env.store.shopping.all().map { it.text }.toSet())
        assertTrue(env.store.notes.all().isEmpty())
        assertEquals(ShoppingItem.DEFAULT_LIST, env.store.shopping.all().first().listName)
    }

    @Test fun missingContextFallsBackToNormalPath() = runTest {
        val env = env()
        // Сущности нет: «запиши его в заметки» не должно превращаться в пустую заметку.
        val reply = env.engine.handle("найди рецепт борща и запиши его в заметки")
        assertTrue(reply.text.isNotBlank())
    }
}
