package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Нова, у меня идея» без текста: спрашиваем, что за идея, и записываем следующую фразу. */
class IdeaFix2182Test {
    private suspend fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun asksWhatTheIdeaIs() = runTest {
        val e = env()
        val reply = e.engine.handle("Нова, у меня идея")
        assertTrue(reply.awaitingAnswer, reply.text)
        assertTrue(reply.text.contains("Расскажите идею"), reply.text)
        assertTrue(e.store.notes.all().isEmpty())
    }

    @Test fun recordsNextPhraseAsIdea() = runTest {
        val e = env()
        e.engine.handle("у меня идея")
        val reply = e.engine.handle("приложение, которое напоминает поливать цветы")
        val idea = e.store.notes.all().single()
        assertEquals(NoteKind.IDEA, idea.kind)
        assertTrue(idea.title.contains("поливать цветы"), idea.title)
        assertTrue(reply.text.isNotBlank(), reply.text)
    }

    @Test fun ideaWithTextWorksAsBefore() = runTest {
        val e = env()
        e.engine.handle("у меня идея купить дом")
        val idea = e.store.notes.all().single()
        assertEquals(NoteKind.IDEA, idea.kind)
        assertTrue(idea.title.contains("дом", ignoreCase = true), idea.title)
    }

    @Test fun commandAfterQuestionIsNotSwallowed() = runTest {
        val e = env()
        e.engine.handle("у меня идея")
        e.engine.handle("какая погода")
        assertTrue(e.store.notes.all().isEmpty())
    }
}
