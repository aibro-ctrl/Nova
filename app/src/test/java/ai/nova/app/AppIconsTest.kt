package ai.nova.app

import ai.nova.app.settings.AccentColor
import ai.nova.app.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Иконка в лотке: ровно один ярлык — выбранный цвет. Раньше «активность» ярлыка определяли через
 * resolveActivity, а он не находит ярлыки лотка (у них нет CATEGORY_DEFAULT), поэтому каждый новый
 * цвет добавлял иконку, и старые никто не гасил.
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
        assertFalse(AppIcons.aliasesToDisable(AccentColor.TEAL).contains(teal))
    }

    @Test
    fun `all other aliases are disabled`() {
        val green = AppIcons.aliasOf(AccentColor.GREEN)
        val disabled = AppIcons.aliasesToDisable(AccentColor.GREEN)
        assertEquals(AccentColor.entries.size - 1, disabled.size)
        assertFalse(disabled.contains(green))
        assertTrue(disabled.contains(AppIcons.aliasOf(AccentColor.TEAL)))
        assertTrue(disabled.contains(AppIcons.aliasOf(AccentColor.SKY)))
    }

    @Test
    fun `alias we were launched from is kept for now`() {
        val teal = AppIcons.aliasOf(AccentColor.TEAL)
        val disabled = AppIcons.aliasesToDisable(AccentColor.GREEN, listOf(teal))
        assertFalse("через бирюзовый ярлык открыто приложение", disabled.contains(teal))
        assertEquals(AccentColor.entries.size - 2, disabled.size)
    }
}
