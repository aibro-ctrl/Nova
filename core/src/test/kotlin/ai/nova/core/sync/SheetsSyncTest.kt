package ai.nova.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор ссылки на таблицу и слияние версий — без сети и без Android. */
class SheetsSyncTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"

    @Test
    fun idIsTakenFromLink() {
        assertEquals(id, SheetsSync.spreadsheetId("https://docs.google.com/spreadsheets/d/$id/edit#gid=0"))
        assertEquals(id, SheetsSync.spreadsheetId("https://docs.google.com/spreadsheets/d/$id"))
    }

    @Test
    fun bareIdIsAccepted() {
        assertEquals(id, SheetsSync.spreadsheetId(id))
        assertEquals(id, SheetsSync.spreadsheetId("  \"$id\"  "))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(SheetsSync.spreadsheetId(""))
        assertNull(SheetsSync.spreadsheetId("https://example.com/table"))
        assertNull(SheetsSync.spreadsheetId("короткий"))
    }

    private fun row(table: String, id: String, at: Long, payload: String = "{}") = SheetsRow(table, id, at, deleted = false, payload = payload)

    @Test
    fun newerVersionWins() {
        val local = listOf(row("notes", "n1", 100), row("notes", "n2", 300))
        val remote = listOf(row("notes", "n1", 200), row("notes", "n2", 100))
        val merged = SheetsSync.merge(local, remote).rows.associateBy { it.id }
        assertEquals(200, merged["n1"]?.updatedAt)
        assertEquals(300, merged["n2"]?.updatedAt)
    }

    @Test
    fun equalTimestampKeepsLocal() {
        val local = row("notes", "n1", 100, payload = """{"title":"локальная"}""")
        val remote = row("notes", "n1", 100, payload = """{"title":"облачная"}""")
        assertEquals(local.payload, SheetsSync.merge(listOf(local), listOf(remote)).rows.single().payload)
    }

    @Test
    fun remoteOnlyRowIsAdded() {
        val merged = SheetsSync.merge(emptyList(), listOf(row("tasks", "t1", 5))).rows
        assertEquals(1, merged.size)
        assertEquals("t1", merged.single().id)
    }

    @Test
    fun tombstoneTravelsBetweenDevices() {
        val deleted = SheetsRow("notes", "n1", 500, deleted = true, payload = "{}")
        val merged = SheetsSync.merge(listOf(row("notes", "n1", 100)), listOf(deleted)).rows.single()
        assertTrue(merged.deleted)
        assertEquals(500, merged.updatedAt)
    }

    @Test
    fun newerLocalTombstoneBeatsOlderRemoteRow() {
        val local = SheetsRow("notes", "n1", 500, deleted = true, payload = "{}")
        val remote = row("notes", "n1", 100, payload = """{"title":"старая"}""")
        val merged = SheetsSync.merge(listOf(local), listOf(remote)).rows.single()
        assertTrue(merged.deleted)
        assertEquals(500, merged.updatedAt)
    }

    @Test
    fun tooLongPayloadIsSkippedAndCounted() {
        val long = "x".repeat(SheetsSync.MAX_CELL + 1)
        val result = SheetsSync.merge(listOf(row("notes", "big", 1, long), row("notes", "ok", 1)), emptyList())
        assertEquals(1, result.rows.size)
        assertEquals(1, result.tooLong)
    }

    @Test
    fun orderIsStable() {
        val rows = SheetsSync.merge(listOf(row("tasks", "b", 1), row("notes", "z", 1), row("notes", "a", 1)), emptyList()).rows
        assertEquals(listOf("notes" to "a", "notes" to "z", "tasks" to "b"), rows.map { it.table to it.id })
    }
}
