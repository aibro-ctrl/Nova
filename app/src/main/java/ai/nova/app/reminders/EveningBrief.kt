package ai.nova.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.nova.app.NovaApp
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Вечерняя сводка в 21:00: настроение, закрытые задачи, траты, вода и привычки за день, и приглашение
 * записать, как прошёл день. По умолчанию выключена; только на телефоне.
 * Отдаётся через [SummaryDelivery]: приветствие по имени, полный текст в истории и озвучка целиком.
 */
object EveningBrief {
    private const val WORK = "nova-evening-brief"
    private fun at(context: Context): LocalTime = BriefTimes.evening(context)

    fun schedule(context: Context, enabled: Boolean, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val now = ZonedDateTime.now()
        var next = now.with(at(context)).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WORK, policy, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = (applicationContext as NovaApp).container
            if (!c.settings.current().eveningBrief) return Result.success()
            c.awaitReady()
            // Выключенные блоки не озвучиваются; если выключили всё — сводки нет.
            val summary = c.eveningSummary()
            if (summary.isBlank()) return Result.success()
            val health = ai.nova.core.health.BriefBlocks.on(c.settings.current().briefBlocks, ai.nova.core.health.BriefBlocks.HEALTH)
            val text = if (health) summary + "\nСкажите «как прошёл день», чтобы записать настроение." else summary
            // Сводка целиком: приветствие по имени, история общения и озвучка — не только первая строка в пуше.
            SummaryDelivery.deliver(applicationContext, c, Notifications.EVENING_ID, title = "Итоги дня", text = text)
            return Result.success()
        }
    }
}
