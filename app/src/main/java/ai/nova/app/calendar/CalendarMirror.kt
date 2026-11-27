package ai.nova.app.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ai.nova.app.settings.SettingsRepository
import ai.nova.core.calendar.CalendarEventSpec
import ai.nova.core.calendar.CalendarEventSpecs
import ai.nova.core.data.CalendarLinkStore
import ai.nova.core.data.LocalStore
import ai.nova.core.model.Reminder
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Дублирование напоминаний в календарь телефона.
 *
 * Прямого доступа к Google Календарю нет: событие пишется в CalendarContract — системный календарь
 * Android. Если на телефоне есть аккаунт Google и включена синхронизация календаря, событие само
 * уезжает в Google Календарь на все устройства. Ни OAuth, ни ключей, ни оплаты не нужно.
 *
 * Связь «напоминание → событие» хранится в [CalendarLinkStore] и не синхронизируется. События
 * обновляются по отпечатку полей: срабатывание повторяющегося напоминания не переписывает серию.
 */
class CalendarMirror(
    private val context: Context,
    private val store: LocalStore,
    private val settings: SettingsRepository,
) {
    /** Календарь, доступный для записи: свой у аккаунта Google или локальный. */
    data class NovaCalendar(
        val id: Long,
        val name: String,
        val account: String,
        val google: Boolean,
        val primary: Boolean,
    ) {
        val label: String get() = if (account.isBlank() || account == name) name else "$name · $account"
    }

    data class Report(
        val added: Int = 0,
        val updated: Int = 0,
        val removed: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private val mutex = Mutex()

    /** Разрешение на чтение и запись календаря выдано. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Календари, куда можно писать. Сначала аккаунт Google, затем остальные. */
    suspend fun calendars(): List<NovaCalendar> = withContext(Dispatchers.IO) { queryCalendars() }

    /** Сколько напоминаний сейчас связано с событиями. */
    suspend fun count(): Int = store.calendarLinks.count()

    /** Пересобрать события по напоминаниям: добавить недостающие, обновить изменённые, убрать лишние. */
    suspend fun reconcile(): Report = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                reconcileLocked()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Не удалось обновить календарь", e)
                Report(error = e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Убрать события, которые добавила Нова (выключение дублирования, выход из аккаунта). */
    suspend fun removeAll(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val links = store.calendarLinks.all()
            if (links.isEmpty()) return@withContext 0
            if (!hasPermission()) return@withContext 0 // события без доступа не удалить; связи не трогаем
            var removed = 0
            for (link in links) if (deleteEvent(link.eventId)) removed++
            store.calendarLinks.deleteAll()
            removed
        }
    }

    private suspend fun reconcileLocked(): Report {
        val cfg = settings.current()
        if (!cfg.calendarMirror) return Report()
        if (!hasPermission()) return Report(error = "Нет разрешения на календарь")
        val list = queryCalendars()
        if (list.isEmpty()) return Report(error = "В телефоне нет календаря, доступного для записи")

        val savedId = cfg.calendarId.toLongOrNull()
        val calendar = list.firstOrNull { it.id == savedId }
            ?: list.firstOrNull { it.google && it.primary }
            ?: list.firstOrNull { it.google }
            ?: list.first()
        if (calendar.id != savedId) settings.setCalendarId(calendar.id.toString())

        val links = store.calendarLinks.all().associateBy { it.reminderId }
        val reminders = store.reminders.all()
        val byId = reminders.associateBy { it.id }
        var added = 0
        var updated = 0
        var removed = 0
        var failed = 0

        // Напоминание удалили (или оно пропало) — событие тоже убираем.
        for (link in links.values) {
            if (byId[link.reminderId] != null) continue
            if (deleteEvent(link.eventId)) removed++
            store.calendarLinks.delete(link.reminderId)
        }

        for (reminder in reminders) {
            val link = links[reminder.id]
            if (!reminder.active) {
                if (link == null) continue
                // Разовое уже прозвенело — событие остаётся в календаре как история.
                val firedOneTime = reminder.recurrence == null && reminder.lastFiredAt != null
                if (!firedOneTime && deleteEvent(link.eventId)) removed++
                store.calendarLinks.delete(reminder.id)
                continue
            }

            val spec = CalendarEventSpecs.forReminder(reminder)
            if (link != null && link.calendarId == calendar.id) {
                if (link.eventId.isBlank()) {
                    // Событие удалили в самом календаре: не воскрешаем, пока напоминание не изменилось.
                    if (link.fingerprint == spec.fingerprint) continue
                } else if (eventExists(link.eventId)) {
                    if (link.fingerprint == spec.fingerprint) continue
                    if (updateEvent(link.eventId, spec)) {
                        store.calendarLinks.put(
                            link.copy(fingerprint = spec.fingerprint, syncedAt = Instant.now().toEpochMilli()),
                        )
                        updated++
                        continue
                    }
                    // Не смогли обновить (провайдер занят, нет прав) — событие не трогаем и не дублируем.
                    failed++
                    continue
                } else {
                    // Событие исчезло (удалили или перенесли вручную) — запоминаем это.
                    if (link.fingerprint == spec.fingerprint) {
                        store.calendarLinks.put(link.copy(eventId = ""))
                        continue
                    }
                }
                if (link.eventId.isNotBlank()) deleteEvent(link.eventId)
            } else if (link != null) {
                // Человек выбрал другой календарь: старое событие убираем, новое создаём ниже.
                deleteEvent(link.eventId)
            }

            // После переустановки или восстановления из копии связей ещё нет, а события в
            // календаре остались. Находим их по метке источника и обновляем, а не плодим дубли.
            val adopted = if (link == null || link.eventId.isBlank()) findManagedEvent(reminder.id, calendar.id) else null
            if (adopted != null) {
                if (updateEvent(adopted, spec)) {
                    store.calendarLinks.put(
                        CalendarLinkStore.Link(
                            reminderId = reminder.id,
                            eventId = adopted,
                            calendarId = calendar.id,
                            fingerprint = spec.fingerprint,
                            syncedAt = Instant.now().toEpochMilli(),
                        ),
                    )
                    updated++
                } else {
                    failed++
                }
                continue
            }

            val eventId = createEvent(calendar.id, reminder, spec)
            if (eventId == null) {
                failed++
            } else {
                store.calendarLinks.put(
                    CalendarLinkStore.Link(
                        reminderId = reminder.id,
                        eventId = eventId,
                        calendarId = calendar.id,
                        fingerprint = spec.fingerprint,
                        syncedAt = Instant.now().toEpochMilli(),
                    ),
                )
                added++
            }
        }
        return Report(added = added, updated = updated, removed = removed, failed = failed)
    }

    private fun queryCalendars(): List<NovaCalendar> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? " +
            "AND ${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.DELETED} = 0"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val out = ArrayList<NovaCalendar>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += NovaCalendar(
                        id = c.getLong(0),
                        name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Календарь",
                        account = c.getString(2).orEmpty(),
                        google = c.getString(3) == GOOGLE_ACCOUNT,
                        primary = c.getInt(4) == 1,
                    )
                }
            }
        }.onFailure { Logger.w(TAG, "Не удалось прочитать список календарей", it) }
        return out.sortedWith(
            compareByDescending<NovaCalendar> { it.google }
                .thenByDescending { it.primary }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun createEvent(calendarId: Long, reminder: Reminder, spec: CalendarEventSpec): String? = runCatching {
        val values = eventValues(spec).apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            // Метка источника: такие события не показываются повторно в плане дня Нова.
            put(CalendarContract.Events.CUSTOM_APP_URI, CUSTOM_URI_PREFIX + reminder.id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        uri?.lastPathSegment?.toLongOrNull()?.toString()
    }.onFailure { Logger.w(TAG, "Не удалось добавить событие напоминания", it) }.getOrNull()

    private fun updateEvent(eventId: String, spec: CalendarEventSpec): Boolean = runCatching {
        val id = eventId.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        context.contentResolver.update(uri, eventValues(spec), null, null) > 0
    }.onFailure { Logger.w(TAG, "Не удалось обновить событие напоминания", it) }.getOrDefault(false)

    private fun eventValues(spec: CalendarEventSpec): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.TITLE, spec.title)
        put(CalendarContract.Events.DESCRIPTION, "Напоминание Нова")
        put(CalendarContract.Events.DTSTART, spec.start.toEpochMilli())
        put(CalendarContract.Events.DTEND, spec.end.toEpochMilli())
        put(CalendarContract.Events.ALL_DAY, 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, spec.timeZone)
        // Напоминает сама Нова; второе уведомление из календаря только мешало бы.
        put(CalendarContract.Events.HAS_ALARM, 0)
        if (spec.rrule == null) putNull(CalendarContract.Events.RRULE) else put(CalendarContract.Events.RRULE, spec.rrule)
    }

    /** Событие, которое Нова создала раньше, но связь с ним потерялась (переустановка, копия). */
    private fun findManagedEvent(reminderId: String, calendarId: Long): String? = runCatching {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_URI} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? " +
                "AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(CUSTOM_URI_PREFIX + reminderId, calendarId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0).toString() else null }
    }.onFailure { Logger.w(TAG, "Не удалось найти прежнее событие напоминания", it) }.getOrNull()

    private fun eventExists(eventId: String): Boolean = runCatching {
        val id = eventId.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.DELETED} = 0",
            null,
            null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun deleteEvent(eventId: String): Boolean {
        val id = eventId.toLongOrNull() ?: return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            context.contentResolver.delete(uri, null, null) > 0
        }.onFailure { Logger.w(TAG, "Не удалось удалить событие $eventId", it) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "Calendar"
        private const val GOOGLE_ACCOUNT = "com.google"
        /** По этому префиксу видно, что событие создала Нова: в плане дня его не показываем второй раз. */
        const val CUSTOM_URI_PREFIX = "nova://reminder/"
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
