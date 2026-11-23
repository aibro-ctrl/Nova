package ai.nova.app.debug

import ai.nova.app.NovaApp
import ai.nova.app.reminders.MorningBrief
import ai.nova.app.reminders.SummaryDelivery
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Только debug-сборка: собрать сводку по расписанию прямо сейчас, не дожидаясь 8:30 или 21:00.
 *
 * adb shell am broadcast -a ai.nova.app.DEBUG_BRIEF -p ai.nova.app --es kind morning
 * kind: morning (по умолчанию), evening, weekly, text (текст из extra text — проверка сценария).
 * Полный текст печатается в logcat с тегом NovaProbe.
 */
class BriefProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NovaApp ?: return
        val kind = intent.getStringExtra("kind").orEmpty().ifBlank { "morning" }
        val custom = intent.getStringExtra("text").orEmpty()
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                val c = app.container
                c.awaitReady()
                val text = when (kind) {
                    "evening" -> c.eveningSummary()
                    "weekly" -> c.engine.weeklySummary().removePrefix("Итоги недели. ")
                    "text" -> custom
                    else -> MorningBrief.text(context).orEmpty()
                }
                val full = SummaryDelivery.deliver(context, c, NOTIFICATION_ID, title = "Проверка сводки", text = text)
                Log.i(TAG, "BRIEF ($kind): $full")
            } catch (e: Exception) {
                Log.e(TAG, "BRIEF FAIL ($kind)", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NovaProbe"
        const val NOTIFICATION_ID = 4242
    }
}
