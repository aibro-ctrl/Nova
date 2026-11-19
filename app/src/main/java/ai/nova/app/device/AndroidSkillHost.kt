package ai.nova.app.device

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import ai.nova.app.media.RadioService
import ai.nova.app.media.RingService
import ai.nova.app.notify.NovaNotificationListener
import ai.nova.app.reminders.GeoReminders
import ai.nova.app.reminders.NovaTimers
import ai.nova.core.skills.ActiveTimer
import ai.nova.core.skills.CalendarItem
import ai.nova.core.skills.ContactInfo
import ai.nova.core.skills.GeoPoint
import ai.nova.core.skills.IncomingMessage
import ai.nova.core.skills.MusicApp
import ai.nova.core.skills.MusicTrack
import ai.nova.core.skills.NeedsPermission
import ai.nova.core.skills.Permission
import ai.nova.core.skills.PlaceReminder
import ai.nova.core.skills.RadioStation
import ai.nova.core.skills.SavedPlace
import ai.nova.core.skills.SkillHost
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Возможности телефона для навыков Нова. Обычные разрешения (контакты, календарь, геолокация)
 * спрашиваются прямо по ходу команды; особые доступы (уведомления, спецвозможности) — открываем нужный экран настроек.
 */
class AndroidSkillHost(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val launcher: BackgroundLauncher,
    val timers: NovaTimers,
    val geo: GeoReminders,
    private val onUserName: (String?) -> Unit,
    private val onCity: (String?) -> Unit,
    private val finance: FinanceBridge? = null,
    private val financePendingSince: suspend (Instant) -> List<ai.nova.core.model.Expense> = { emptyList() },
    /** Положить найденный текст в заметки Нова. */
    private val saveNoteTo: suspend (String, String) -> Boolean = { _, _ -> false },
) : SkillHost {
    private val locator = Locator(context)

    override suspend fun location(): GeoPoint? {
        if (!locator.hasCoarse() && !permissions.ensure(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return locator.current()?.let { GeoPoint(it.latitude, it.longitude) }
    }

    override suspend fun messages(): List<IncomingMessage> {
        if (!NovaNotificationListener.enabled(context)) {
            launcher.launch(NovaNotificationListener.settingsIntent(context))
            throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет доступа к уведомлениям")
        }
        return NovaNotificationListener.messages()
    }

    override suspend fun reply(message: IncomingMessage, text: String): Boolean =
        withContext(Dispatchers.Main) { NovaNotificationListener.reply(context, message.key, text) }

    override suspend fun screenText(): String? {
        val service = NovaAccessibilityService.current
        if (service == null) {
            launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw NeedsPermission(Permission.ACCESSIBILITY, "спецвозможности выключены")
        }
        return withContext(Dispatchers.Main) { service.screenText() }
    }

    override suspend fun contact(name: String): ContactInfo? {
        if (!permissions.ensure(Manifest.permission.READ_CONTACTS)) throw NeedsPermission(Permission.CONTACTS, "нет доступа к контактам")
        return withContext(Dispatchers.IO) { ContactLookup.find(context, name)?.let { ContactInfo(it.name, it.phones) } }
    }

    /** Дни рождения из контактов — только если доступ уже выдан: вопрос «у кого скоро ДР» не должен дёргать разрешения. */
    override suspend fun contactBirthdays(): List<Pair<String, java.time.MonthDay>> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, java.time.MonthDay>>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        // «1990-05-12», «--05-12» (без года), иногда «12.05.1990».
                        val raw = c.getString(1) ?: continue
                        val md = Regex("""(?:\d{4}|-)-(\d{2})-(\d{2})""").find(raw)?.let { m -> runCatching { java.time.MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() }
                            ?: Regex("""^(\d{1,2})\.(\d{1,2})""").find(raw)?.let { m -> runCatching { java.time.MonthDay.of(m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }
                        if (md != null) out += name to md
                    }
                }
            }
            out.distinctBy { it.first }
        }
    }

    override suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> {
        if (!permissions.ensure(Manifest.permission.READ_CALENDAR)) throw NeedsPermission(Permission.CALENDAR, "нет доступа к календарю")
        return withContext(Dispatchers.IO) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, from.toEpochMilli())
                android.content.ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val out = ArrayList<CalendarItem>()
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 50) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Без названия"
                    val allDay = c.getInt(3) == 1
                    // Событие «на весь день» хранится в UTC-полночь — переносим на местную дату.
                    fun localDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                    val begin = if (allDay) localDay(c.getLong(1)) else Instant.ofEpochMilli(c.getLong(1))
                    val end = c.getLong(2).takeIf { it > 0 }?.let { if (allDay) localDay(it) else Instant.ofEpochMilli(it) }
                    // Вчерашнее событие «на весь день» попадает в окно запроса из-за часового пояса — отбрасываем.
                    if (allDay && (!begin.isBefore(to) || (end != null && !end.isAfter(from)))) continue
                    out += CalendarItem(title, begin, end, allDay, c.getString(4))
                }
            }
            out
        }
    }

    override suspend fun playRadio(station: RadioStation): Boolean = RadioService.play(context, station)
    override fun stopRadio(): Boolean = RadioService.stop(context)
    override fun radioPlaying(): Boolean = RadioService.playing

    /** Найденную песню играет тот же плеер, что и радио: медиасессия, фокус звука и уведомление уже готовы. */
    override suspend fun playTrack(track: MusicTrack): Boolean = RadioService.play(context, RadioStation(track.label, track.previewUrl), music = true)

    override fun hasMusicApp(app: MusicApp): Boolean = app.packages.any { installed(it) }

    override suspend fun playInMusicApp(query: String, app: MusicApp): Boolean {
        val pkg = app.packages.firstOrNull { installed(it) }
        return try {
            if (query.isBlank()) {
                val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return false
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
            // Стандартный способ «включи по поиску»: Яндекс Музыка и Spotify по нему реально начинают играть.
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            if (pkg != null) play.setPackage(pkg)
            if (play.resolveActivity(context.packageManager) != null) {
                context.startActivity(play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
            // Не вышло — открываем поиск в самом приложении, а без него в браузере.
            val web = app.webUrl(Uri.encode(query))
            val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(web))
            if (pkg != null) inApp.setPackage(pkg)
            val target = if (inApp.resolveActivity(context.packageManager) != null) inApp else Intent(Intent.ACTION_VIEW, Uri.parse(web))
            if (target.resolveActivity(context.packageManager) == null) return false
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Logger.w("Music", "Не удалось включить ${app.title}", e)
            false
        }
    }

    private fun installed(pkg: String): Boolean = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    override suspend fun ringPhone(): Boolean = RingService.start(context, "Я здесь!", "Нова ищет телефон. Коснитесь, чтобы остановить.", loud = true)

    override fun timers(): List<ActiveTimer> = timers.all()
    override fun cancelTimers(label: String?): Int = timers.cancel(label)
    override fun cancelTimer(id: String): Boolean = timers.cancelOne(id)

    override suspend fun savePlace(name: String): SavedPlace? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val loc = locator.current(maxAgeMs = 2 * 60_000L, precise = true) ?: return null
        return SavedPlace(name, loc.latitude, loc.longitude).also { geo.savePlace(it) }
    }

    override fun places(): List<SavedPlace> = geo.places()

    override suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val r = geo.add(text, place, onLeave)
        // Чтобы напоминание сработало, когда Нова закрыта, нужна геолокация «Всегда» (Android 10+).
        if (!locator.hasBackground()) permissions.ensure(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return r
    }

    override fun placeReminders(): List<PlaceReminder> = geo.reminders()
    override fun cancelPlaceReminders(place: String?): Int = geo.cancel(place)

    override suspend fun financeSummary(): String? = finance?.summary()
    override suspend fun financePending(since: Instant): List<ai.nova.core.model.Expense> = financePendingSince(since)

    override fun setUserName(name: String?) = onUserName(name)
    override fun setCity(city: String?) = onCity(city)

    override suspend fun saveNote(title: String, content: String): Boolean = saveNoteTo(title, content)
}
