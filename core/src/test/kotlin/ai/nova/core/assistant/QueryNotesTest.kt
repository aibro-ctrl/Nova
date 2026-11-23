package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Покажи мои последние заметки», «какие у меня идеи» — голосом, без открытия экрана. */
class QueryNotesTest {

    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun latestNotesAreListedNewestFirst() = runTest {
        val e = env()
        e.store.notes.create(NoteKind.NOTE, "Старая", "")
        e.time.advanceMillis(1_000)
        e.store.notes.create(NoteKind.NOTE, "Новая", "")
        val text = e.engine.handle("покажи мои последние заметки").text
        assertTrue(text.contains("Последние заметки (2):"), text)
        assertTrue(text.indexOf("Новая") < text.indexOf("Старая"), text)
    }

    @Test fun onlyIdeasAreListed() = runTest {
        val e = env()
        e.store.notes.create(NoteKind.NOTE, "Заметка", "")
        e.store.notes.create(NoteKind.IDEA, "Идея про склад", "")
        val text = e.engine.handle("какие у меня идеи").text
        assertTrue(text.contains("Идея про склад"), text)
        assertFalse(text.contains("Заметка"), text)
    }

    @Test fun askedPhrasesListRecordsInsteadOfSearching() = runTest {
        // Фразы из правок: раньше они уходили в поиск по словам («и прочитай мои идеи»).
        val e = env()
        e.store.notes.create(NoteKind.IDEA, "Идея про склад", "")
        for (phrase in listOf("найди и прочитай мои идеи", "что я сегодня придумал", "найди мои идеи", "какие идеи я записал сегодня")) {
            val text = e.engine.handle(phrase).text
            assertTrue(text.contains("Идея про склад"), "$phrase → $text")
        }
    }

    @Test fun emptyListIsExplained() = runTest {
        val e = env()
        val text = e.engine.handle("покажи мои заметки").text
        assertTrue(text.contains("Заметок пока нет"), text)
    }

    @Test fun hiddenOnLockScreen() = runTest {
        val e = TestEnv(lockPolicy = { LockPolicy.SAFE }).apply { settings = AssistantSettings(useAI = false) }
        e.store.notes.create(NoteKind.NOTE, "Секрет", "")
        val text = e.engine.handle("покажи мои заметки").text
        assertTrue(text.contains("Разблокируйте"), text)
        assertFalse(text.contains("Секрет"), text)
    }
}
