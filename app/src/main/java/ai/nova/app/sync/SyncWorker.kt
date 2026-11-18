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
import ai.nova.core.auth.AuthState
import java.util.concurrent.TimeUnit

/** Фоновая синхронизация с Supabase. Работает только при наличии сети; при ошибке — повтор с экспоненциальной паузой. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NovaApp).container
        c.awaitReady()
        if (c.auth.state.value !is AuthState.SignedIn) return Result.success()
        val report = c.syncEngine.sync()
        if (report.pulled > 0) c.rescheduleReminders()
        return when {
            report.ok -> Result.success()
            runAttemptCount >= 5 -> Result.failure()
            else -> Result.retry()
        }
    }
}

class SyncScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Синхронизация вскоре после локального изменения (частые изменения объединяются). */
    fun requestSoon(delaySeconds: Long = 5) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
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
        private const val PERIODIC = "nova-sync-periodic"
        private const val ONE_TIME = "nova-sync-soon"
    }
}
