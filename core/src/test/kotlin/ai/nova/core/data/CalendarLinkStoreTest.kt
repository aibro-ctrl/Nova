package ai.nova.core.data

import ai.nova.core.db.NovaDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Связь «напоминание → событие»: хранится локально, обновляется и удаляется. */
class CalendarLinkStoreTest {

    private fun store(): CalendarLinkStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NovaDatabase.Schema.create(it) }
        return CalendarLinkStore(NovaDatabase(driver), Dispatchers.Unconfined)
    }

    @Test
    fun linkIsSavedAndReplaced() = runTest {
        val store = store()
        store.put(CalendarLinkStore.Link("r1", "10", 1, "f1", 100))
        assertEquals("10", store.byReminder("r1")?.eventId)
        assertEquals(1, store.count())

        store.put(CalendarLinkStore.Link("r1", "11", 2, "f2", 200))
        assertEquals("11", store.byReminder("r1")?.eventId)
        assertEquals(1, store.count(), "повторная запись заменяет связь, а не добавляет вторую")
    }

    @Test
    fun detachedEventIsNotCounted() = runTest {
        val store = store()
        store.put(CalendarLinkStore.Link("r1", "", 1, "f1", 100))
        assertEquals(0, store.count(), "событие удалили снаружи — связи нет")
        store.delete("r1")
        assertNull(store.byReminder("r1"))
        assertEquals(0, store.all().size)
    }
}
