package ai.nova.core.data

import ai.nova.core.db.Calendar_event_link
import ai.nova.core.db.NovaDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Связь напоминания с событием календаря телефона.
 *
 * Хранится только на устройстве и не участвует в синхронизации: у каждого телефона свои
 * идентификаторы событий. [fingerprint] — отпечаток полей события, [syncedAt] — когда
 * событие последний раз приводили в соответствие с напоминанием.
 */
class CalendarLinkStore(
    private val db: NovaDatabase,
    private val dispatcher: CoroutineDispatcher,
) {
    data class Link(
        val reminderId: String,
        val eventId: String,
        val calendarId: Long,
        val fingerprint: String,
        val syncedAt: Long,
    )

    private val q get() = db.calendarEventLinkQueries

    suspend fun all(): List<Link> = withContext(dispatcher) {
        q.selectAll().executeAsList().map { it.toLink() }
    }

    suspend fun byReminder(reminderId: String): Link? = withContext(dispatcher) {
        q.selectByReminder(reminderId).executeAsOneOrNull()?.toLink()
    }

    suspend fun put(link: Link) = withContext(dispatcher) {
        q.upsert(Calendar_event_link(link.reminderId, link.eventId, link.calendarId, link.fingerprint, link.syncedAt))
    }

    suspend fun delete(reminderId: String) = withContext(dispatcher) {
        q.deleteByReminder(reminderId)
    }

    suspend fun deleteAll() = withContext(dispatcher) { q.deleteAll() }

    /** Сколько событий сейчас реально связано с напоминаниями. */
    suspend fun count(): Int = withContext(dispatcher) {
        q.count().executeAsOne().toInt()
    }

    private fun Calendar_event_link.toLink() = Link(
        reminderId = reminder_id,
        eventId = event_id,
        calendarId = calendar_id,
        fingerprint = fingerprint,
        syncedAt = synced_at,
    )
}
