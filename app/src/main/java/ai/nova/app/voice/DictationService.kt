package ai.nova.app.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ai.nova.app.R
import ai.nova.app.reminders.Notifications
import ai.nova.core.util.Logger

/**
 * Долгая диктовка при погашенном экране: пока служба работает, Android не забирает микрофон и не усыпляет
 * процессор ([PARTIAL_WAKE_LOCK] с таймаутом, чтобы зависшая диктовка не съела батарею). Текст диктовки
 * копится в [ai.nova.app.voice.VoiceController], служба только держит сессию живой.
 */
class DictationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Нова записывает")
            .setContentText("Диктуйте — текст не потеряется, даже если экран погаснет")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        }.onFailure { Logger.w(TAG, "Диктовка без службы: ${it.message}") }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nova:dictation")?.apply {
                setReferenceCounted(false)
                runCatching { acquire(WAKE_TIMEOUT_MS) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Dictation"
        private const val ID = 1003
        /** Диктовка ограничена объёмом текста; таймаут — страховка от вечно висящей блокировки. */
        private const val WAKE_TIMEOUT_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DictationService::class.java)) }
                .onFailure { Logger.w(TAG, "Не удалось начать диктовку в фоне: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DictationService::class.java)) }
        }
    }
}
