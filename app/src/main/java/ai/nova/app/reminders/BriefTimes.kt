package ai.nova.app.reminders

import android.content.Context
import java.time.LocalTime

/**
 * Время сводок: утренней, вечерней и итогов недели. Раньше было жёстко задано (8:30, 21:00, 19:00),
 * теперь человек выбирает сам — например, если встаёт в 6 утра или ложится в час ночи.
 */
object BriefTimes {
    private const val PREFS = "nova_brief_times"
    private const val MORNING = "morning"
    private const val EVENING = "evening"
    private const val WEEKLY = "weekly"

    val defaultMorning: LocalTime = LocalTime.of(8, 30)
    val defaultEvening: LocalTime = LocalTime.of(21, 0)
    val defaultWeekly: LocalTime = LocalTime.of(19, 0)

    fun morning(context: Context): LocalTime = read(context, MORNING, defaultMorning)
    fun evening(context: Context): LocalTime = read(context, EVENING, defaultEvening)
    fun weekly(context: Context): LocalTime = read(context, WEEKLY, defaultWeekly)

    fun setMorning(context: Context, time: LocalTime) = write(context, MORNING, time)
    fun setEvening(context: Context, time: LocalTime) = write(context, EVENING, time)
    fun setWeekly(context: Context, time: LocalTime) = write(context, WEEKLY, time)

    private fun read(context: Context, key: String, fallback: LocalTime): LocalTime {
        val minutes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, -1)
        if (minutes < 0 || minutes >= 24 * 60) return fallback
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    private fun write(context: Context, key: String, time: LocalTime) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key, time.hour * 60 + time.minute).apply()
    }

    /** «08:30» — для подписи в настройках. */
    fun label(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
}
