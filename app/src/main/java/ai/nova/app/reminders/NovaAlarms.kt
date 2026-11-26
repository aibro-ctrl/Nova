package ai.nova.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import ai.nova.app.media.RingService
import ai.nova.core.assistant.ActiveAlarm
import ai.nova.core.assistant.RuFormat
import ai.nova.core.util.Ids
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** Будильник, который хранит сама Нова: время, дни повтора и ближайшее срабатывание. */
data class OwnAlarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val label: String = "",
    val enabled: Boolean = true,
    /** Ближайшее срабатывание; null — выключен. */
    val at: Instant? = null,
    /** Отложенный будильник: сработает один раз и исчезнет. */
    val oneShot: Boolean = false,
) {
    val time: LocalTime get() = LocalTime.of(hour, minute)
    fun toActive() = ActiveAlarm(time, days, label, at?.takeIf { enabled }, enabled)
}

/**
 * Свои будильники Нова: приложение «Часы» отдаёт только установку будильника, а список своих
 * будильников системе не показывает. Поэтому Нова хранит их сама — тогда она может честно ответить
 * «какие у меня будильники», выключить нужный голосом и отложить сработавший на «ещё 5 минут».
 *
 * Повторяющиеся будильники ставятся системным будильником-часами (setAlarmClock), поэтому
 * звонят в беззвучном режиме и не теряются в экономии батареи.
 */
class NovaAlarms(private val context: Context) {
    private val prefs = context.getSharedPreferences("nova_alarms", Context.MODE_PRIVATE)

    private fun stored(): List<OwnAlarm> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val days = runCatching {
                val d = o.optJSONArray("days") ?: JSONArray()
                (0 until d.length()).map { d.optInt(it) }.mapNotNull { v -> DayOfWeek.entries.firstOrNull { it.value == v } }.toSet()
            }.getOrDefault(emptySet())
            OwnAlarm(
                id = o.optString("id").ifBlank { Ids.newId() },
                hour = o.optInt("hour", 7),
                minute = o.optInt("minute", 0),
                days = days,
                label = o.optString("label"),
                enabled = o.optBoolean("enabled", true),
                at = o.optLong("at", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                oneShot = o.optBoolean("oneShot", false),
            )
        }
    }

    private fun save(list: List<OwnAlarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("hour", it.hour).put("minute", it.minute)
                    .put("days", JSONArray(it.days.map { d -> d.value }))
                    .put("label", it.label).put("enabled", it.enabled)
                    .put("at", it.at?.toEpochMilli() ?: 0L).put("oneShot", it.oneShot),
            )
        }
        // commit: приёмник сигнала может сразу завершить процесс.
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    fun all(): List<OwnAlarm> = synchronized(LOCK) { stored().sortedBy { it.at ?: Instant.MAX } }

    /** Включённые будильники: их Нова и называет, когда спрашивают «какие у меня будильники». */
    fun active(): List<ActiveAlarm> = synchronized(LOCK) { stored().map { it.toActive() } }

    fun set(time: LocalTime, days: Set<DayOfWeek>, label: String = ""): OwnAlarm {
        val at = nextAt(time, days, ZonedDateTime.now())
        val alarm = OwnAlarm(Ids.newId(), time.hour, time.minute, days, label.trim(), enabled = true, at = at, oneShot = days.isEmpty())
        synchronized(LOCK) {
            // Тот же будильник уже есть — не дублируем, а переносим на новое время.
            val rest = stored().filterNot { it.hour == time.hour && it.minute == time.minute && it.days == days }
            save(rest + alarm)
        }
        schedule(alarm)
        return alarm
    }

    /** «Выключи будильник», «выключи будильник на 7», «убери все будильники». Возвращает выключенные. */
    fun off(time: LocalTime?, all: Boolean, delete: Boolean = false): List<OwnAlarm> = synchronized(LOCK) {
        val list = stored()
        val enabled = list.filter { it.enabled }
        val gone = when {
            all -> enabled
            time != null -> enabled.filter { it.hour == time.hour && it.minute == time.minute }
            else -> enabled.minByOrNull { it.at ?: Instant.MAX }?.let { listOf(it) } ?: emptyList()
        }
        gone.forEach { unschedule(it) }
        val ids = gone.map { it.id }.toSet()
        save(
            if (delete) list.filterNot { it.id in ids }
            else list.map { a -> if (a.id in ids) a.copy(enabled = false, at = null) else a },
        )
        gone
    }

    /** Будильник, который звенит сейчас (или замолчал меньше трёх минут назад). */
    fun ringing(): OwnAlarm? {
        val raw = prefs.getString(RINGING, "").orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split("|")
        if (parts.size != 4) return null
        val since = parts[3].toLongOrNull() ?: return null
        if (Instant.ofEpochMilli(since).isBefore(Instant.now().minusSeconds(RINGING_WINDOW_MINUTES * 60L))) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return OwnAlarm(Ids.newId(), hour, minute, emptySet(), parts[2], enabled = true, at = Instant.ofEpochMilli(since), oneShot = true)
    }

    fun clearRinging() { prefs.edit().remove(RINGING).commit() }

    /**
     * «Ещё 5 минут», «повтори через 30 минут» — перенос сработавшего будильника.
     * null — будильник сейчас не звонил.
     */
    fun snooze(minutes: Int): Instant? = synchronized(LOCK) {
        val firedAt = prefs.getLong(LAST_FIRED, 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: return null
        // «Ещё 5 минут» — про только что замолчавший будильник. Через десять минут это уже
        // скорее про идущий таймер, поэтому старое срабатывание не считается.
        if (firedAt.isBefore(Instant.now().minusSeconds(SNOOZE_WINDOW_MINUTES * 60L))) return null
        val at = Instant.now().plusSeconds(minutes * 60L)
        val time = at.atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        val label = prefs.getString(LAST_LABEL, "").orEmpty()
        val alarm = OwnAlarm(Ids.newId(), time.hour, time.minute, emptySet(), label, enabled = true, at = at, oneShot = true)
        save(stored() + alarm)
        schedule(alarm)
        at
    }

    /** Будильник сработал: одноразовый убираем, повторяющийся ставим на следующий день. */
    fun fired(id: String): OwnAlarm? = synchronized(LOCK) {
        val list = stored()
        val alarm = list.firstOrNull { it.id == id } ?: return null
        val nowMs = Instant.now().toEpochMilli()
        // Помним, что именно звенит: «выключи будильник» должен гасить сигнал, даже если
        // одноразовый будильник уже убран из списка.
        prefs.edit().putLong(LAST_FIRED, nowMs).putString(LAST_LABEL, alarm.label)
            .putString(RINGING, "${alarm.hour}|${alarm.minute}|${alarm.label}|$nowMs").commit()
        if (alarm.oneShot) {
            // Отложенный будильник срабатывает один раз и исчезает из списка.
            save(list.filterNot { it.id == id })
            alarm
        } else {
            val next = nextAt(alarm.time, alarm.days, ZonedDateTime.now().plusSeconds(1))
            val updated = alarm.copy(at = next)
            save(list.map { if (it.id == id) updated else it })
            schedule(updated)
            updated
        }
    }

    fun cancelOne(id: String): Boolean = synchronized(LOCK) {
        val alarm = stored().firstOrNull { it.id == id } ?: return false
        unschedule(alarm)
        save(stored().filterNot { it.id == id })
        true
    }

    /** После перезагрузки телефона системные будильники сбрасываются — ставим свои заново. */
    fun rescheduleAll() = synchronized(LOCK) {
        val now = ZonedDateTime.now()
        val list = stored().map { a ->
            if (!a.enabled) return@map a
            // Пропущенное, пока телефон лежал выключенным, не звенит задним числом: ставим на следующий раз.
            val next = a.at?.takeIf { it.isAfter(now.toInstant()) } ?: nextAt(a.time, a.days, now)
            a.copy(at = next)
        }
        save(list)
        list.filter { it.enabled }.forEach { schedule(it) }
    }

    private fun schedule(a: OwnAlarm) {
        val at = a.at ?: return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(a) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                val show = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, ai.nova.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), pi)
            } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    private fun unschedule(a: OwnAlarm) {
        pending(a, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
    }

    /** У каждого будильника свой адрес: иначе похожие id дали бы один и тот же PendingIntent. */
    private fun pending(a: OwnAlarm, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmReceiver::class.java).setData(Uri.parse("nova-alarm://${a.id}")).putExtra(EXTRA_ID, a.id),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Самый ранний включённый будильник — о нём Нова говорит «следующий». */
    fun next(): OwnAlarm? = all().firstOrNull { it.enabled }

    companion object {
        private const val KEY = "alarms"
        private const val LAST_FIRED = "last_fired"
        private const val LAST_LABEL = "last_label"
        private const val RINGING = "ringing"
        /** Сколько секунд после начала сигнала «выключи будильник» ещё считается выключением звонка. */
        private const val RINGING_WINDOW_MINUTES = 3
        const val EXTRA_ID = "alarm_id"
        const val ACTION_SNOOZE = "ai.nova.alarm.SNOOZE"
        const val SNOOZE_MINUTES = 5
        /** Сколько помним звонок, чтобы «ещё N минут» относилось к будильнику, а не к таймеру. */
        const val SNOOZE_WINDOW_MINUTES = 10
        /** Экземпляров несколько (приложение и приёмник) — чтение и запись списка общие. */
        private val LOCK = Any()

        /** Ближайшее срабатывание: сегодня, если время ещё не прошло, иначе в один из указанных дней. */
        fun nextAt(time: LocalTime, days: Set<DayOfWeek>, now: ZonedDateTime): Instant {
            for (offset in 0..8) {
                val day = now.toLocalDate().plusDays(offset.toLong())
                if (days.isNotEmpty() && day.dayOfWeek !in days) continue
                val at = day.atTime(time.hour, time.minute).atZone(now.zone)
                if (at.isAfter(now)) return at.toInstant()
            }
            return now.plusDays(1).withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0).toInstant()
        }
    }
}

/**
 * Будильник Нова сработал: громкий сигнал и кнопка «Ещё 5 минут».
 * Повторяющийся будильник сразу ставится на следующий день — система не должна его забыть.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarms = NovaAlarms(context)
        if (intent.action == NovaAlarms.ACTION_SNOOZE) {
            alarms.snooze(NovaAlarms.SNOOZE_MINUTES)
            RingService.stop(context)
            return
        }
        val id = intent.getStringExtra(NovaAlarms.EXTRA_ID) ?: return
        val alarm = alarms.fired(id) ?: return
        val time = RuFormat.time(alarm.time)
        val text = if (alarm.label.isBlank()) "Скажите «ещё 5 минут» или коснитесь «Стоп»." else alarm.label
        RingService.start(context, "Будильник $time", text, loud = true, snoozeAlarmId = id)
    }
}
