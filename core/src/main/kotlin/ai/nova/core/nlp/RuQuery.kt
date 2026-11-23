package ai.nova.core.nlp

/**
 * Запрос в карты и видео: «вокзала» → «вокзал», «рыбалку» → «рыбалка», плюс город из настроек.
 *
 * Полноценной морфологии в приложении нет, поэтому правила намеренно осторожные: меняется
 * только односложный запрос (одно слово — одна основа), а многословные фразы вроде
 * «рецепт пиццы» или «красной площади» остаются как есть: «рецепт пицца» хуже оригинала,
 * а поисковики сами справляются с падежами зависимых слов.
 */
object RuQuery {

    /** Слова, которые не склоняются: «до Сочи», «в Тбилиси». */
    private val INDECLINABLE = setOf(
        "сочи", "тбилиси", "сухуми", "батуми", "дели", "баку", "токио", "осло", "бордо",
        "хельсинки", "таллин", "таллинн", "сан-франциско", "капри", "суздаль",
    )

    /**
     * Словарная форма запроса: «до вокзала» → «вокзал», «про рыбалку» → «рыбалка».
     * Предлоги уже убраны разбором фразы; здесь только окончание.
     */
    fun toSearchForm(text: String): String {
        val cleaned = text.trim().trim(',', '.', '!', '?', ';', ':').trim()
            .replace(Regex("""\s{2,}"""), " ")
        if (cleaned.isEmpty()) return cleaned
        val words = cleaned.split(' ')
        if (words.size > 1) return cleaned
        val word = words.single()
        val lower = word.lowercase().replace('ё', 'е')
        if (lower in INDECLINABLE) return word
        val restored = nominative(lower) ?: return word
        // Регистр сохраняем: «Москву» → «Москва», «казани» → «казань».
        return if (word.first().isUpperCase()) restored.replaceFirstChar { it.uppercase() } else restored
    }

    private fun nominative(w: String): String? {
        // Короткие слова («дом», «кино», «меню», «Сочи») не трогаем: угадывать нечего.
        if (w.length < 5) return null
        return when {
            // «станции» → «станция»
            w.endsWith("ии") -> w.dropLast(2) + "ия"
            // «аптеки» → «аптека», «Одессы»? нет: там «ы»
            w.endsWith("ки") || w.endsWith("ги") || w.endsWith("хи") -> w.dropLast(1) + "а"
            // «рыбалку» → «рыбалка», «Москву» → «Москва»
            w.endsWith("у") -> w.dropLast(1) + "а"
            w.endsWith("ю") -> w.dropLast(1) + "я"
            // «школы» → «школа», «Москвы» → «Москва»
            w.endsWith("ы") -> w.dropLast(1) + "а"
            // «вокзала» → «вокзал», «работы»? уже выше. «до мамы» → «мама» (см. «ы»)
            w.endsWith("а") -> w.dropLast(1)
            // «Казани» → «Казань», «площади» → «площадь»
            w.endsWith("и") -> w.dropLast(1) + "ь"
            else -> null
        }
    }

    /**
     * Место с городом из настроек: «вокзал» + «Казань» → «вокзал, Казань».
     * Если город в запросе уже назван («Казань, Баумана 5»), второй раз не добавляем.
     */
    fun withCity(place: String, city: String?): String {
        val p = place.trim().trim(',', '.', ' ').trim()
        val c = city?.trim()?.trim(',', '.', ' ')?.trim().orEmpty()
        if (p.isEmpty()) return c
        if (c.isEmpty()) return p
        return if (mentionsCity(p, c)) p else "$p, $c"
    }

    private fun mentionsCity(place: String, city: String): Boolean {
        val p = RuTokenizer.normalize(place)
        val c = RuTokenizer.normalize(city).trim()
        if (c.length < 4) return false
        if (p.contains(c)) return true
        // «Казани, Баумана 5» — город узнаём по основе первого слова: «казан».
        val base = c.trimEnd('ь', 'а', 'я', 'и', 'ы', 'й', 'о', 'е', 'у', 'ю')
        if (base.length < 4) return false
        return p.split(Regex("""[^а-я\-]+""")).any { it.startsWith(base) }
    }
}
