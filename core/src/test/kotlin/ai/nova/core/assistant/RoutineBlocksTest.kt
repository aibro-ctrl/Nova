package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.model.Routine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Конструктор сценариев: каждая галочка — обычная фраза, которую Нова и так понимает.
 * Тест держит это обещание: если блок добавят, а фразу Нова перестанет разбирать, тест упадёт.
 */
class RoutineBlocksTest {

    @Test
    fun `каждая галочка понимается новым движком`() = runTest {
        for (block in RoutineBlocks.ALL) {
            val env = TestEnv()
            env.settings = AssistantSettings(useAI = false, assistantName = "Нова")
            val reply = env.engine.handle(block.command)
            assertFalse(
                reply.text.startsWith("Не поняла"),
                "блок «${block.title}» идёт командой «${block.command}», но Нова её не поняла: ${reply.text}",
            )
        }
    }

    @Test
    fun `блоки в сценарии распознаются по командам`() {
        val commands = listOf("какая погода", "мои напоминания", "проверь почту")
        assertEquals(setOf("weather", "reminders"), RoutineBlocks.selectedIds(commands))
        assertEquals(listOf("проверь почту"), RoutineBlocks.extras(commands))
    }

    @Test
    fun `сборка сохраняет старый порядок и добавляет новые галочки`() {
        val original = listOf("какая погода", "мои напоминания", "проверь почту")
        val composed = RoutineBlocks.compose(original, setOf("weather", "reminders", "news"), listOf("проверь почту"))
        // Старые команды на месте, новые — после них, свои — в конце.
        assertEquals(listOf("какая погода", "мои напоминания", "проверь почту", "новости"), composed)
    }

    @Test
    fun `снятая галочка убирает свою команду`() {
        val composed = RoutineBlocks.compose(listOf("какая погода", "новости"), setOf("weather"), emptyList())
        assertEquals(listOf("какая погода"), composed)
    }

    @Test
    fun `повторы не попадают в сценарий`() {
        val composed = RoutineBlocks.compose(listOf("Какая погода"), setOf("weather"), listOf("какая погода!"))
        assertEquals(1, composed.size)
        assertEquals("какая погода", Routine.normalize(composed.single()))
    }

    @Test
    fun `у каждой галочки есть название и подсказка`() {
        assertTrue(RoutineBlocks.ALL.map { it.id }.toSet().size == RoutineBlocks.ALL.size, "id галочек должны быть уникальны")
        for (b in RoutineBlocks.ALL) {
            assertTrue(b.title.isNotBlank(), "у блока ${b.id} нет названия")
            assertTrue(b.hint.isNotBlank(), "у блока ${b.id} нет подсказки")
            assertTrue(b.command.isNotBlank(), "у блока ${b.id} нет команды")
        }
    }
}
