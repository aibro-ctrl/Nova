package ai.nova.core.shopping

import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.nlp.Rx

/**
 * Разбор просьбы о покупке: «хочу кеды Puma, 46 размер, до 10 тысяч».
 *
 * Всё считается локально, без AI: Нова понимает обычную речь и сама решает, чего не хватает для поиска.
 * Разовые параметры (сегодняшний размер, бюджет) читаются из фразы, а постоянные (размер обуви,
 * вид животного) — из памяти, если человек их уже называл.
 */
object ShoppingIntent {

    /**
     * Слова, по которым фраза считается просьбой о товаре. Порядок важен: первая совпавшая категория
     * задаёт уточняющие вопросы (размер спрашиваем только у обуви и одежды). Пробел в начале слова
     * отличает «шины» от «машины», поэтому спорные слова записаны с ним.
     */
    private val CATEGORY_WORDS = mapOf(
        ShoppingCategory.SHOES to listOf(
            "кед", "кроссов", "ботин", "туфл", "сандал", "сапог", "тапоч", "шлепан", "мокасин", "лофер",
            "обув", "босонож", "сланц", "угги", "дутик",
        ),
        ShoppingCategory.OUTERWEAR to listOf(
            "куртк", "пальто", "пуховик", "плащ", "ветровк", "шуба", "дубленк", "парк", "бомбер",
        ),
        ShoppingCategory.CLOTHES to listOf(
            "футболк", "рубашк", "джинс", "брюк", "шорт", "платье", "плать", "юбк", "свитер", "худи",
            "толстовк", "кофт", "пижам", "носк", "белье", "бельё", "жилет", "одежд", "костюм", "лонгслив",
            "майк", "купальник", "колготк", "перчатк", "шапк", "шарф",
        ),
        ShoppingCategory.TECH to listOf(
            "телефон", "ноутбук", "наушник", "планшет", "монитор", "клавиатур", "мышк", "часы", "смартфон",
            "телевизор", "пылесос", "камер", "колонк", "зарядк", "повербанк", "power bank", "техник",
            "чайник", "микроволнов", "блендер", "мультиварк", "стиральн", "холодильник", "кофеварк",
            "тостер", "фен", "утюг", "обогревател", "кондиционер", "проектор", "принтер", "роутер",
            "флешк", "карта памяти", "джойстик", "геймпад", "экшен-камер",
        ),
        ShoppingCategory.HOME to listOf(
            "кастрюл", "сковород", "подушк", "одеял", "полотенц", "лампа", "лампочк", "штор", "ковер",
            "ковёр", "мебел", "стол", "стул", "посуд", "бытов", "тарелк", "кружк", "стакан", "нож",
            "вилк", "ложк", "набор посуды", "бокал", "постельн", "матрас", "простын", "наволочк", "плед",
            "коврик", "зеркал", "шкаф", "полк", "вешалк", "корзин", "ваза", "светильник", "розетк",
            "удлинител", "батарейк", "инструмент", "молоток", "молотк", "дрель", "шуруповерт", "отвертк",
            "пила", "пилк", "гвозд", "шуруп", "изолент", "рулетк", "сверл", "ключ гаечн", "напильник",
            "стремянк", "мел", "скотч", "клей",
        ),
        ShoppingCategory.PET to listOf(
            "корм", "наполнитель", "поводок", "когтеточк", "клетк", "аквариум", "лежанк", "животн",
            "для кош", "для кот", "для собак", "для щенк", "для попуга", "для рыб", "для грызун",
            "кошач", "собач", "зоотовар", "лоток",
        ),
        ShoppingCategory.FOOD to listOf(
            "продукт", "молоко", "хлеб", "кофе", "чай", "сахар", "масло", "мясо", "рыб", "овощ", "фрукт",
            "крупа", "шоколад", "конфет", "печень", "сок", "вода", "сыр", "яйц", "куриц", "макарон",
            "рис", "гречк", "специ", "соус", "кетчуп", "майонез", "мороженое", "торт", "мука", "соль",
            "орех", "сухофрукт", "колбас", "йогурт", "творог", "сметан", "паштет", "консерв",
        ),
        ShoppingCategory.KIDS to listOf(
            "подгузник", "игрушк", "коляск", "детск", "конструктор", "пазл", "бутылочк", "соск",
            "развивающ", "настольн игр", "кукл", "машинк", "самокат детск", "рюкзак школьн",
        ),
        ShoppingCategory.BEAUTY to listOf(
            "крем", "шампун", "гель", "духи", "помад", "тушь", "косметик", "мыло", "дезодорант", "паста",
            "зубн", "лак", "пудр", "тональн", "бальзам", "скраб", "мицеллярн", "прокладк", "бритв",
            "парфюм", "маск", "сыворотк", "тени",
        ),
        ShoppingCategory.AUTO to listOf(
            "авто", "покрышк", " шины", "автомобильн", "аккумулятор", "антифриз", "стеклоочистител",
            "щетки стекло", "моторн", "домкрат", "автохимия", "омывател", "коврик в салон", "тормозн",
        ),
        ShoppingCategory.SPORT to listOf(
            "гантел", "штанга", "штанг", "скакалк", "эспандер", "велосипед", "тренажер", "тренажёр",
            "коврик для йог", "йог", "палатк", "спальник", "термос", "лыж", "коньк", "мяч", "ракетк",
            "боксерск", "фитнес", "спорт", "туристическ", "фонар",
        ),
        ShoppingCategory.GARDEN to listOf(
            "семена", "рассад", "удобрени", "грунт", "горшок", "горшк", "лейк", "саженц", "садов",
            "огородн", "теплиц", "лопат", "грабл", "тяпк", "секатор", "шланг",
        ),
        ShoppingCategory.BOOKS to listOf(
            "книг", "ручк", "тетрад", "альбом", "краск", "пластилин", "карандаш", "блокнот", "ежедневник",
            "канцеляр", "папк", "фломастер", "обложк", "закладк", "учебник", "раскраск",
        ),
    )

    private val KNOWN_BRANDS = listOf(
        "puma", "nike", "adidas", "reebok", "new balance", "asics", "jordan", "vans", "converse",
        "kappa", "fila", "demix", "under armour", "columbia", "the north face", "ecco", "salamander",
        "ralf ringer", "zolla", "ostin", "o'stin", "zara", "h&m", "uniqlo", "levi's", "levis",
        "xiaomi", "samsung", "apple", "huawei", "honor", "realme", "oppo", "vivo", "lg", "sony",
        "bosch", "philips", "dyson", "redmond", "polaris", "dexp", "haier", "bosch", "makita",
        "royal canin", "purina", "whiskas", "pedigree", "bosch", "мнямс", "наша марка",
    ).sortedByDescending { it.length }

    private val COLORS = listOf(
        "черн", "бел", "син", "голуб", "красн", "зелен", "зелён", "жёлт", "желт", "сер", "беж",
        "коричнев", "розов", "фиолетов", "оранж", "бордов", "хаки", "молочн",
    )

    private val ANIMALS = listOf(
        "неразлучник" to "неразлучники",
        "попуга" to "попугай",
        "кошк" to "кошка",
        "котек" to "кошка",
        "кот" to "кошка",
        "собак" to "собака",
        "щен" to "собака",
        "рыб" to "рыба",
        "хомя" to "хомяк",
        "черепах" to "черепаха",
    )

    /** Служебные слова просьбы: в поисковый запрос они не идут. */
    private val FILLER = Rx.of(
        """^(?:ну\s+)?(?:нова|эй,?\s*нова)?,?\s*""" +
            """(?:хочу|хотел(?:а)?|нужно|надо|нужен|нужна|нужны|мне|себе|пожалуйста|давай|помоги)\b|""" +
            """^(?:найди|поищи|посмотри|подбери|подбери-ка|выбери|закажи|купи)\b""",
    )

    private val PRICE = Rx.of("""(?:до|не\s+дороже|максимум|бюджет|в\s+пределах|за)\s*(\d[\d\s]*(?:[.,]\d+)?)\s*(тыс(?:яч\w*)?|к|тыща\w*|млн\w*|руб\w*|₽)?""")
    private val PRICE_FROM = Rx.of("""(?:от|начиная\s+с)\s*(\d[\d\s]*(?:[.,]\d+)?)\s*(тыс(?:яч\w*)?|к|руб\w*|₽)?""")

    private val SIZE = Rx.of("""(?:размер\w*|р-р)\s*(\d{2,3}|[xsml]{1,4})(?:[.,]5)?""")
    private val SIZE_AFTER = Rx.of("""(\d{2,3})(?:[.,]5)?\s*(?:-?й\s*)?(?:размер\w*)""")
    /** Буквенный размер одежды: «xxl», «m», «2xl» — это размер, а не бренд. */
    private val LETTER_SIZE = Rx.of("""(?:^|[^\p{L}\p{N}])((?:[2-5]?x{0,3}[sml])|(?:[2-5]xl))(?:[^\p{L}\p{N}]|$)""")

    /** Само слово «размер», когда цифры уже убраны отдельным правилом. */
    private val SIZE_WORD = Rx.of("""(?:^|\s)(?:размер\w*|р-р)(?=\s|$)""")

    /** Латинские слова, которые не бренд: служебные и размерные. */
    private val NOT_BRAND = setOf("size", "the", "and", "for", "new", "sale", "xxl", "xl")

    private val VOLUME = Rx.of("""(\d+(?:[.,]\d+)?)\s*(кг|килограмм\w*|г|гр|грамм\w*|л|литр\w*|мл|миллилитр\w*)""")
    private val HALF_KILO = Rx.of("""пол(?:овина|у|)\s*кило\w*""")

    private val MIN_RATING = Rx.of("""(?:рейтинг\w*\s*(?:от|не\s+ниже|выше)?|от)\s*(\d(?:[.,]\d)?)\s*(?:звезд|звёзд|балл\w*)?""")

    private val QUANTITY = Rx.of("""(?:(\d+)\s*(?:шт|штук\w*)|(?:пару|два|две|три|четыре|пять)\s+(?:шт|штук\w*))""")
    private val WORDS_QTY = mapOf("пару" to 2, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5)

    private val NEW_SEARCH = Rx.of("""(?:^|\s)(?:теперь|а\s+теперь|ещё|еще|заодно|а\s+заодно)\s""")

    /** Разбор новой просьбы. */
    fun parse(text: String, facts: ShoppingFacts = ShoppingFacts(), previous: ShoppingQuery? = null): ShoppingQuery {
        val source = RuTokenizer.normalize(text)
        val t = " " + source.replace(Regex("""\s+"""), " ").trim() + " "

        val category = category(t) ?: previous?.category ?: ShoppingCategory.OTHER
        val brand = brand(source) ?: if (isNewSearch(t) || previous == null) null else previous.brand
        val marketplaces = marketplaces(source) ?: if (previous != null) previous.marketplaces else emptySet()
        // Бюджет и размер из предыдущей фразы сохраняются: «а теперь посмотри Nike» — тот же поиск.
        val priceTo = priceTo(t) ?: previous?.priceTo
        val priceFrom = priceFrom(t) ?: previous?.priceFrom
        val size = size(t) ?: when {
            previous != null && isNewSearch(t) -> previous.size
            previous != null -> previous.size
            category.needsSize -> if (category == ShoppingCategory.SHOES) facts.shoeSize else facts.clothesSize
            else -> null
        }
        val color = color(t) ?: previous?.color
        val volume = volume(t) ?: previous?.volume
        val subject = animal(t) ?: previous?.subject ?: facts.petKind.takeIf { category == ShoppingCategory.PET }
        val minRating = minRating(t) ?: previous?.minRating
        val quantity = quantity(t) ?: previous?.quantity ?: 1

        return ShoppingQuery(
            text = cleanText(source),
            category = category,
            brand = brand,
            size = size,
            color = color,
            priceTo = priceTo,
            priceFrom = priceFrom,
            quantity = quantity,
            marketplaces = marketplaces,
            volume = volume,
            subject = subject,
            minRating = minRating,
        )
    }

    /**
     * Ответ на уточняющий вопрос: «46», «до 10 тысяч», «для неразлучников».
     * Возвращает null, если ответ ничего не добавляет — тогда фразу разберут обычные навыки.
     */
    fun answer(text: String, current: ShoppingQuery): ShoppingQuery? {
        val t = " " + RuTokenizer.normalize(text).replace(Regex("""\s+"""), " ").trim() + " "
        var changed = false
        var next = current
        // На вопрос «какой размер?» отвечают просто числом.
        val bare = t.trim()
        val bareSize = bare.takeIf { Regex("""^\d{2}$|^\d{2}[.,]5$""").matches(it) }?.replace(',', '.')
        if (bareSize != null && current.category.needsSize && size(t) == null) {
            next = next.copy(size = bareSize)
            changed = true
        }
        size(t)?.let { next = next.copy(size = it); changed = true }
        priceTo(t)?.let { next = next.copy(priceTo = it); changed = true }
        volume(t)?.let { next = next.copy(volume = it); changed = true }
        animal(t)?.let { next = next.copy(subject = it); changed = true }
        color(t)?.let { next = next.copy(color = it); changed = true }
        quantity(t)?.let { next = next.copy(quantity = it); changed = true }
        if (!changed) return null
        if (next.text.isBlank()) next = next.copy(text = current.text)
        return next
    }

    /** «Теперь найди Nike» — тот же поиск, меняется только бренд. */
    fun isNewSearch(t: String): Boolean = NEW_SEARCH.containsMatchIn(" $t ")

    fun category(text: String): ShoppingCategory? {
        val t = RuTokenizer.normalize(text)
        return ShoppingCategory.entries.firstOrNull { c ->
            CATEGORY_WORDS[c].orEmpty().any { t.contains(it) }
        }
    }

    fun brand(text: String): String? {
        val t = RuTokenizer.normalize(text)
        KNOWN_BRANDS.firstOrNull { Rx.of("""(?:^|[^\p{L}\p{N}])${Regex.escape(it)}(?:[^\p{L}\p{N}]|$)""").containsMatchIn(t) }
            ?.let { return it.replaceFirstChar { c -> c.uppercase() } }
        // Незнакомый бренд пишут латиницей: «кеды sage» — так и ищем. Размеры латиницей («xxl», «m») — не бренд.
        return Regex("""[a-z][a-z&'\-]{2,}""").find(t)?.value
            ?.takeIf { w -> !LETTER_SIZE.matches(w) && w !in NOT_BRAND }
            ?.replaceFirstChar { it.uppercase() }
    }

    fun marketplaces(text: String): Set<Marketplace>? {
        val t = RuTokenizer.normalize(text)
        val found = Marketplace.entries.filter {
            when (it) {
                Marketplace.OZON -> "озон" in t || "ozon" in t
                Marketplace.WB -> "вайлдберриз" in t || "вайлдберри" in t || "wildberries" in t ||
                    Regex("""(?:^|\s)вб(?:\s|$)""").containsMatchIn(t)
            }
        }
        return found.toSet().takeIf { it.isNotEmpty() }
    }

    /** «до 10 тысяч», «не дороже 5000», «бюджет 3к». */
    fun priceTo(text: String): Int? {
        val t = RuTokenizer.normalize(text)
        PRICE.find(t)?.let { m ->
            val value = number(m.groupValues[1], m.groupValues[2])
            // «за 3000» — это цена, а «до 3000» — бюджет: оба ограничивают сверху.
            if (value != null && value in 50..1_000_000) return value
        }
        return null
    }

    fun priceFrom(text: String): Int? {
        val t = RuTokenizer.normalize(text)
        PRICE_FROM.find(t)?.let { m -> return number(m.groupValues[1], m.groupValues[2]) }
        return null
    }

    fun size(text: String): String? {
        val t = RuTokenizer.normalize(text)
        SIZE.find(t)?.let { return it.groupValues[1].uppercase() }
        SIZE_AFTER.find(t)?.let { return it.groupValues[1] }
        LETTER_SIZE.find(t)?.let { return it.groupValues[1].uppercase() }
        return null
    }

    fun volume(text: String): String? {
        val t = RuTokenizer.normalize(text)
        if (HALF_KILO.containsMatchIn(t)) return "500 г"
        VOLUME.find(t)?.let { m ->
            val value = m.groupValues[1].replace(',', '.')
            val unit = m.groupValues[2]
            return "$value $unit".trim()
        }
        return null
    }

    fun animal(text: String): String? {
        val t = RuTokenizer.normalize(text)
        return ANIMALS.firstOrNull { (key, _) -> t.contains(key) }?.second
    }

    fun color(text: String): String? {
        val t = RuTokenizer.normalize(text)
        if ("не важ" in t || "любой" in t || "все равно" in t || "всё равно" in t) return null
        return COLORS.firstOrNull { c -> t.contains(c) }
    }

    /** «с рейтингом от 4,5», «не ниже 4 звёзд». */
    fun minRating(text: String): Double? {
        val t = RuTokenizer.normalize(text)
        if (!("рейтинг" in t || "звезд" in t || "звёзд" in t || "балл" in t)) return null
        MIN_RATING.find(t)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 1.0..5.0 }
            ?.let { return it }
        return null
    }

    fun quantity(text: String): Int? {
        val t = RuTokenizer.normalize(text)
        QUANTITY.find(t)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..20) return it }
            WORDS_QTY.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        }
        return null
    }

    /**
     * Запрос для площадки: из фразы убираем служебные слова («хочу купить»), цену, размер и магазин —
     * их площадка не поймёт и найдёт мусор.
     */
    fun cleanText(source: String): String {
        var t = RuTokenizer.normalize(source)
        // «найди мне футболку» — служебных слов может быть несколько подряд, убираем их все.
        repeat(3) { t = FILLER.replace(t, " ") }
        t = PRICE.replace(t, " ")
        t = SIZE.replace(t, " ")
        t = SIZE_AFTER.replace(t, " ")
        t = LETTER_SIZE.replace(t, " ")
        t = VOLUME.replace(t, " ")
        t = HALF_KILO.replace(t, " ")
        t = t.replace(Regex("""(?:^|\s)(?:на|в)\s+(?:озоне|озон|вайлдберриз\w*|wildberries|вб)(?=\s|$)"""), " ")
        t = SIZE_WORD.replace(t, " ")
        // «Где купить», «сколько стоят» — вопрос человека, а не часть названия товара.
        t = t.replace(Regex("""(?:^|\s)(?:где\s+(?:купить|заказать|найти)|сколько\s+сто(?:ят|ит|имость)|купить|заказать|приобрести)(?=\s|$)"""), " ")
        t = t.replace(Regex("""[^\p{L}\p{N}\s&'\-]"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun number(raw: String, unit: String): Int? {
        val digits = raw.replace(" ", "").replace(',', '.')
        val base = digits.toDoubleOrNull() ?: return null
        val u = unit.lowercase()
        val multiplier = when {
            u.startsWith("тыс") || u.startsWith("тыща") || u == "к" -> 1000
            u.startsWith("млн") -> 1_000_000
            else -> 1
        }
        return Math.round(base * multiplier).toInt().takeIf { it > 0 }
    }
}
