package ai.nova.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ai.nova.app.R
import ai.nova.app.assist.AssistActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Виджет на рабочий стол: быстрый вызов Нова, даже если она не выбрана ассистентом по умолчанию.
 * Пока Нова слушает, волна в орбе «дышит» теми же кадрами, что и на главном экране; в покое — один кадр,
 * чтобы виджет не тратил батарею на постоянную анимацию.
 */
class NovaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pi = openIntent(context)
        val views = RemoteViews(context.packageName, R.layout.widget_nova).apply {
            setOnClickPendingIntent(R.id.widget_root, pi)
            setImageViewResource(R.id.widget_wave, FRAMES[0])
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Кадры фирменной волны: те же, что в анимации прослушивания на главном экране. */
        val FRAMES = intArrayOf(
            R.drawable.widget_wave_0, R.drawable.widget_wave_1, R.drawable.widget_wave_2, R.drawable.widget_wave_3,
            R.drawable.widget_wave_4, R.drawable.widget_wave_5, R.drawable.widget_wave_6, R.drawable.widget_wave_7,
        )

        private var waveJob: Job? = null

        private fun openIntent(context: Context): PendingIntent {
            val intent = Intent(context, AssistActivity::class.java).setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /**
         * Волна в виджете: во время прослушивания кадры меняются, в покое — снова первый кадр.
         * Работает, даже если Нова вызвана словом «Нова» при свёрнутом приложении.
         */
        fun wave(context: Context, scope: CoroutineScope, active: Boolean) {
            waveJob?.cancel()
            waveJob = null
            if (!active) {
                frame(context, 0)
                return
            }
            waveJob = scope.launch {
                var i = 0
                while (isActive) {
                    frame(context, i)
                    i = (i + 1) % FRAMES.size
                    delay(220)
                }
            }
        }

        private fun frame(context: Context, index: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NovaWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_nova).apply {
                setOnClickPendingIntent(R.id.widget_root, openIntent(context))
                setImageViewResource(R.id.widget_wave, FRAMES[index % FRAMES.size])
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
