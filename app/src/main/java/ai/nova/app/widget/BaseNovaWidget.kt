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
import ai.nova.app.settings.WidgetStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Общая часть виджетов Нова: «Плашка», «Орб» и «Карточка» — три отдельных виджета в списке рабочего стола.
 *
 * Отдельные виджеты, а не один с настройкой вида: у каждого свой размер ячейки (3×1, 1×1, 2×2), поэтому
 * на рабочем столе он выглядит ровно так же, как в предпросмотре в настройках, и не сжимается в чужую форму.
 * Пока Нова слушает, волна «дышит» теми же кадрами, что на главном экране; в покое — один кадр.
 */
abstract class BaseNovaWidget(private val style: WidgetStyle) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, WidgetRender.views(context, style)) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        // Растянули — перерисовываем: кнопка и текст должны встать заново, а не остаться от прежнего размера.
        manager.updateAppWidget(id, WidgetRender.views(context, style))
    }

    companion object {
        /** Перерисовать все виды виджетов (после смены цвета или настроек) — вызывается из приложения. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            listOf(NovaWidget::class.java, NovaOrbWidget::class.java, NovaCardWidget::class.java).forEach { cls ->
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: return@forEach
                val style = WidgetRender.styleOf(cls)
                ids.forEach { manager.updateAppWidget(it, WidgetRender.views(context, style)) }
            }
        }
    }
}

/** «Плашка»: орб, имя и подсказка в одну строку — 3×1. */
class NovaWidget : BaseNovaWidget(WidgetStyle.PILL)

/** «Орб»: только фирменный круг с волной — 1×1. */
class NovaOrbWidget : BaseNovaWidget(WidgetStyle.ORB)

/** «Карточка»: крупный орб, имя, подсказка и кнопка — 2×2. */
class NovaCardWidget : BaseNovaWidget(WidgetStyle.CARD)

/**
 * Отрисовка виджетов: макеты, кадры волны и переход в окно ассистента по касанию.
 * Живёт отдельно от провайдеров, чтобы настройки могли показать тот же макет в предпросмотре.
 */
object WidgetRender {
    /** Кадры фирменной волны: те же, что в анимации прослушивания на главном экране. */
    val FRAMES = intArrayOf(
        R.drawable.widget_wave_0, R.drawable.widget_wave_1, R.drawable.widget_wave_2, R.drawable.widget_wave_3,
        R.drawable.widget_wave_4, R.drawable.widget_wave_5, R.drawable.widget_wave_6, R.drawable.widget_wave_7,
    )

    private var waveJob: Job? = null

    fun styleOf(cls: Class<*>): WidgetStyle = when (cls) {
        NovaOrbWidget::class.java -> WidgetStyle.ORB
        NovaCardWidget::class.java -> WidgetStyle.CARD
        else -> WidgetStyle.PILL
    }

    fun layoutOf(style: WidgetStyle): Int = when (style) {
        WidgetStyle.PILL -> R.layout.widget_nova
        WidgetStyle.ORB -> R.layout.widget_nova_orb
        WidgetStyle.CARD -> R.layout.widget_nova_card
    }

    fun views(context: Context, style: WidgetStyle): RemoteViews =
        RemoteViews(context.packageName, layoutOf(style)).apply {
            setOnClickPendingIntent(R.id.widget_root, openIntent(context))
            setImageViewResource(R.id.widget_wave, FRAMES[0])
        }

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
        val wave = FRAMES[index % FRAMES.size]
        listOf(NovaWidget::class.java, NovaOrbWidget::class.java, NovaCardWidget::class.java).forEach { cls ->
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: return@forEach
            if (ids.isEmpty()) return@forEach
            val style = styleOf(cls)
            val views = RemoteViews(context.packageName, layoutOf(style)).apply {
                setOnClickPendingIntent(R.id.widget_root, openIntent(context))
                setImageViewResource(R.id.widget_wave, wave)
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
