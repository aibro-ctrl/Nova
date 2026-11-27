package ai.nova.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.nova.app.NovaApp
import java.util.concurrent.TimeUnit

/**
 * Фоновая отправка записей в Google Таблицу. Работает только при включённой автосинхронизации
 * и только при сети: без интернета таблица недоступна, а будить телефон зря незачем.
 */
class SheetsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NovaApp).container
        c.awaitReady()
        if (!c.settings.current().sheetsAutoSync) return Result.success()
        // Не настроено — повторять бессмысленно: ни адреса, ни таблицы, ни кода доступа ещё нет.
        if (!c.sheetsSync.isConfigured()) return Result.success()
        val report = c.sheetsSync.sync()
        // Из таблицы могли приехать напоминания — будильники нужно поставить заново.
        if (report.pulled > 0) c.rescheduleReminders()
        return when {
            report.ok -> Result.success()
            runAttemptCount >= 5 -> Result.failure()
            else -> Result.retry()
        }
    }
}

class SheetsScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SheetsWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Синхронизация вскоре после изменения: частые правки объединяются в один запрос. */
    fun requestSoon(delaySeconds: Long = 10) {
        val request = OneTimeWorkRequestBuilder<SheetsWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
    }

    companion object {
        private const val PERIODIC = "nova-sheets-periodic"
        private const val ONE_TIME = "nova-sheets-soon"
    }
}
