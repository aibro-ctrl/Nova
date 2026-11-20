package ai.nova.app

import ai.nova.app.settings.WidgetStyle
import ai.nova.app.widget.WidgetRender
import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Все три вида виджета существуют, содержат нужные элементы и собираются в RemoteViews. */
@RunWith(AndroidJUnit4::class)
class WidgetLayoutsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Настройки показывают этот же макет в предпросмотре, поэтому вид и макет не должны расходиться.
    private val layouts = WidgetStyle.entries.associateWith { WidgetRender.layoutOf(it) }

    @Test
    fun everyStyleInflatesWithRootAndWave() {
        for ((style, layout) in layouts) {
            val view = LayoutInflater.from(context).inflate(layout, null)
            assertNotNull("$style: widget_root", view.findViewById(R.id.widget_root))
            assertNotNull("$style: widget_wave", view.findViewById(R.id.widget_wave))
        }
    }

    @Test
    fun remoteViewsBuildsForEveryStyle() {
        for ((style, layout) in layouts) {
            val views = RemoteViews(context.packageName, layout)
            views.setImageViewResource(R.id.widget_wave, WidgetRender.FRAMES[0])
            // Применение к чужому родителю — единственный способ проверить, что все id разложены верно.
            assertNotNull("$style", views.apply(context, FrameLayout(context)))
        }
    }

    @Test
    fun listWidgetsInflate() {
        // У этих виджетов общий фон с «Плашкой»: проверяем, что RemoteViews принимает его целиком.
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        views.setTextViewText(R.id.widget_list_title, "Задачи на сегодня")
        assertNotNull(views.apply(context, FrameLayout(context)))
    }

    @Test
    fun waveFramesAreComplete() {
        assertEquals(8, WidgetRender.FRAMES.size)
        for (frame in WidgetRender.FRAMES) assertNotNull(context.getDrawable(frame))
    }
}
