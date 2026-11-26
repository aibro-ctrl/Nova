package ai.nova.app.ui.theme

import ai.nova.core.nlp.ExpenseCategories
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Цвета долей трат: категории должны различаться на полосе, а не сливаться в один тон. */
class CategoryColorsTest {

    /** Разница оттенков по CIE76 (ΔE): так её видит глаз, а не сравнение каналов. */
    private fun deltaE(a: Color, b: Color): Double {
        fun lab(c: Color): DoubleArray {
            fun lin(v: Float): Double { val u = v.toDouble(); return if (u <= 0.04045) u / 12.92 else Math.pow((u + 0.055) / 1.055, 2.4) }
            val r = lin(c.red); val g = lin(c.green); val b2 = lin(c.blue)
            val x = (r * 0.4124 + g * 0.3576 + b2 * 0.1805) / 0.95047
            val y = r * 0.2126 + g * 0.7152 + b2 * 0.0722
            val z = (r * 0.0193 + g * 0.1192 + b2 * 0.9505) / 1.08883
            fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
            val fx = f(x); val fy = f(y); val fz = f(z)
            return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
        }
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return Math.sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }

    /** Раньше все доли были одной бирюзово-шалфейной гаммой с разницей ΔE ≈ 7 — полоса сливалась. */
    @Test
    fun paletteHasNoTwinColors() {
        for (i in CategoryPalette.indices) {
            for (j in i + 1 until CategoryPalette.size) {
                val d = deltaE(CategoryPalette[i], CategoryPalette[j])
                assertTrue("оттенки $i и $j слишком похожи (ΔE $d)", d >= 18.0)
            }
        }
    }

    @Test
    fun neighboringCategoriesGetDifferentColors() {
        // Больше категорий, чем оттенков: соседние всё равно не должны совпадать.
        val names = ExpenseCategories.all.take(CategoryPalette.size + 4)
        val colors = categoryColors(names)
        assertEquals(names.size, colors.size)
        names.forEachIndexed { i, name ->
            val next = names.getOrNull(i + 1) ?: return@forEachIndexed
            assertNotEquals("«$name» и «$next» одного цвета", colors.getValue(name), colors.getValue(next))
        }
    }

    @Test
    fun categoryKeepsItsColor() {
        val alone = categoryColors(listOf("Продукты"))
        val withNeighbors = categoryColors(listOf("Транспорт", "Продукты", "Кафе и рестораны"))
        assertEquals(alone.getValue("Продукты"), withNeighbors.getValue("Продукты"))
    }

    @Test
    fun unknownCategoryStillGetsAColor() {
        val colors = categoryColors(listOf("Хобби", "Другое"))
        assertEquals(2, colors.size)
        assertNotEquals(colors.getValue("Хобби"), colors.getValue("Другое"))
    }
}
