package ai.nova.core.data

import ai.nova.core.db.NovaDatabase
import ai.nova.core.sync.SyncableTable
import ai.nova.core.util.TimeSource
import ai.nova.core.util.UpdateStamp
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Точка сборки локального хранилища. Драйвер SQLite передаётся снаружи:
 * на Android — зашифрованный SQLCipher, в тестах и на десктопе — JDBC.
 */
class LocalStore(
    driver: SqlDriver,
    time: TimeSource,
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val db: NovaDatabase = NovaDatabase(driver)
    val changes = LocalChangeBus()
    private val stamp = UpdateStamp(time)

    val notes = SqlNoteRepository(db, stamp, changes, dispatcher)
    val expenses = SqlExpenseRepository(db, stamp, changes, dispatcher)
    val tasks = SqlTaskRepository(db, stamp, changes, dispatcher)
    val reminders = SqlReminderRepository(db, stamp, changes, dispatcher)
    /** Связь напоминаний с событиями календаря телефона: только на этом устройстве. */
    val calendarLinks = CalendarLinkStore(db, dispatcher)
    val memories = SqlMemoryRepository(db, stamp, changes, dispatcher)
    val conversations = SqlConversationRepository(db, stamp, changes, dispatcher)
    val embeddings = EmbeddingStore(db, dispatcher)
    val shopping = SqlShoppingRepository(db, stamp, changes, dispatcher)
    val routines = SqlRoutineRepository(db, stamp, changes, dispatcher)
    val secrets = SecretNoteStore(db, dispatcher) { time.now() }
    /** Вода, лекарства, привычки — только на телефоне. */
    val habits = ai.nova.core.health.HabitStore(db, dispatcher)
    /** Состояние агента: многошаговые задачи, шаги и контекст. */
    val agent = ai.nova.core.agent.AgentStore(db, dispatcher)
    /** Сохранённые рецепты и идущая готовка. */
    val cooking = CookingStore(db, dispatcher)

    /** Порядок важен только для удобства отладки; таблицы независимы. */
    val syncTables: List<SyncableTable> = listOf(notes, expenses, tasks, reminders, memories, conversations, shopping, routines)

    suspend fun cursor(table: String): String? = withContext(dispatcher) { db.syncStateQueries.selectCursor(table).executeAsOneOrNull() }
    suspend fun setCursor(table: String, value: String) = withContext(dispatcher) { db.syncStateQueries.upsertCursor(table, value); Unit }

    /** Полная очистка локальных данных (выход из аккаунта). */
    suspend fun wipe() = withContext(dispatcher) {
        db.transaction {
            db.noteQueries.deleteAll(); db.expenseQueries.deleteAll(); db.taskQueries.deleteAll()
            db.reminderQueries.deleteAll(); db.calendarEventLinkQueries.deleteAll()
            db.memoryQueries.deleteAll(); db.conversationQueries.deleteAll()
            db.embeddingQueries.deleteAll(); db.syncStateQueries.deleteAll()
            db.shoppingItemQueries.deleteAll(); db.routineQueries.deleteAll(); db.secretNoteQueries.deleteAll()
            db.habitLogQueries.deleteAll()
            db.agentQueries.deleteAllTasks(); db.agentQueries.deleteAllSteps(); db.agentQueries.deleteAllContext()
            db.recipeQueries.deleteAllRecipes(); db.recipeQueries.deleteAllSessions()
        }
    }

    /** Есть ли неотправленные изменения. */
    suspend fun pendingChanges(): Int = syncTables.sumOf { it.dirtyRows().size }
}
