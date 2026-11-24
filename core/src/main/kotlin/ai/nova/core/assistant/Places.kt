package ai.nova.core.assistant

/**
 * Место, найденное вокруг текущего места телефона.
 *
 * Нужны координаты: карты строят маршрут «отсюда туда» только по точке. Пока Нова передавала
 * приложению карт слово («аптека»), оно открывало поиск и показывало список — маршрута не было.
 */
data class PlaceCandidate(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    /** Сколько метров по прямой от текущего места; null — место телефона неизвестно. */
    val meters: Int? = null,
) {
    /** Название, а если его нет — адрес: «Аптека Вита», «улица Ленина, 5». */
    val title: String get() = name.trim().ifEmpty { address.trim() }
}

/**
 * Типы мест, которые человек называет словом: «поесть», «аптека», «магазин одежды».
 * [search] — что искать на карте, если человек не уточнил ничего другого.
 */
enum class PlaceKind(val search: String, val question: String?) {
    FOOD("кафе", "Посидеть в кафе, поесть в ресторане или быстро перекусить? Можно назвать кухню: пицца, суши, бургеры, кофе."),
    COFFEE("кофейня", null),
    PHARMACY("аптека", null),
    CLOTHES("магазин одежды", null),
    MALL("торговый центр", null),
    GROCERY("супермаркет", null),
    FUEL("заправка", null),
    BANK("банк", null),
    ATM("банкомат", null),
    CINEMA("кинотеатр", null),
    HOTEL("гостиница", null),
    PARKING("парковка", null),
    DENTIST("стоматология", null),
    MARKET("рынок", null),
}

/**
 * Слова, которыми называют места, и уточнение «чего именно». Всё офлайн и предсказуемо:
 * одинаковые фразы дают одинаковый поиск.
 */
object Places {

    /** Основы слов → тип места. Порядок важен: «торговый центр» раньше «магазина». */
    private val KINDS: List<Pair<PlaceKind, List<String>>> = listOf(
        PlaceKind.FOOD to listOf(
            "поесть", "пообедать", "перекусить", "покушать", "подкрепиться", "перекус", "голоден", "голодна",
            "кафе", "ресторан", "столов", "фастфуд", "фаст-фуд", "шаурм", "бургер", "пиццер", "суши", "ролл",
            "поедим", "поужинать", "позавтракать", "обед", "ужин",
        ),
        PlaceKind.MALL to listOf("торгов", "тц", "молл", "mall"),
        PlaceKind.CLOTHES to listOf("одежд", "бутик", "шопинг", "обув", "шмот"),
        PlaceKind.COFFEE to listOf("кофе", "кофейн"),
        PlaceKind.PHARMACY to listOf("аптек", "лекарств", "таблетк"),
        PlaceKind.GROCERY to listOf("супермаркет", "продукт", "гипермаркет", "магазин"),
        PlaceKind.FUEL to listOf("заправк", "бензин", "азс", "азк"),
        PlaceKind.ATM to listOf("банкомат", "снять деньги", "наличн"),
        PlaceKind.BANK to listOf("банк", "отделение банка"),
        PlaceKind.CINEMA to listOf("кинотеатр", "кино"),
        PlaceKind.HOTEL to listOf("гостиниц", "отел", "отель", "переночевать"),
        PlaceKind.PARKING to listOf("парковк", "припарковаться", "стоянк"),
        PlaceKind.DENTIST to listOf("стоматолог", "зубн"),
        PlaceKind.MARKET to listOf("рынок", "базар"),
    )

    /** Уточнения, которые человек говорит в ответ на вопрос про еду. */
    private val CUISINE: List<Pair<List<String>, String>> = listOf(
        listOf("фастфуд", "фаст-фуд", "быстро", "перекусить", "на бегу", "быстренько", "шаурм", "вредн") to "фастфуд",
        listOf("посидеть", "спокойно", "кафе", "ресторан", "обед", "пообедать", "ужин", "поужинать", "нормальн") to "кафе",
        listOf("пицц") to "пиццерия",
        listOf("суши", "ролл", "япон") to "суши-бар",
        listOf("бургер", "макдоналд", "вкусно и точк") to "бургерная",
        listOf("кофе", "чай", "десерт") to "кофейня",
        listOf("шаурм", "донер", "кебаб") to "шаурма",
        listOf("вегетариан", "веган", "постн") to "вегетарианское кафе",
        listOf("грузин") to "грузинский ресторан",
        listOf("итальян") to "итальянский ресторан",
        listOf("китай") to "китайский ресторан",
        listOf("узбек", "плов") to "узбекский ресторан",
        listOf("русск", "домашн") to "русский ресторан",
        listOf("азиат") to "азиатский ресторан",
        listOf("армян", "кавказ") to "кавказский ресторан",
        listOf("европей") to "европейский ресторан",
        listOf("любое", "любой", "неважно", "все равно", "без разницы", "как хочешь", "на твой вкус", "что-нибудь") to "кафе",
    )

    /** «Ещё», «другое», «не то» — показать следующее ближайшее место. */
    private val NEXT = rx("""^(?:а\s+|ну\s+|давай\s+|покажи\s+|найди\s+|поищи\s+)*(?:ещ[её]|друг(?:ое|ой|ую|ого|ая)|следующ(?:ее|ий|ую|ая)|не\s+то|не\s+подходит|не\s+устраивает|не\s+нравится|не\s+хочу|другое\s+место|другой\s+вариант|вариант\s+дальше)(?:\s+(?:вариант|место|пожалуйста|давай))?$""")

    /** Фразы, после которых про еду ещё ничего не понятно. */
    private val VAGUE_FOOD = listOf(
        "поесть", "перекусить", "покушать", "подкрепиться", "перекус", "пообедать", "голоден", "голодна", "еда", "еду",
    )

    /** Ответ «нет, спасибо» — отказ от маршрута, а не «ещё». */
    private val NO = rx("""^(?:нет(?:,?\s+спасибо)?|не\s+надо|не\s+нужно|отмена|отмени|не\s+строй(?:те)?|хватит|не\s+по\s+пути|спасибо,?\s+не\s+надо)$""")

    private val HOUSE = rx("""(?:^|[\s,.])(?:д\.?\s*)?\d{1,4}\s*[а-я]?(?:[\s,.]|$)""")

    /** Что-то меряемое — это не номер дома: «5 минут», «500 рублей», «3 км». */
    private val NOT_ADDRESS = rx("""\d+\s*(?:мин|час|км|метр|руб|₽|раз|процент|%|год|дн)""")

    private fun rx(p: String) = ai.nova.core.nlp.Rx.of(p)

    private fun norm(text: String): String =
        text.lowercase().replace('ё', 'е').replace(rx("""[^a-zа-я0-9\s\-]"""), " ").replace(rx("""\s+"""), " ").trim()

    /** Тип места по фразе; null — это название, адрес или что-то другое. */
    fun kind(text: String): PlaceKind? {
        val t = " " + norm(text) + " "
        return KINDS.firstOrNull { (_, words) -> words.any { w -> t.contains(" $w") } }?.first
    }

    /** «Поесть» без уточнения: сначала спросим, что именно нужно. */
    fun vague(text: String): Boolean = norm(text).let { t ->
        t.isEmpty() || VAGUE_FOOD.any { t == it || t == "хочу $it" || t == "можно $it" || t == "хочется $it" }
    }

    /** Ответ на вопрос про еду → что искать. null — это не ответ (человек говорит о другом). */
    fun refineFood(answer: String): String? {
        val t = norm(answer)
        if (t.isEmpty()) return null
        CUISINE.firstOrNull { (words, _) -> words.any { t.contains(it) } }?.let { return it.second }
        // «В ресторане», «в кафе», «в пиццерии» — тоже ответ.
        return foodByWord(t)
    }

    private fun foodByWord(t: String): String? = when {
        t.contains("посидеть") || t.contains("кафе") -> "кафе"
        t.contains("ресторан") -> "ресторан"
        t.contains("фаст") || t.contains("быстр") -> "фастфуд"
        else -> null
    }

    /** Следующее место: «ещё», «другое», «не то». */
    fun isNext(text: String): Boolean = NEXT.containsMatchIn(norm(text))

    fun isRefusal(text: String): Boolean = NO.containsMatchIn(norm(text))

    /**
     * Похоже на адрес с номером дома: «Большевистская 36», «ул. Ленина, 5», «дом 12».
     * Такие фразы строим сразу, без уточнений: человек уже сказал, куда ехать.
     */
    fun looksLikeAddress(text: String): Boolean {
        val t = norm(text)
        if (!HOUSE.containsMatchIn(t)) return false
        if (NOT_ADDRESS.containsMatchIn(t)) return false
        return t.any { it.isLetter() }
    }

    /** Слова-предлоги и уточнения: в названии места они лишние. */
    private val FILLER = setOf(
        "до", "к", "в", "во", "на", "у", "около", "возле", "рядом", "ближайший", "ближайшая",
        "ближайшее", "ближайшую", "поблизости", "недалеко", "мне", "меня", "пожалуйста",
    )

    /** Слова-типы: они описывают, что это за место, а не как оно называется. */
    private val GENERIC = listOf(
        "торгов", "центр", "тц", "трц", "молл", "mall", "магазин", "аптек", "лекарств", "кафе",
        "ресторан", "кофейн", "супермаркет", "гипермаркет", "продукт", "заправк", "бензин", "азс",
        "азк", "банкомат", "банк", "кинотеатр", "кино", "гостиниц", "отел", "отель", "парковк",
        "стоянк", "стоматолог", "зубн", "рынок", "базар", "одежд", "бутик", "обув", "шопинг", "шмот",
    )

    /**
     * Название места без слов-типов: «до торгового центра Галерея» → «галерея».
     * null — в названии одни типы («аптека», «ближайший торговый центр»), искать нечего.
     */
    fun name(text: String): String? {
        val words = norm(text).split(' ').filter { it.isNotBlank() }
        var i = 0
        while (i < words.size && words[i] in FILLER) i++
        while (i < words.size && GENERIC.any { k -> words[i].startsWith(k) && words[i].length - k.length <= 3 }) i++
        // Остаток с предлога сразу — не название, а описание: «кафе в парке» это не «парке».
        if (i == 0 || i >= words.size || words[i] in FILLER) return null
        return words.drop(i).joinToString(" ")
    }

    /**
     * Как искать место в справочнике. Справочник плохо понимает падежи: «до торгового центра галерея»
     * не находит ничего, а «галерея Новосибирск» — сразу. Поэтому сначала пробуем название с городом,
     * а потом — то, как сказали.
     */
    fun searchQueries(text: String, city: String?): List<String> {
        val spoken = text.trim()
        val place = name(spoken)
        val town = city?.trim().orEmpty()
        val first = when {
            place == null -> null
            town.isEmpty() || place.contains(town, ignoreCase = true) -> place
            else -> "$place $town"
        }
        return listOfNotNull(first, spoken).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Расстояние словами: «350 метров отсюда», «2,4 км отсюда»; пусто — расстояния не знаем. */
    fun distance(meters: Int?): String = when {
        meters == null || meters <= 0 -> ""
        meters < 1000 -> ai.nova.core.assistant.RuFormat.count(meters, "метр", "метра", "метров") + " отсюда"
        else -> "%.1f".format(java.util.Locale.US, meters / 1000.0).replace('.', ',') + " км отсюда"
    }
}
