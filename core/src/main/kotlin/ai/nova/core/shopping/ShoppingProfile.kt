package ai.nova.core.shopping

import ai.nova.core.domain.MemoryRepository
import ai.nova.core.model.MemoryItem
import ai.nova.core.nlp.RuTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Память покупок: размеры, бренды, вид животного.
 *
 * Использует общую долгосрочную память Нова (раздел «Память», категория [CATEGORY]) — второй системы
 * памяти в приложении нет. Постоянные факты отделены от разовых: «найди кеды до 10 тысяч» в память
 * не попадёт, а «у меня 46-й размер» — попадёт, и Нова об этом скажет.
 */
class ShoppingProfile(private val memory: MemoryRepository) {

    /** Факты для текущего поиска. Читаются один раз перед разбором фразы. */
    suspend fun facts(): ShoppingFacts {
        val items = runCatching { memory.all() }.getOrElse { emptyList() }
        val parsed = items.filter { it.category == CATEGORY }.mapNotNull { fact(it) }
        return ShoppingFacts(
            shoeSize = parsed.lastOrNull { it.attribute == "shoe_size" }?.value,
            clothesSize = parsed.lastOrNull { it.attribute == "clothes_size" }?.value,
            preferredBrands = parsed.filter { it.attribute == "preferred_brand" }.map { it.value },
            petKind = parsed.lastOrNull { it.attribute == "pet_kind" }?.value,
        )
    }

    /** Все сохранённые факты — для экрана «что Нова помнит о покупках». */
    suspend fun all(): List<ShoppingFact> =
        runCatching { memory.all() }.getOrElse { emptyList() }
            .filter { it.category == CATEGORY }
            .mapNotNull { fact(it) }

    /**
     * Запоминает факт. Возвращает сам факт — по нему Нова честно говорит, что запомнила.
     * Временные параметры сюда не попадают: их отсекает [LONG_TERM] на входе.
     */
    suspend fun remember(attribute: String, value: String): ShoppingFact? {
        if (attribute !in ShoppingFacts.LONG_TERM) return null
        val clean = value.trim()
        if (clean.isEmpty()) return null
        // Повторный тот же факт не плодит записи.
        val same = all().firstOrNull { it.attribute == attribute && it.value.equals(clean, true) }
        if (same != null) return same
        val content = buildJsonObject {
            put("attribute", attribute)
            put("value", clean)
            put("source", "user_statement")
            put("confidence", "1.0")
        }.toString()
        val item = runCatching { memory.create(content, CATEGORY) }.getOrNull() ?: return null
        return fact(item) ?: ShoppingFact(attribute, clean, item.id)
    }

    /** Убирает факт из памяти — человек может передумать. */
    suspend fun forget(attribute: String): Int {
        var removed = 0
        runCatching { memory.all() }.getOrElse { emptyList() }
            .filter { it.category == CATEGORY }
            .filter { fact(it)?.attribute == attribute }
            .forEach { if (runCatching { memory.delete(it.id) }.getOrDefault(false)) removed++ }
        return removed
    }

    /**
     * Факт из реплики: «у меня 46 размер», «мой размер обуви 46», «беру только Puma».
     * null — в реплике нет постоянного факта.
     */
    fun factFrom(text: String, category: ShoppingCategory?): Pair<String, String>? {
        val t = RuTokenizer.normalize(text)
        val size = Regex("""(?:размер\w*|р-р)\s*(\d{2,3})|(\d{2,3})(?:[.,]5)?\s*(?:-?й\s*)?размер\w*""").find(t)
        size?.let { m ->
            val value = m.groupValues[1].ifBlank { m.groupValues[2] }
            val shoes = "обув" in t || category == ShoppingCategory.SHOES
            return (if (shoes) "shoe_size" else "clothes_size") to value
        }
        ShoppingIntent.animal(t)?.let { return "pet_kind" to it }
        if ("любим" in t || "предпочита" in t || "только" in t) {
            ShoppingIntent.brand(text)?.let { return "preferred_brand" to it }
        }
        return null
    }

    private fun fact(item: MemoryItem): ShoppingFact? {
        val json = runCatching { Json.parseToJsonElement(item.content) as? JsonObject }.getOrNull() ?: return null
        val attribute = runCatching { json["attribute"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        val value = runCatching { json["value"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return ShoppingFact(attribute, value, item.id)
    }

    companion object {
        /** Категория в общей памяти Нова: видно в разделе «Память» вместе с остальными фактами. */
        const val CATEGORY = "покупки"
    }
}

/** Один сохранённый факт о покупках. */
data class ShoppingFact(val attribute: String, val value: String, val id: String? = null) {
    /** Как это звучит для человека. */
    val label: String
        get() = when (attribute) {
            "shoe_size" -> "Размер обуви: $value"
            "clothes_size" -> "Размер одежды: $value"
            "preferred_brand" -> "Любимый бренд: $value"
            "pet_kind" -> "Питомец: $value"
            "preferred_color" -> "Любимый цвет: $value"
            else -> "$attribute: $value"
        }
}
