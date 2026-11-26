package ai.nova.core.shopping

import ai.nova.core.domain.MemoryRepository
import ai.nova.core.model.MemoryItem
import ai.nova.core.nlp.RuTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Память покупок: размеры, бренды, вид животного.
 *
 * Использует общую долгосрочную память Нова (раздел «Память», категория [CATEGORY]) — второй системы
 * памяти в приложении нет. Постоянные факты отделены от разовых: «купи кеды до 10 тысяч» в память
 * не попадёт, а «у меня 46-й размер» — попадёт, и Нова об этом скажет.
 */
class ShoppingProfile(private val memory: MemoryRepository) {

    /** Факты для текущего поиска. Читаются один раз перед разбором фразы. */
    suspend fun facts(): ShoppingFacts {
        val parsed = stored()
        return ShoppingFacts(
            shoeSize = parsed.lastOrNull { it.first.attribute == "shoe_size" }?.first?.value,
            clothesSize = parsed.lastOrNull { it.first.attribute == "clothes_size" }?.first?.value,
            preferredBrands = parsed.filter { it.first.attribute == "preferred_brand" }.map { it.first.value },
            petKind = parsed.lastOrNull { it.first.attribute == "pet_kind" }?.first?.value,
        )
    }

    /** Все сохранённые факты — для экрана «что Нова помнит о покупках». */
    suspend fun all(): List<ShoppingFact> = stored().map { it.first }

    /**
     * Записи памяти покупок. Старые версии хранили служебную строку вида
     * `{"attribute":"shoe_size","value":"44"}` — в разделе «Память» это выглядело как код.
     * Такие записи переписываем человеческим текстом один раз, читая их же.
     */
    private suspend fun stored(): List<Pair<ShoppingFact, MemoryItem>> =
        runCatching { memory.all() }.getOrElse { emptyList() }
            .filter { it.category == CATEGORY }
            .mapNotNull { item ->
                val fact = fact(item) ?: return@mapNotNull null
                val readable = item.content.trim() != fact.label
                val fixed = if (readable) {
                    runCatching { memory.update(item.copy(content = fact.label)) }.getOrDefault(item)
                } else item
                fact to fixed
            }

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
        // В памяти — обычная фраза: её видно в разделе «Память» и понятно без расшифровки.
        val content = ShoppingFact(attribute, clean).label
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

    private fun fact(item: MemoryItem): ShoppingFact? =
        ShoppingFact.parse(item.content)?.copy(id = item.id)

    companion object {
        /** Категория в общей памяти Нова: видно в разделе «Память» вместе с остальными фактами. */
        const val CATEGORY = "покупки"
    }
}

/** Один сохранённый факт о покупках. */
data class ShoppingFact(val attribute: String, val value: String, val id: String? = null) {
    /** Как это звучит для человека. */
    val label: String get() = labelOf(attribute, value)

    companion object {
        /** Подписи фактов: по ним же читаются записи, сохранённые обычным текстом. */
        val LABELS: Map<String, String> = linkedMapOf(
            "shoe_size" to "Размер обуви: ",
            "clothes_size" to "Размер одежды: ",
            "preferred_brand" to "Любимый бренд: ",
            "pet_kind" to "Питомец: ",
            "preferred_color" to "Любимый цвет: ",
        )

        fun labelOf(attribute: String, value: String): String = (LABELS[attribute] ?: "$attribute: ") + value

        /** Факт из записи памяти: обычный текст («Размер обуви: 44») или старый служебный JSON. */
        fun parse(content: String): ShoppingFact? {
            val raw = content.trim()
            LABELS.forEach { (attribute, label) ->
                if (raw.length >= label.length && raw.regionMatches(0, label, 0, label.length, ignoreCase = true)) {
                    val value = raw.substring(label.length).trim()
                    if (value.isNotEmpty()) return ShoppingFact(attribute, value)
                }
            }
            val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            val attribute = runCatching { json["attribute"]?.jsonPrimitive?.content }.getOrNull() ?: return null
            val value = runCatching { json["value"]?.jsonPrimitive?.content }.getOrNull() ?: return null
            return ShoppingFact(attribute, value)
        }

        /** Как показать запись памяти человеку: служебную строку старых версий — по-русски. */
        fun readable(content: String): String = parse(content)?.label ?: content
    }
}
