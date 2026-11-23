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
     * Частые места: основа → словарная форма. Без словаря «до рынка» превращалось в «рынк»,
     * а «до музея» оставалось «музея» — карты на такое отвечали пустотой.
     */
    private val PLACES: List<Pair<String, String>> = listOf(
        "аэропорт" to "аэропорт", "автовокзал" to "автовокзал", "автосервис" to "автосервис",
        "аптек" to "аптека", "банкомат" to "банкомат", "банк" to "банк", "барбершоп" to "барбершоп",
        "бассейн" to "бассейн", "библиотек" to "библиотека", "больниц" to "больница",
        "вокзал" to "вокзал", "гостиниц" to "гостиница", "дач" to "дача", "дом" to "дом",
        "заправк" to "заправка", "зоопарк" to "зоопарк", "институт" to "институт", "кафе" to "кафе",
        "кинотеатр" to "кинотеатр", "кино" to "кино", "магазин" to "магазин", "метро" to "метро", "мфц" to "мфц",
        "музе" to "музей", "отел" to "отель", "офис" to "офис", "парикмахерск" to "парикмахерская",
        "парковк" to "парковка", "парк" to "парк", "пляж" to "пляж", "площад" to "площадь",
        "поликлиник" to "поликлиника", "полици" to "полиция", "почт" to "почта",
        "работ" to "работа", "ресторан" to "ресторан", "рынк" to "рынок", "спортзал" to "спортзал",
        "стадион" to "стадион", "станции" to "станция", "станци" to "станция", "столов" to "столовая",
        "супермаркет" to "супермаркет", "театр" to "театр", "университет" to "университет",
        "фитнес" to "фитнес", "школ" to "школа",
    ).sortedByDescending { it.first.length }

    /** Падежные окончания, которые можно отрезать от основы из [PLACES]. */
    private val PLACE_ENDINGS = setOf(
        "", "а", "я", "у", "ю", "е", "и", "ы", "о", "й", "ая", "ую", "ой", "ей", "ом", "ем",
        "ов", "ев", "ам", "ям", "ами", "ями", "ах", "ях", "ь", "ью", "ии", "ию", "ией",
    )

    /** Уточнения перед местом: «ближайшая аптека» ищем как «аптека». */
    private val PLACE_MODIFIERS = listOf(
        "ближайш", "ближн", "недалек", "неподалек", "рядом", "поблизост", "хорош", "лучш",
        "недорог", "дешев", "вкусн", "популярн", "известн", "круглосуточн", "открыт", "любой",
        "какой-нибудь", "какой-то", "нормальн", "приличн", "свободн",
    )

    /** Словарная форма частого места («рынка» → «рынок»); null — слова нет в словаре. */
    fun knownPlace(word: String): String? {
        val w = word.lowercase().replace('ё', 'е').trim()
        if (w.isEmpty()) return null
        for ((stem, canonical) in PLACES) {
            if (!w.startsWith(stem)) continue
            val rest = w.removePrefix(stem)
            if (rest in PLACE_ENDINGS) return canonical
        }
        return null
    }

    /** Это слово — тип места («аптека», «музей», «поесть»)? Нужно, чтобы понять «ближайшая аптека» без команды. */
    fun isPlaceWord(word: String): Boolean = knownPlace(word) != null || word.lowercase().replace('ё', 'е').trim() in FOOD

    /** «Поесть», «пообедать» — это про кафе, а не про заметку. */
    private val FOOD = setOf("поесть", "пообедать", "перекусить", "покушать", "подкрепиться", "перекус")

    fun isFood(word: String): Boolean {
        val w = word.lowercase().replace('ё', 'е').trim()
        return w in FOOD || w.startsWith("пообеда") || w.startsWith("перекус")
    }

    /** «рядом», «поблизости», «недалеко» — уточнение, а не место. */
    fun isPlaceModifier(word: String): Boolean = isModifier(word)

    private fun isModifier(word: String): Boolean {
        val w = word.lowercase().replace('ё', 'е')
        return PLACE_MODIFIERS.any { w.startsWith(it) }
    }

    /**
     * Место для карт: убирает уточнения («ближайшая аптека» → «аптека») и ставит словарную форму.
     * Многословные названия («Торговый центр Мега», «Баумана 5») остаются как есть.
     */
    fun placeForm(text: String): String {
        val cleaned = clean(text).replace(Regex("""(?i)^(?:до|к|в|во|на)\s+"""), "")
        if (cleaned.isEmpty()) return cleaned
        val words = cleaned.split(' ').filter { it.isNotBlank() }
        val kept = words.filterNot { isModifier(it) }
        val base = (kept.ifEmpty { words }).joinToString(" ")
        // «ближайшая аптека» → «аптека»; заодно «где поесть» → «кафе».
        if (base.split(' ').size == 1 && isFood(base)) return "кафе"
        return toSearchForm(base)
    }

    /** Одно и то же место? «дом» и «дома», «работа» и «на работе» — совпадают. */
    fun samePlace(a: String, b: String): Boolean {
        val x = placeForm(a).lowercase()
        val y = placeForm(b).lowercase()
        return x.isNotEmpty() && x == y
    }

    private fun clean(text: String): String = text.trim().trim(',', '.', '!', '?', ';', ':').trim()
        .replace(Regex("""\s{2,}"""), " ")

    /**
     * Словарная форма запроса: «до вокзала» → «вокзал», «про рыбалку» → «рыбалка».
     * Предлоги уже убраны разбором фразы; здесь только окончание.
     */
    fun toSearchForm(text: String): String {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) return cleaned
        val words = cleaned.split(' ')
        if (words.size > 1) return cleaned
        val word = words.single()
        val lower = word.lowercase().replace('ё', 'е')
        if (lower in INDECLINABLE) return word
        knownPlace(lower)?.let { return restoreCase(word, it) }
        val restored = nominative(lower) ?: return word
        // Регистр сохраняем: «Москву» → «Москва», «казани» → «казань».
        return restoreCase(word, restored)
    }

    private fun restoreCase(source: String, restored: String): String =
        if (source.first().isUpperCase()) restored.replaceFirstChar { it.uppercase() } else restored

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
            // «музея» → «музей», «санатория» → «санаторий»; «тётя» уже словарная форма — не трогаем
            w.endsWith("я") && w.length > 4 -> w.dropLast(1).let { if (it.last() in "еиоаы") it + "й" else null }
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
