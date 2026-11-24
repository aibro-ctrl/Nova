package ai.nova.core.shopping

/**
 * Маркетплейсы, с которыми работает помощник по покупкам.
 *
 * Публичного API покупателя у площадок нет: товары Нова ищет в тех же открытых каталогах, что и сайт
 * магазина, а корзину и оформление оставляет человеку — см. [ShoppingActionValidator].
 */
enum class Marketplace(val id: String, val title: String, val host: String) {
    OZON("ozon", "Ozon", "ozon.ru"),
    WB("wb", "Wildberries", "wildberries.ru"),
    ;

    /** Страница поиска в браузере или приложении: запасной путь, когда каталог не ответил. */
    fun searchUrl(encodedQuery: String): String = when (this) {
        OZON -> "https://www.ozon.ru/search/?text=$encodedQuery"
        WB -> "https://www.wildberries.ru/catalog/0/search.aspx?search=$encodedQuery"
    }

    companion object {
        /** Площадка по адресу ссылки: так одна и та же карточка открывается в своём приложении. */
        fun byUrl(url: String): Marketplace? = entries.firstOrNull { it.host in url }

        /** «на озоне», «в вайлдберриз», «на вб» — по словам человека. */
        fun of(text: String): Marketplace? {
            val t = " " + text.lowercase().trim() + " "
            return when {
                "озон" in t || "ozon" in t -> OZON
                "вайлдберриз" in t || "вайлдберри" in t || "wildberries" in t ||
                    " вб " in t -> WB
                else -> null
            }
        }
    }
}

/**
 * Корни слов «кому покупаем». Нужны в двух местах: чтобы не повторять животное в запросе к площадке
 * («корм для кошки кошка») и чтобы узнать его в названии чужого товара («для кошек», «кошачий»).
 */
object ShoppingSubjects {
    private val ROOTS = mapOf(
        "кошка" to listOf("кошк", "кошек", "кошач", "кот"),
        "собака" to listOf("собак", "собач", "щен", "пес"),
        "попугай" to listOf("попуга"),
        "птица" to listOf("птиц", "птич", "канарей"),
        "рыба" to listOf("рыб"),
        "хомяк" to listOf("хомя"),
        "черепаха" to listOf("черепах"),
        "кролик" to listOf("кролик", "крольч"),
        "грызуны" to listOf("грызун", "мыш", "крыс", "свинк"),
        "неразлучники" to listOf("неразлучник"),
        "ёж" to listOf("еж"),
        "лошадь" to listOf("лошад", "конь", "коня", "пони"),
    )

    fun roots(subject: String): List<String> =
        ROOTS[subject] ?: listOf(subject.take(5))
}

/** Состояние интеграции с площадкой — то, что Нова показывает человеку без прикрас. */
enum class IntegrationStatus(val text: String) {
    NOT_CONNECTED("не подключена"),
    SEARCH_ONLY("поиск без корзины"),
    CONNECTED("подключена"),
    SESSION_EXPIRED("сессия истекла"),
    TEMPORARILY_UNAVAILABLE("временно недоступна"),
    NEEDS_ACTION("нужно действие пользователя"),
}

/**
 * Товар в том виде, в каком он приходит с площадки. Поля, которых площадка не отдала, остаются null:
 * выдумывать характеристики нельзя.
 */
data class ShoppingProduct(
    val id: String,
    val marketplace: Marketplace,
    val title: String,
    /** Цена в рублях; null — площадка не отдала цену. */
    val price: Int? = null,
    /** Старая цена только тогда, когда площадка отдала её достоверно и она выше текущей. */
    val oldPrice: Int? = null,
    val rating: Double? = null,
    val reviews: Int? = null,
    val brand: String? = null,
    val imageUrl: String? = null,
    val url: String,
    val sizes: List<String> = emptyList(),
    val color: String? = null,
    /** null — площадка не сообщила наличие: так и говорим, а не считаем товар в наличии. */
    val available: Boolean? = null,
    val seller: String? = null,
    /** Выбранный вариант — заполняется, когда человек выбрал размер. */
    val chosenSize: String? = null,
) {
    val priceLabel: String get() = price?.let { "${formatMoney(it)} ₽" } ?: "цена не указана"

    val oldPriceLabel: String?
        get() = oldPrice?.takeIf { price != null && it > price }?.let { formatMoney(it) + " ₽" }

    val ratingLabel: String?
        get() = rating?.let { r ->
            val text = String.format(java.util.Locale.US, "%.1f", r).replace('.', ',')
            val count = reviews?.takeIf { it > 0 }?.let { " · $it ${reviewsWord(it)}" }.orEmpty()
            "★ $text$count"
        }

    companion object {
        fun formatMoney(value: Int): String {
            val digits = value.toString()
            val sb = StringBuilder()
            digits.forEachIndexed { i, c ->
                if (i > 0 && (digits.length - i) % 3 == 0) sb.append('\u00A0')
                sb.append(c)
            }
            return sb.toString()
        }

        fun reviewsWord(count: Int): String = when {
            count % 10 == 1 && count % 100 != 11 -> "отзыв"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "отзыва"
            else -> "отзывов"
        }
    }
}

/** Итог поиска по одной площадке: товары либо понятная причина, почему их нет. */
data class MarketplaceResult(
    val marketplace: Marketplace,
    val products: List<ShoppingProduct> = emptyList(),
    val error: String? = null,
    val status: IntegrationStatus = IntegrationStatus.SEARCH_ONLY,
) {
    val ok: Boolean get() = error == null
}

/** Результат поиска по всем площадкам сразу: одна площадка не отменяет другую. */
data class ShoppingSearchResult(
    val query: ShoppingQuery,
    val results: List<MarketplaceResult>,
    val products: List<ShoppingProduct> = emptyList(),
) {
    val anyOk: Boolean get() = results.any { it.ok }
    val problems: List<MarketplaceResult> get() = results.filter { !it.ok }
    val empty: Boolean get() = products.isEmpty()
}

/** Что стало с добавлением в корзину. Оплату и оформление Нова не делает никогда. */
sealed interface CartResult {
    data class Added(val product: ShoppingProduct, val quantity: Int) : CartResult

    /** Нужен размер или другой вариант — сначала уточняем, потом добавляем. */
    data class NeedsVariant(val product: ShoppingProduct, val kind: String, val options: List<String>) : CartResult

    /** Площадка не даёт добавлять из другого приложения: открываем карточку, нажать нужно самому. */
    data class Manual(val product: ShoppingProduct, val reason: String) : CartResult

    data class Failed(val product: ShoppingProduct, val reason: String) : CartResult
}
