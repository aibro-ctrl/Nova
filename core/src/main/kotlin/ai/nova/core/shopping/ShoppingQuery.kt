package ai.nova.core.shopping

/** Категория товара — от неё зависит, какие вопросы вообще уместны. */
enum class ShoppingCategory(val title: String) {
    SHOES("обувь"),
    CLOTHES("одежда"),
    OUTERWEAR("верхняя одежда"),
    TECH("техника"),
    HOME("дом"),
    PET("товары для животных"),
    AUTO("авто"),
    FOOD("продукты"),
    KIDS("детское"),
    BEAUTY("косметика"),
    SPORT("спорт"),
    GARDEN("сад и огород"),
    BOOKS("книги и канцелярия"),
    OTHER("товары"),
    ;

    /** У обуви и одежды размер обязателен: без него подборка бессмысленна. */
    val needsSize: Boolean get() = this == SHOES || this == CLOTHES || this == OUTERWEAR
}

/**
 * Что человек хочет купить: собирается из его слов, сессии и памяти. Незаполненные поля — null,
 * а не «любой»: иначе Нова перестанет спрашивать то, что действительно нужно.
 */
data class ShoppingQuery(
    /** Свободный текст запроса без служебных слов («кеды puma»). Уходит в поиск площадки. */
    val text: String = "",
    val category: ShoppingCategory = ShoppingCategory.OTHER,
    val brand: String? = null,
    val size: String? = null,
    val color: String? = null,
    /** Бюджет сверху, рубли. */
    val priceTo: Int? = null,
    val priceFrom: Int? = null,
    val quantity: Int = 1,
    val marketplaces: Set<Marketplace> = emptySet(),
    /** Объём или вес упаковки — важен для корма, бытовой химии, продуктов. */
    val volume: String? = null,
    /** Вид животного или другое уточнение, без которого подборка будет неверной. */
    val subject: String? = null,
    /** «только с рейтингом от 4,5» — жёсткое требование человека. */
    val minRating: Double? = null,
) {
    /** Текст для площадки: «кеды puma 46». */
    fun searchText(): String {
        val parts = ArrayList<String>(4)
        // «кеды puma» + бренд Puma — площадке хватит одного упоминания.
        fun add(value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return
            val already = parts.joinToString(" ").lowercase()
            if (already.contains(v.lowercase())) return
            parts += v
        }
        add(text.ifBlank { category.title })
        add(brand)
        add(size?.let { if (category == ShoppingCategory.SHOES) "$it размер" else it })
        add(color)
        add(volume)
        add(subject)
        return parts.joinToString(" ").trim()
    }

    /**
     * Вопросы, которые действительно нужно задать. Бюджет не спрашиваем: поиск работает и без него,
     * а деньги — не тот вопрос, который стоит задавать на каждый запрос.
     */
    fun questions(profile: ShoppingFacts): List<ShoppingQuestion> {
        val out = ArrayList<ShoppingQuestion>()
        if (category.needsSize && size == null) {
            val saved = if (category == ShoppingCategory.SHOES) profile.shoeSize else profile.clothesSize
            out += ShoppingQuestion(ShoppingQuestion.Kind.SIZE, saved)
        }
        if (category == ShoppingCategory.PET && subject == null) {
            out += ShoppingQuestion(ShoppingQuestion.Kind.SUBJECT, profile.petKind)
        }
        return out.take(2)
    }
}

/** Уточняющий вопрос: один, максимум два — не допрос. */
data class ShoppingQuestion(val kind: Kind, val suggestion: String? = null) {
    enum class Kind { SIZE, SUBJECT }
}

/**
 * Факты о человеке, полезные для будущих покупок. Постоянные (размер, бренд, вид животного)
 * отделены от разовых параметров текущего поиска: разовое в память не попадает.
 */
data class ShoppingFacts(
    val shoeSize: String? = null,
    val clothesSize: String? = null,
    val preferredBrands: List<String> = emptyList(),
    val petKind: String? = null,
) {
    companion object {
        /** Группы фактов, которые имеет смысл запоминать между покупками. */
        val LONG_TERM = setOf("shoe_size", "clothes_size", "preferred_brand", "pet_kind", "preferred_color")
    }
}
