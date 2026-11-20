package ai.nova.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.nova.app.settings.AccentColor
import ai.nova.app.ui.theme.AppIcons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Смена цвета иконки на живом PackageManager: старый ярлык нельзя гасить сразу — приложение,
 * запущенное через него, система закрывает. Гасим только когда ушли в фон.
 */
@RunWith(AndroidJUnit4::class)
class AppIconsAliasTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm get() = context.packageManager
    private val teal get() = ComponentName(context, AppIcons.aliasOf(AccentColor.TEAL))
    private val green get() = ComponentName(context, AppIcons.aliasOf(AccentColor.GREEN))

    @After
    fun restoreTeal() {
        pm.setComponentEnabledSetting(green, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(teal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    @Test
    fun changingAccentKeepsOldAliasUntilBackground() {
        // Состояние свежей установки: активен бирюзовый ярлык, зелёный выключен.
        pm.setComponentEnabledSetting(green, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(teal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        val deferred = AppIcons.apply(context, AccentColor.GREEN)

        assertTrue("новая иконка включена", isEnabled(green))
        assertTrue("старая иконка ещё жива: через неё открыто приложение", isEnabled(teal))
        assertEquals("старый ярлык отложен до фона", listOf(teal), deferred)

        AppIcons.disable(context, deferred)

        assertTrue("новая иконка на месте", isEnabled(green))
        assertFalse("в фоне остаётся одна иконка", isEnabled(teal))
    }

    @Test
    fun sameAccentChangesNothing() {
        pm.setComponentEnabledSetting(green, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(teal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        val deferred = AppIcons.apply(context, AccentColor.TEAL)

        assertTrue("активный ярлык включён", isEnabled(teal))
        assertTrue("гасить нечего", deferred.isEmpty())
    }

    private fun isEnabled(component: ComponentName): Boolean =
        pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
