package ai.nova.core.nlp

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Простые вычисления голосом: «сколько будет 250 умножить на 4», «15 процентов от 2000», «(1200+800)/2».
 */
object Calculator {
    private val wordOps = listOf(
        Regex("""\bумножить на\b|\bумноженное на\b|\bпомножить на\b""") to "*",
        Regex("""\bразделить на\b|\bделить на\b|\bподелить на\b|\bразделенное на\b""") to "/",
        Regex("""\bплюс\b|\bприбавить\b""") to "+",
        Regex("""\bминус\b|\bотнять\b|\bвычесть\b""") to "-",
    ).map { (r, op) -> Regex(Rx.unicode(r.pattern)) to op }

    private const val NUM = """(\d+(?:[.,]\d+)?)"""
    private const val PCT = """(?:%|процент\w*)"""

    /** «20% от 1000», «15 процентов от 2000». */
    private val percentOf = Rx.of("""^$NUM\s*$PCT\s+от\s+$NUM$""")

    /** «минус 20% от тысячи» — это тысяча без двадцати процентов, то есть 800. */
    private val minusPercentOf = Rx.of("""^(?:минус|отнять|вычесть)\s+$NUM\s*$PCT\s+от\s+$NUM$""")
    private val plusPercentOf = Rx.of("""^(?:плюс|прибавить)\s+$NUM\s*$PCT\s+от\s+$NUM$""")

    /** «2000 минус 20%», «2000 плюс 20 процентов». */
    private val baseMinusPercent = Rx.of("""^$NUM\s*(?:минус|отнять|вычесть)\s+$NUM\s*$PCT$""")
    private val basePlusPercent = Rx.of("""^$NUM\s*(?:плюс|прибавить)\s+$NUM\s*$PCT$""")
    private val baseSignPercent = Rx.of("""^$NUM\s*([-+])\s*$NUM\s*$PCT$""")

    /** «20 от тысячи» без знака % — так тоже говорят, но только когда явно просят посчитать. */
    private val bareOf = Rx.of("""^$NUM\s+от\s+$NUM$""")

    /** Вычисляет выражение из фразы; null, если это не арифметика. */
    fun evaluate(text: String): Double? {
        val normalized = RuTokenizer.normalize(text)
        val explicitCalc = LEAD.containsMatchIn(normalized.trim())
        var t = normalized
            .replace(LEAD, "")
            .replace('−', '-').replace("×", "*").replace("÷", "/").trim().trimEnd('?', '.', '=')
        // «x»/«х» и «на» считаем умножением только между числами: «3 х 4», «250 на 4» (последнее — только после «сколько будет»)
        t = t.replace(timesSign, " * ")
        if (explicitCalc) t = t.replace(timesWord, " * ")
        // Числительные словами → цифры. Важно до процентов: иначе «20% от тысячи» не распознавалось вовсе.
        t = numerals(t)
        // Проценты считаем до знаков действий: «минус» ниже станет дефисом и смысл потеряется.
        percent(t, explicitCalc)?.let { return it }

        var expr = t
        for ((r, op) in wordOps) expr = r.replace(expr, " $op ")
        expr = expr.replace(" ", "").replace(',', '.')
        if (!allowedChars.matches(expr)) return null
        if (!anyOperator.containsMatchIn(expr.drop(1))) return null
        return runCatching { Parser(expr).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    private val LEAD = Regex("""^(сколько будет|посчитай|вычисли|подсчитай|реши|сколько)\s*""")

    /** «x»/«х» и «на» между числами — умножение: «3 х 4», «250 на 4». */
    private val timesSign = Regex("""(?<=\d)\s*[хx]\s*(?=\d)""")
    private val timesWord = Regex("""(?<=\d)\s+на\s+(?=\d)""")
    private val allowedChars = Regex("""^[\d.+\-*/()]+$""")
    private val anyOperator = Regex("""[+\-*/]""")

    /** Числительные словами → цифры: «двадцать процентов от тысячи» → «20 процентов от 1000». */
    private fun numerals(t: String): String {
        val tokens = RuTokenizer.tokenize(t)
        if (tokens.none { it.isNumber }) return t
        val sb = StringBuilder()
        var cursor = 0
        for (tok in tokens) {
            sb.append(t, cursor, tok.start)
            val n = tok.number
            sb.append(if (n != null) formatNum(n) else tok.text)
            cursor = tok.end
        }
        sb.append(t.substring(cursor))
        return sb.toString()
    }

    /** Проценты и скидки. Возвращает null, если это не проценты. */
    private fun percent(t: String, explicitCalc: Boolean): Double? {
        val s = t.trim()
        percentOf.find(s)?.let { m -> return share(num(m.groupValues[1]), num(m.groupValues[2])) }
        minusPercentOf.find(s)?.let { m ->
            val base = num(m.groupValues[2])
            return base - share(num(m.groupValues[1]), base)
        }
        plusPercentOf.find(s)?.let { m ->
            val base = num(m.groupValues[2])
            return base + share(num(m.groupValues[1]), base)
        }
        baseMinusPercent.find(s)?.let { m ->
            val base = num(m.groupValues[1])
            return base - share(num(m.groupValues[2]), base)
        }
        basePlusPercent.find(s)?.let { m ->
            val base = num(m.groupValues[1])
            return base + share(num(m.groupValues[2]), base)
        }
        baseSignPercent.find(s)?.let { m ->
            val base = num(m.groupValues[1])
            val part = share(num(m.groupValues[3]), base)
            return if (m.groupValues[2] == "-") base - part else base + part
        }
        if (explicitCalc) bareOf.find(s)?.let { m -> return share(num(m.groupValues[1]), num(m.groupValues[2])) }
        return null
    }

    /** Сколько составляет [p] процентов от [base]. */
    private fun share(p: Double, base: Double): Double = p * base / 100

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    fun format(v: Double): String {
        val bd = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString().replace('.', ',')
        return s
    }

    private fun formatNum(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        private fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); require(s[i++] == ')'); return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
