package ai.nova.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.nova.app.settings.AccentColor
import ai.nova.app.ui.theme.AppIcons
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Смена цвета иконки на живом PackageManager. На телефоне после установки у ярлыков состояние DEFAULT,
 * и старый способ проверки «ярлык активен» через resolveActivity его не видел — каждый новый цвет
 * добавлял в лоток ещё одну иконку. Теперь лишние ярлыки гасятся не спрашивая, а тот, через который
 * приложение открыто, — только когда ушли в фон.
 */
@RunWith(AndroidJUnit4::class)
class AppIconsAliasTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm get() = context.packageManager
    private fun alias(accent: AccentColor) = ComponentName(context, AppIcons.aliasOf(accent))
    private val teal get() = alias(AccentColor.TEAL)
    private val green get() = alias(AccentColor.GREEN)
    private val purple get() = alias(AccentColor.PURPLE)

    /** Как сразу после установки: у всех ярлыков состояние из манифеста (DEFAULT). */
    private fun freshInstall() {
        AccentColor.entries.forEach {
            pm.setComponentEnabledSetting(alias(it), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }

    private fun state(component: ComponentName) = pm.getComponentEnabledSetting(component)

    /** Ярлыки, которые лоток сейчас показывает: всё, что не выключено явно. */
    private fun visible() = AccentColor.entries.filter { state(alias(it)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED }

    @After
    fun restoreTeal() {
        AccentColor.entries.forEach {
            pm.setComponentEnabledSetting(alias(it), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
        AccentColor.entries.filter { it != AccentColor.TEAL }.forEach {
            pm.setComponentEnabledSetting(alias(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        pm.setComponentEnabledSetting(teal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    @Test
    fun changingAccentLeavesExactlyOneIcon() {
        freshInstall()

        val deferred = AppIcons.apply(context, AccentColor.GREEN)

        assertEquals("новая иконка включена", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(green))
        assertEquals("старый ярлык погашен сразу", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(teal))
        assertTrue("гасить в фоне нечего: приложение открыто не через ярлык", deferred.isEmpty())
        assertEquals("в лотке одна иконка", listOf(AccentColor.GREEN), visible())
    }

    @Test
    fun launchedAliasIsKeptUntilBackground() {
        freshInstall()

        val deferred = AppIcons.apply(context, AccentColor.GREEN, keep = teal)

        assertEquals("ярлык, через который открыто приложение, ещё жив", listOf(teal), deferred)
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(teal))
        assertEquals("прочие ярлыки погашены сразу", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(purple))

        AppIcons.disable(context, deferred)

        assertEquals("в фоне остаётся одна иконка", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(teal))
        assertEquals(listOf(AccentColor.GREEN), visible())
    }

    @Test
    fun repeatedAccentChangesDoNotAccumulateIcons() {
        freshInstall()

        // Именно так росло число иконок на телефоне: цвет меняли несколько раз подряд.
        listOf(AccentColor.INDIGO, AccentColor.GREEN, AccentColor.SKY, AccentColor.PURPLE)
            .forEach { AppIcons.apply(context, it) }

        assertEquals("сколько раз ни меняй цвет — иконка одна", listOf(AccentColor.PURPLE), visible())
    }

    @Test
    fun leftoversFromKilledProcessAreCleanedUp() {
        freshInstall()
        // Прошлый запуск убили до onStop/onDestroy: включёнными остались чужие ярлыки.
        pm.setComponentEnabledSetting(teal, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(purple, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)

        AppIcons.apply(context, AccentColor.GREEN)

        assertEquals("запуск приложения приводит лоток в порядок", listOf(AccentColor.GREEN), visible())
    }

    @Test
    fun sameAccentKeepsOneIcon() {
        freshInstall()

        AppIcons.apply(context, AccentColor.TEAL, keep = teal)

        assertEquals(listOf(AccentColor.TEAL), visible())
    }
}
