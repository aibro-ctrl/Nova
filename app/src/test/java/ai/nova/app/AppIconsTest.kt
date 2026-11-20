package ai.nova.app

import ai.nova.app.settings.AccentColor
import ai.nova.app.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Иконка в лотке: при запуске гасится всё, кроме выбранного цвета, и никогда — выбранный.
 * Раньше гасили и активный ярлык запуска, из-за чего система закрывала приложение на старте.
 */
class AppIconsTest {

    @Test
    fun `alias name follows accent`() {
        assertEquals("ai.nova.app.launcher.IconTeal", AppIcons.aliasOf(AccentColor.TEAL))
        assertEquals("ai.nova.app.launcher.IconGreen", AppIcons.aliasOf(AccentColor.GREEN))
    }

    @Test
    fun `chosen alias is never disabled`() {
        val teal = AppIcons.aliasOf(AccentColor.TEAL)
        assertTrue(AppIcons.aliasesToDefer(AccentColor.TEAL, listOf(teal)).isEmpty())
    }

    @Test
    fun `other active alias is deferred`() {
        val teal = AppIcons.aliasOf(AccentColor.TEAL)
        val green = AppIcons.aliasOf(AccentColor.GREEN)
        // Ситуация со свежей установки: активен бирюзовый ярлык, в настройках выбран зелёный.
        assertEquals(listOf(teal), AppIcons.aliasesToDefer(AccentColor.GREEN, listOf(teal, green)))
    }

    @Test
    fun `nothing to disable when only chosen is active`() {
        val sky = AppIcons.aliasOf(AccentColor.SKY)
        assertTrue(AppIcons.aliasesToDefer(AccentColor.SKY, listOf(sky)).isEmpty())
    }
}
