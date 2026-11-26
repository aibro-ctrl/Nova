package ai.nova.app.ui.theme

import ai.nova.core.nlp.ExpenseCategories
import androidx.compose.ui.graphics.Color

/**
 * Цвета долей в разбивке трат.
 *
 * Раньше вся гамма была бирюзово-шалфейной: соседние доли отличались на пару тонов, и полоса трат
 * сливалась в одно пятно. Теперь оттенки разнесены по кругу при той же сдержанной насыщенности —
 * полоса читается по сегментам, а не как градиент.
 */
val CategoryPalette: List<Color> = listOf(
    Color(0xFF0F766E), Color(0xFF2F6FB0), Color(0xFF3D3C96), Color(0xFF82469E),
    Color(0xFFA33D86), Color(0xFFB03F5E), Color(0xFFB84A38), Color(0xFFC06D12),
    Color(0xFF9C7D10), Color(0xFF5F8C2B), Color(0xFF1E8C3F), Color(0xFF8A6A50),
)

/** Постоянный оттенок категории: «Продукты» одного цвета в любой период, а не «какой по счёту». */
fun preferredCategorySlot(category: String): Int {
    val known = ExpenseCategories.all.indexOf(category)
    val seed = if (known >= 0) known else Math.floorMod(category.lowercase().hashCode(), CategoryPalette.size)
    return seed % CategoryPalette.size
}

/**
 * Цвета для отчёта: пока в палитре есть свободные оттенки, две категории одного цвета не получают —
 * иначе «Продукты» и «Транспорт» снова сливаются. Перебор идёт от постоянного оттенка категории,
 * поэтому одна и та же категория сохраняет цвет, когда соседей в отчёте становится больше.
 */
fun categoryColors(categories: List<String>): Map<String, Color> {
    val used = mutableSetOf<Int>()
    val out = LinkedHashMap<String, Color>(categories.size)
    for (name in categories) {
        val start = preferredCategorySlot(name)
        var slot = start
        var step = 0
        while (slot in used && step < CategoryPalette.size) {
            step++
            slot = (start + step) % CategoryPalette.size
        }
        used += slot
        out[name] = CategoryPalette[slot]
    }
    return out
}
