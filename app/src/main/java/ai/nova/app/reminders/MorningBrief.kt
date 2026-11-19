package ai.nova.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.nova.app.NovaApp
import ai.nova.app.R
import ai.nova.app.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Утренняя сводка: в 8:30 одно уведомление — задачи на сегодня, просроченные и напоминания дня.
 * Ничего не нужно открывать и спрашивать; если на сегодня пусто — уведомления нет.
 */
object MorningBrief {
    private const val WORK = "nova-morning-brief"
    const val NOTIFICATION_ID = Notifications.BRIEF_ID
    private fun at(context: Context): LocalTime = BriefTimes.morning(context)

    fun schedule(context: Context, enabled: Boolean, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        var next = now.with(at(context)).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        // KEEP: не сдвигаем уже запланированное время при каждом запуске приложения.
        // REPLACE: человек сменил время в настройках — пересобираем расписание.
        val policy = if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WORK, policy, request)
    }

    /** Текст сводки (погода, задачи, напоминания) или null, если сказать нечего. */
    suspend fun text(context: Context): String? {
        val c = (context.applicationContext as NovaApp).container
        c.awaitReady()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val tasks = c.store.tasks.all().filter { !it.done }
        val dueToday = tasks.filter { it.dueDate == today }
        val overdue = tasks.filter { it.dueDate?.isBefore(today) == true }
        val reminders = c.store.reminders.active().filter { it.triggerAt.atZone(zone).toLocalDate() == today }
            .sortedBy { it.triggerAt }
        val weather = runCatching { c.skills.weatherLine(c.assistantSettings()) }.getOrNull()
        if (dueToday.isEmpty() && overdue.isEmpty() && reminders.isEmpty()) return weather
        return buildList {
            weather?.let { add(it) }
            if (dueToday.isNotEmpty()) add("Задачи: " + dueToday.take(4).joinToString(", ") { it.title } + if (dueToday.size > 4) " и ещё ${dueToday.size - 4}" else "")
            if (reminders.isNotEmpty()) add("Напоминания: " + reminders.take(3).joinToString(", ") {
                val t = it.triggerAt.atZone(zone).toLocalTime()
                (if (it.isMissed(java.time.Instant.now())) "пропущено " else "") + "%02d:%02d %s".format(t.hour, t.minute, it.text)
            })
            if (overdue.isNotEmpty()) add("Просрочено: ${overdue.size}")
        }.joinToString("\n")
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = (applicationContext as NovaApp).container
            if (!c.settings.current().morningBrief) return Result.success()
            val text = text(applicationContext) ?: return Result.success()
            val open = PendingIntent.getActivity(
                applicationContext, NOTIFICATION_ID, Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_stat_nova)
                .setContentTitle("Доброе утро! План на сегодня")
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // На экране блокировки — без подробностей.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_stat_nova).setContentTitle("План на сегодня готов").build(),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(applicationContext, NOTIFICATION_ID, n)
            return Result.success()
        }
    }
}
