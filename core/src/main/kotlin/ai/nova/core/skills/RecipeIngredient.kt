package ai.nova.core.skills

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Ингредиент с количеством и единицей: «рис — 500 г», «соль, перец — по вкусу».
 * [note] — уточнение («около 500 г», «для подачи», «по вкусу»).
 */
@Serializable
data class RecipeIngredient(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val note: String? = null,
    /** Количество стояло в начале строки («500 г мяса») — так и пишем, без перестановки слов. */
    val amountFirst: Boolean = false,
) {
    /** Как ингредиент звучит и попадает в список покупок. */
    val text: String
        get() {
            val head = name.trim().replaceFirstChar { it.lowercase() }
            val q = quantity
            val base = when {
                q != null && !unit.isNullOrBlank() && amountFirst -> "${amount(q)} ${unitPlural(unit!!, q)} $head"
                q != null && !unit.isNullOrBlank() -> "$head — ${amount(q)} ${unitPlural(unit!!, q)}"
                q != null && amountFirst -> "${amount(q)} $head"
                q != null -> "$head — ${amount(q)}"
                else -> head
            }
            return when {
                q != null && !note.isNullOrBlank() -> "$base ($note)"
                q == null && !note.isNullOrBlank() -> "$base — $note"
                else -> base
            }
        }

    /** Пересчёт на другое число порций: количества умножаются, «по вкусу» остаётся как есть. */
    fun scaled(factor: Double): RecipeIngredient =
        if (quantity == null || factor == 1.0 || factor <= 0) this else copy(quantity = quantity * factor)

    companion object {
        /** Порядок важен: «ст. ложки» должно проверяться раньше «л». */
        private val UNITS = listOf(
            "ст. ложки", "ст. ложка", "ст. л.", "ст л", "столовые ложки", "столовая ложка",
            "ч. ложки", "ч. ложка", "ч. л.", "ч л", "чайные ложки", "чайная ложка",
            "кубика", "кубик", "головки", "головка", "зубчика", "зубчик", "щепотки", "щепотка",
            "ломтика", "ломтик", "куска", "кусок", "пучка", "пучок", "стакана", "стакан",
            "кг", "мг", "мл", "литра", "литр", "г", "л", "шт.", "шт", "штуки", "штука",
        )

        /**
         * Три живых формата строки:
         * «рис — 500 г» (название, затем количество), «500 г мяса» (количество впереди),
         * «соль по вкусу» и просто «молоко» (количества нет).
         */
        fun parse(line: String): RecipeIngredient {
            val raw = line.trim().trimStart('-', '•', '*', '·', ' ').trim().trimEnd(',', ';')
            if (raw.isEmpty()) return RecipeIngredient("")
            val dash = dashIndex(raw)
            if (dash >= 0) {
                val left = raw.substring(0, dash).trim().trimEnd(',', ';', ':')
                val tail = raw.substring(dash + 1).trim().trimStart(':').trim()
                if (left.isEmpty()) return measureFirst(tail) ?: plain(tail)
                val head = measureFirst(left)
                // «500 г — мясо»: количество слева, название справа.
                if (head != null) return head.copy(name = tail.ifBlank { head.name })
                val right = tailAmount(tail)
                return RecipeIngredient(left, right?.quantity, right?.unit, right?.note)
            }
            return measureFirst(raw) ?: plain(raw)
        }

        fun parseAll(lines: List<String>): List<RecipeIngredient> =
            lines.map { parse(it) }.filter { it.name.isNotBlank() }

        private fun dashIndex(s: String): Int {
            val em = s.indexOf('—')
            if (em >= 0) return em
            val en = s.indexOf('–')
            if (en >= 0) return en
            val hyphen = Regex("""\s+-\s+""").find(s)?.range?.first ?: -1
            return hyphen
        }

        /** Количество с единицей, если строка начинается с числа; иначе null. */
        private fun measureFirst(line: String): RecipeIngredient? {
            val m = NUMBER.find(line) ?: return null
            val quantity = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val rest = m.groupValues[2].trim()
            val unit = UNITS.firstOrNull { rest.lowercase().startsWith(it) }
            val tail = (if (unit != null) rest.substring(unit.length) else rest).trim()
            val (name, note) = splitNote(tail)
            if (name.isBlank()) return null
            return RecipeIngredient(name, quantity, unit, note, amountFirst = true)
        }

        /** «соль по вкусу» → название «соль», уточнение «по вкусу». */
        private fun plain(line: String): RecipeIngredient {
            val (name, note) = splitNote(line)
            return RecipeIngredient(name, null, null, note)
        }

        /** Количество из правой части «рис — 500 г»; null — числа нет. */
        private fun tailAmount(tail: String): RecipeIngredient? {
            val m = NUMBER.find(tail) ?: return null
            val quantity = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val rest = m.groupValues[2].trim()
            val unit = UNITS.firstOrNull { rest.lowercase().startsWith(it) }
            val afterUnit = if (unit != null) rest.substring(unit.length).trim() else rest
            val note = afterUnit.trim().trim('(', ')').trim().takeIf { it.isNotBlank() }
            return RecipeIngredient("", quantity, unit, note)
        }

        private val NUMBER = Regex("""^(\d+(?:[.,]\d+)?)\b\s*(.*)$""")
        private val NOTE = Regex("""\s+(по\s+вкусу|по\s+желанию|для\s+подачи|для\s+украшения|для\s+смазки|опционально)$""", RegexOption.IGNORE_CASE)

        private fun splitNote(line: String): Pair<String, String?> {
            val note = NOTE.find(line) ?: return line.trim() to null
            return line.substring(0, note.range.first).trim() to note.value.trim()
        }

        /** 500.0 → «500», 0.5 → «0,5» — без лишних нулей. */
        fun amount(value: Double): String {
            val rounded = (value * 10).roundToLong() / 10.0
            return if (abs(rounded - rounded.roundToLong()) < 0.001) rounded.roundToLong().toString()
            else String.format(java.util.Locale.US, "%.1f", rounded).replace('.', ',')
        }

        /** «2 головка» не бывает: для счётных единиц подставляем форму по числу. */
        fun unitPlural(unit: String, value: Double): String {
            val forms = FORMS[unit.lowercase()] ?: return unit
            val n = abs(value.roundToLong())
            val last2 = n % 100
            val last = n % 10
            return when {
                last2 in 11..14 -> forms[2]
                last == 1L -> forms[0]
                last in 2..4 -> forms[1]
                else -> forms[2]
            }
        }

        /** Единица → формы «1 / 2 / 5». Вес и объём не меняются. */
        private val FORMS = mapOf(
            "головка" to listOf("головка", "головки", "головок"),
            "головки" to listOf("головка", "головки", "головок"),
            "зубчик" to listOf("зубчик", "зубчика", "зубчиков"),
            "зубчика" to listOf("зубчик", "зубчика", "зубчиков"),
            "щепотка" to listOf("щепотка", "щепотки", "щепоток"),
            "щепотки" to listOf("щепотка", "щепотки", "щепоток"),
            "ломтик" to listOf("ломтик", "ломтика", "ломтиков"),
            "ломтика" to listOf("ломтик", "ломтика", "ломтиков"),
            "кусок" to listOf("кусок", "куска", "кусков"),
            "куска" to listOf("кусок", "куска", "кусков"),
            "пучок" to listOf("пучок", "пучка", "пучков"),
            "пучка" to listOf("пучок", "пучка", "пучков"),
            "стакан" to listOf("стакан", "стакана", "стаканов"),
            "стакана" to listOf("стакан", "стакана", "стаканов"),
            "штука" to listOf("шт.", "шт.", "шт."),
            "штуки" to listOf("шт.", "шт.", "шт."),
            "шт" to listOf("шт.", "шт.", "шт."),
            "шт." to listOf("шт.", "шт.", "шт."),
            "кубик" to listOf("кубик", "кубика", "кубиков"),
            "кубика" to listOf("кубик", "кубика", "кубиков"),
        )
    }
}
