package ai.nova.core.agent

import ai.nova.core.db.NovaDatabase
import ai.nova.core.util.FixedTimeSource
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Миграция 3.sqm: у базы с установленного приложения появляются таблицы агента, старые данные целы. */
class AgentMigrationTest {
    private val time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))

    private fun names(driver: JdbcSqliteDriver, like: String): List<String> =
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '$like'", { cursor ->
            val out = ArrayList<String>()
            while (cursor.next().value == true) cursor.getString(0)?.let { out += it }
            QueryResult.Value(out.toList())
        }, 0).value.orEmpty()

    @Test fun migrationAddsAgentTablesToExistingDatabase() = runTest {
        val file = File.createTempFile("nova-migration", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                // База версии 3 — как у приложения, обновившегося с 2.16.
                driver.execute(null, "CREATE TABLE note (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)", 0)
                driver.execute(null, "INSERT INTO note(id, title) VALUES ('n1', 'Борщ')", 0)
                driver.execute(null, "CREATE TABLE shopping_item (id TEXT NOT NULL PRIMARY KEY, list_name TEXT NOT NULL, text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1, synced_updated_at INTEGER)", 0)
                driver.execute(null, "INSERT INTO shopping_item(id, list_name, text, done, created_at, updated_at) VALUES ('s1', 'Покупки', 'Молоко', 0, 1, 1)", 0)
                driver.execute(null, "PRAGMA user_version = 3", 0)

                assertEquals(6, NovaDatabase.Schema.version, "версия схемы выросла на миграциях 3.sqm–5.sqm")
                NovaDatabase.Schema.migrate(driver, 3, NovaDatabase.Schema.version)
                assertTrue(names(driver, "agent%").containsAll(listOf("agent_task", "agent_step", "agent_context")))
                // 4.sqm: количество и источник у пунктов списка покупок, рецепты и сессии готовки.
                assertTrue(names(driver, "recipe").contains("recipe"))
                assertTrue(names(driver, "cooking_session").contains("cooking_session"))
                val columns = driver.executeQuery(null, "PRAGMA table_info(shopping_item)", { cursor ->
                    val out = ArrayList<String>()
                    while (cursor.next().value == true) cursor.getString(1)?.let { out += it }
                    QueryResult.Value(out.toList())
                }, 0).value.orEmpty()
                assertTrue(columns.containsAll(listOf("quantity", "unit", "source")), columns.toString())
                // 5.sqm: карточка товара с площадки — магазин, ссылка, фото, цена, рейтинг.
                assertTrue(columns.containsAll(listOf("marketplace", "url", "image_url", "price", "rating", "reviews")), columns.toString())
                val kept = driver.executeQuery(null, "SELECT text FROM shopping_item WHERE id = 's1'", { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getString(0))
                }, 0).value
                assertEquals("Молоко", kept, "старые пункты списка покупок целы")

                val store = AgentStore(NovaDatabase(driver), Dispatchers.Unconfined)
                assertNull(store.active(), "новых задач после миграции нет")
                val task = AgentCore(store, time).context.begin("cooking", "Борщ", totalSteps = 3)
                assertEquals(task.id, store.active()?.id)

                // Старые записи не пострадали.
                val title = driver.executeQuery(null, "SELECT title FROM note", { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getString(0))
                }, 0).value
                assertEquals("Борщ", title)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun schemaCreatesAgentTables() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NovaDatabase.Schema.create(it) }
        try {
            assertTrue(names(driver, "agent%").containsAll(listOf("agent_task", "agent_step", "agent_context")))
        } finally {
            driver.close()
        }
    }
}
