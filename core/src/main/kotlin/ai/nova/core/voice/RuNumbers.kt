package ai.nova.core.voice

import ai.nova.core.assistant.RuFormat

/**
 * Русские числительные словами: количественные (с родом), порядковые (для дат и годов), дроби.
 * Нужны, чтобы движок речи не читал «2 кг» как «два килограмма» в мужском роде всегда,
 * а «в 2026 году» — как «две тысячи двадцать шесть году».
 */
object RuNumbers {

    enum class Gender { MASCULINE, FEMININE, NEUTER }

    /** Согласование числительного с существительным: «один рубль», «одна минута», «два килограмма», «две недели». */
    fun cardinal(n: Long, gender: Gender = Gender.MASCULINE): String {
        if (n == 0L) return "ноль"
        if (n < 0) return "минус " + cardinal(-n, gender)
        if (n >= 1_000_000_000_000L) return n.toString() // таких сумм в ответах не бывает
        val sb = StringBuilder()
        var rest = n
        fun add(value: Long, forms: List<String>, g: Gender) {
            if (rest < value) return
            val count = rest / value
            rest %= value
            if (sb.isNotEmpty()) sb.append(' ')
            // «одна тысяча», «две тысячи», «пять тысяч» — тысяча считается в женском роде.
            sb.append(cardinal(count, g)).append(' ').append(RuFormat.plural(count, forms[0], forms[1], forms[2]))
        }
        add(1_000_000_000L, listOf("миллиард", "миллиарда", "миллиардов"), Gender.MASCULINE)
        add(1_000_000L, listOf("миллион", "миллиона", "миллионов"), Gender.MASCULINE)
        add(1000L, listOf("тысяча", "тысячи", "тысяч"), Gender.FEMININE)
        if (rest > 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(below1000(rest, gender))
        }
        return sb.toString()
    }

    private fun below1000(n: Long, g: Gender): String {
        val out = ArrayList<String>(4)
        var rest = n
        val hundreds = rest / 100
        rest %= 100
        if (hundreds > 0) out += HUNDREDS[hundreds.toInt()]
        if (rest >= 20) {
            out += TENS[(rest / 10).toInt()]
            rest %= 10
        }
        if (rest > 0) out += unit(rest.toInt(), g)
        return out.joinToString(" ")
    }

    private fun unit(n: Int, g: Gender): String = when (n) {
        1 -> when (g) { Gender.MASCULINE -> "один"; Gender.FEMININE -> "одна"; Gender.NEUTER -> "одно" }
        2 -> if (g == Gender.FEMININE) "две" else "два"
        else -> SMALL[n]
    }

    private val SMALL = listOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )

    private val TENS = listOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )

    private val HUNDREDS = listOf(
        "", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот",
    )

    /**
     * Порядковое числительное: «в две тысячи двадцать шестом году», «с тысяча девятьсот девяносто первого».
     * Склонение — только то, что встречается в ответах: предложный («в … году»), родительный («… года»), именительный.
     */
    fun ordinal(n: Long, case: Case = Case.PREPOSITIONAL, gender: Gender = Gender.MASCULINE): String {
        if (n < 0) return "минус " + ordinal(-n, case, gender)
        if (n == 0L) return "нулевом"
        val rest = n % 1000
        val thousands = n / 1000
        // Круглые тысячи — единственное порядковое слово: «в двухтысячном году», «в тысяча девятьсотом».
        if (rest == 0L) {
            val word = when {
                thousands == 1L -> "тысячном"
                thousands % 10 == 2L && thousands % 100 != 12L -> "двухтысячном"
                thousands < 20 -> cardinal(thousands, Gender.MASCULINE)
                    .split(' ').last() + ordinalStem(thousands.toInt() % 10) + caseEnding(case, gender)
                else -> ordinal(thousands * 1000, case, gender)
            }
            return word
        }
        val sb = StringBuilder()
        if (thousands == 1L) sb.append("тысяча ")
        else if (thousands > 0) {
            sb.append(cardinal(thousands, Gender.FEMININE)).append(' ')
                .append(RuFormat.plural(thousands, "тысяча", "тысячи", "тысяч")).append(' ')
        }
        // Последнее слово трёхзначной части становится порядковым, остальные остаются количественными.
        if (rest % 100 in 11L..19L) {
            if (rest / 100 > 0) sb.append(HUNDREDS[(rest / 100).toInt()]).append(' ')
            sb.append(teenOrdinal((rest % 100).toInt(), case, gender))
        } else if (rest % 100 == 0L) {
            sb.append(hundredOrdinal(rest.toInt() / 100, case, gender))
        } else if (rest % 10 == 0L) {
            if (rest / 100 > 0) sb.append(HUNDREDS[(rest / 100).toInt()]).append(' ')
            sb.append(tenOrdinal((rest % 100).toInt() / 10, case, gender))
        } else {
            val head = rest - (rest % 10)
            if (head > 0) sb.append(below1000(head, Gender.MASCULINE)).append(' ')
            sb.append(unitOrdinal((rest % 10).toInt(), case, gender))
        }
        return sb.toString().trim()
    }

    /**
     * Родительный падеж количественного: «около пяти километров», «более двадцати минут», «от двух до трёх дней».
     * Предлоги «около», «от», «до», «более», «менее» требуют именно этой формы.
     */
    fun cardinalGenitive(n: Long, gender: Gender = Gender.MASCULINE): String {
        if (n < 0) return "минус " + cardinalGenitive(-n, gender)
        if (n == 0L) return "нуля"
        if (n >= 1_000_000_000_000L) return n.toString()
        val sb = StringBuilder()
        var rest = n
        fun add(value: Long, forms: List<String>) {
            if (rest < value) return
            val count = rest / value
            rest %= value
            if (value <= 1) return
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(cardinalGenitive(count, Gender.FEMININE)).append(' ')
                .append(RuFormat.plural(count, forms[0], forms[1], forms[2]))
        }
        add(1_000_000_000L, listOf("миллиарда", "миллиардов", "миллиардов"))
        add(1_000_000L, listOf("миллиона", "миллионов", "миллионов"))
        if (rest >= 1000L) {
            val thousands = rest / 1000L
            rest %= 1000L
            if (sb.isNotEmpty()) sb.append(' ')
            if (thousands == 1L) sb.append("одной тысячи")
            else sb.append(cardinalGenitive(thousands, Gender.FEMININE)).append(' ')
                .append(RuFormat.plural(thousands, "тысячи", "тысяч", "тысяч"))
        }
        if (rest > 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(below1000Genitive(rest, gender))
        }
        return sb.toString()
    }

    private fun below1000Genitive(n: Long, g: Gender): String {
        val out = ArrayList<String>(4)
        var rest = n
        val hundreds = rest / 100
        rest %= 100
        if (hundreds > 0) out += HUNDREDS_GEN[hundreds.toInt()]
        if (rest >= 20) {
            out += TENS_GEN[(rest / 10).toInt()]
            rest %= 10
        }
        if (rest > 0) out += unitGenitive(rest.toInt(), g)
        return out.joinToString(" ")
    }

    private fun unitGenitive(n: Int, g: Gender): String = when (n) {
        1 -> if (g == Gender.FEMININE) "одной" else "одного"
        2 -> "двух"
        3 -> "трёх"
        4 -> "четырёх"
        else -> SMALL_GEN[n]
    }

    private val SMALL_GEN = listOf(
        "нуля", "одного", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти",
        "десяти", "одиннадцати", "двенадцати", "тринадцати", "четырнадцати", "пятнадцати",
        "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати",
    )

    private val TENS_GEN = listOf(
        "", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти", "восьмидесяти", "девяноста",
    )

    private val HUNDREDS_GEN = listOf(
        "", "ста", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот",
    )

    enum class Case { NOMINATIVE, GENITIVE, PREPOSITIONAL }

    private fun caseEnding(case: Case, gender: Gender): String = when (case) {
        Case.NOMINATIVE -> if (gender == Gender.FEMININE) "ая" else "ый"
        Case.GENITIVE -> if (gender == Gender.FEMININE) "ой" else "ого"
        Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "ой" else "ом"
    }

    /** Основа порядкового: 5 → «пят», 3 → «трет». */
    private fun ordinalStem(n: Int): String = listOf(
        "нулев", "перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят",
    ).getOrElse(n) { "" }

    private fun unitOrdinal(n: Int, case: Case, gender: Gender): String = when (n) {
        3 -> when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "третья" else "третий"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "третьей" else "третьего"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "третьей" else "третьем"
        }
        1 -> when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "первая" else "первый"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "первой" else "первого"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "первой" else "первом"
        }
        2 -> when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "вторая" else "второй"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "второй" else "второго"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "второй" else "втором"
        }
        else -> ordinalStem(n) + caseEnding(case, gender)
    }

    /** Порядковые от 11 до 19: «двенадцатого июня», «девятнадцатом году» — одной основой, а не «десять второго». */
    private fun teenOrdinal(n: Int, case: Case, gender: Gender): String {
        val stem = TEEN_STEMS.getOrElse(n - 11) { "десят" }
        return stem + when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "ая" else "ый"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "ой" else "ого"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "ой" else "ом"
        }
    }

    private val TEEN_STEMS = listOf(
        "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат",
        "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат",
    )

    private fun tenOrdinal(tens: Int, case: Case, gender: Gender): String {        val stem = listOf("", "десят", "двадцат", "тридцат", "сороков", "пятидесят", "шестидесят", "семидесят", "восьмидесят", "девяност")[tens]
        return stem + when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "ая" else "ый"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "ой" else "ого"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "ой" else "ом"
        }
    }

    private fun hundredOrdinal(hundreds: Int, case: Case, gender: Gender): String {
        val stem = listOf("", "сот", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот", "восьмисот", "девятисот")[hundreds]
        return stem + when (case) {
            Case.NOMINATIVE -> if (gender == Gender.FEMININE) "ая" else "ый"
            Case.GENITIVE -> if (gender == Gender.FEMININE) "ой" else "ого"
            Case.PREPOSITIONAL -> if (gender == Gender.FEMININE) "ой" else "ом"
        }
    }

    /** Дробь: «1,5» → «одна целая и пять десятых», «0,5» → «ноль целых и пять десятых». */
    fun fraction(whole: Long, digits: String): String {
        if (digits.isBlank()) return cardinal(whole)
        val frac = digits.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return "$whole,$digits"
        val singular = when (digits.length) { 1 -> "десятая"; 2 -> "сотая"; else -> "тысячная" }
        val plural = when (digits.length) { 1 -> "десятых"; 2 -> "сотых"; else -> "тысячных" }
        // «одна десятая», «две десятых», «пять десятых».
        val fracWord = if (frac % 10 == 1L && frac % 100 != 11L) singular else plural
        val wholeWord = when {
            whole == 0L -> "ноль целых"
            whole % 10 == 1L && whole % 100 != 11L -> cardinal(whole, Gender.FEMININE) + " целая"
            else -> cardinal(whole, Gender.FEMININE) + " целых"
        }
        return "$wholeWord и ${cardinal(frac, Gender.FEMININE)} $fracWord"
    }

    /** Женский род нужен словам, которые считают штуками: минута, секунда, неделя, копейка, порция. */
    private val FEMININE_NOUNS = setOf(
        "минута", "минуты", "минут", "секунда", "секунды", "секунд", "неделя", "недели", "недель",
        "штука", "штуки", "штук", "порция", "порции", "порций", "копейка", "копейки", "копеек",
        "чашка", "чашки", "чашек", "ложка", "ложки", "ложек", "страница", "страницы", "страниц",
        "калория", "калории", "калорий", "тысяча", "тысячи", "тысяч", "половина", "половины",
    )

    fun genderOf(word: String): Gender = if (word.lowercase().trim(',', '.', '!', '?', ':', ';') in FEMININE_NOUNS) Gender.FEMININE else Gender.MASCULINE
}
