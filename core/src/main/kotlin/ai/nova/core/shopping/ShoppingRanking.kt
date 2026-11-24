package ai.nova.core.shopping

import ai.nova.core.nlp.RuTokenizer

/**
 * Отбор лучших предложений из того, что нашли площадки.
 *
 * Правила простые и объяснимые: обязательные требования человека (размер, бюджет, вид животного)
 * отсекаются до оценки, а среди подходящих выигрывает товар с хорошей оценкой И заметным числом отзывов —
 * поэтому «5,0 из двух отзывов» не обгоняет «4,8 из тысячи». Меньше десяти подходящих — показываем
 * сколько есть: добивать список неподходящим нельзя.
 */
object ShoppingRanking {

    /** Сколько товаров показываем: больше десяти в чате не читается. */
    const val LIMIT = 10

    /** Вес отзывов: при 30 отзывах оценка учитывается наполовину, при 300 — почти полностью. */
    private const val REVIEW_TRUST = 30.0

    fun best(products: List<ShoppingProduct>, query: ShoppingQuery, limit: Int = LIMIT): List<ShoppingProduct> {
        val suitable = products
            .filter { fits(it, query) }
            .distinctBy { it.marketplace to it.id }
        if (suitable.isEmpty()) return emptyList()
        val cheapest = suitable.mapNotNull { it.price }.minOrNull()
        val ranked = suitable
            .map { it to score(it, query, cheapest) }
            .sortedWith(
                compareByDescending<Pair<ShoppingProduct, Double>> { it.second }
                    .thenBy { it.first.price ?: Int.MAX_VALUE },
            )
            .map { it.first }
        // По одному лучшему с каждой площадки — вперёд: иначе десяток дешёвых карточек одного магазина
        // прячет второй магазин целиком, и сравнивать цены не с чем.
        val head = ranked.distinctBy { it.marketplace }
        return (head + ranked).distinctBy { it.marketplace to it.id }.take(limit)
    }

    /** Обязательные требования. Если площадка не сообщила данные, товар не отбрасываем, а помечаем. */
    fun fits(product: ShoppingProduct, query: ShoppingQuery): Boolean {
        val budget = query.priceTo
        if (budget != null && product.price != null && product.price > budget) return false
        query.priceFrom?.let { from -> if (product.price != null && product.price < from) return false }
        query.minRating?.let { min -> if (product.rating != null && product.rating < min) return false }
        if (query.size != null && revealsSizes(product) && !hasSize(product, query.size)) return false
        if (query.subject != null && !mentionsSubject(product, query.subject)) return false
        if (product.title.isBlank()) return false
        return true
    }

    /** Оценка 0..100: соответствие запросу, доверие к оценке, цена, наличие. */
    fun score(product: ShoppingProduct, query: ShoppingQuery, cheapest: Int?): Double {
        val words = words(query.searchText())
        val title = RuTokenizer.normalize(product.title)
        val relevance = if (words.isEmpty()) 0.5 else words.count { title.contains(it) }.toDouble() / words.size

        val rating = product.rating ?: 0.0
        val reviews = product.reviews ?: 0
        // Доверие растёт с числом отзывов: 5,0 из двух отзывов ≈ 2,1 доверия, 4,8 из тысячи ≈ 4,7.
        val trust = rating * (reviews / (reviews + REVIEW_TRUST))

        val priceScore = when {
            product.price == null || cheapest == null || cheapest <= 0 -> 0.5
            else -> (cheapest.toDouble() / product.price).coerceIn(0.0, 1.0)
        }
        val available = when (product.available) {
            true -> 1.0
            false -> 0.0
            null -> 0.6
        }
        return relevance * 35 + trust / 5.0 * 35 + priceScore * 20 + available * 10
    }

    private fun revealsSizes(product: ShoppingProduct): Boolean = product.sizes.isNotEmpty()

    private fun hasSize(product: ShoppingProduct, size: String): Boolean =
        product.sizes.any { it.trim().replace(",", ".") == size.trim().replace(",", ".") } ||
            RuTokenizer.normalize(product.title).contains(size)

    /**
     * Корм для неразлучников не заменяем универсальным «для всех птиц». Сравниваем по корням:
     * «корм для кошки» должен находить «для кошек» и «кошачий», а не только точную форму слова.
     */
    private fun mentionsSubject(product: ShoppingProduct, subject: String): Boolean {
        val title = RuTokenizer.normalize(product.title + " " + (product.brand ?: ""))
        return ShoppingSubjects.roots(subject).any { title.contains(it) }
    }

    private fun words(text: String): List<String> = RuTokenizer.normalize(text)
        .split(' ', ',', '.')
        .map { it.trim() }
        .filter { it.length >= 3 }
}
