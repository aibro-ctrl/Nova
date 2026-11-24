package ai.nova.core.skills

import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.shopping.CartResult
import ai.nova.core.shopping.Marketplace
import ai.nova.core.shopping.ShoppingActionValidator
import ai.nova.core.shopping.ShoppingCategory
import ai.nova.core.shopping.ShoppingFacts
import ai.nova.core.shopping.ShoppingIntent
import ai.nova.core.shopping.ShoppingProduct
import ai.nova.core.shopping.ShoppingProfile
import ai.nova.core.shopping.ShoppingQuery
import ai.nova.core.shopping.ShoppingRanking
import ai.nova.core.shopping.ShoppingSearchResult
import ai.nova.core.util.Logger
import ai.nova.core.util.TimeSource
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

/**
 * Помощник по покупкам: понимает просьбу, спрашивает только необходимое, ищет на Ozon и Wildberries,
 * показывает лучшие варианты и кладёт выбранное в корзину.
 *
 * Жёсткие границы: Нова не оформляет заказ и не платит ([ShoppingActionValidator]), не выдумывает
 * товары и цены (что не пришло от площадки — того нет), а если каталог недоступен — открывает поиск
 * на площадке и говорит об этом прямо.
 */
class ShoppingSkills(
    private val host: SkillHost,
    private val profile: ShoppingProfile?,
    private val time: TimeSource,
) {
    private var session: ShoppingSession? = null

    /** Нова ждёт уточнения по покупке (размер, бюджет, питомец) — следующая фраза может быть ответом. */
    val awaiting: Boolean get() = live()?.let { it.question != null || it.chosen != null } == true

    /** Поиск по разобранной просьбе. */
    suspend fun search(parsed: ShoppingQuery): SkillOutcome.Say {
        // Площадки: сказанные в просьбе, иначе выбранные в настройках, иначе обе.
        val chosen = runCatching { host.shoppingMarketplaces() }.getOrDefault(emptySet())
        val query = if (parsed.marketplaces.isNotEmpty()) parsed
        else parsed.copy(marketplaces = chosen.ifEmpty { Marketplace.entries.toSet() })
        rememberFromQuery(query)
        return start(query)
    }

    /** Ответ на уточняющий вопрос («46», «до 10 тысяч», «для неразлучников»). */
    suspend fun answer(text: String): SkillOutcome.Say? {
        val s = live() ?: return null
        // Перед корзиной спрашивали размер — это ответ про размер, а не новая просьба.
        if (s.chosen != null) return cartSize(text)
        val next = ShoppingIntent.answer(text, s.query) ?: return null
        // Названный размер или питомец пригодятся и в следующий раз — запоминаем как постоянный факт.
        rememberFromQuery(next)
        return start(next)
    }

    /** «Что ты помнишь о покупках», «забудь размер обуви». */
    suspend fun memory(forget: String?): SkillOutcome.Say {
        val p = profile ?: return SkillOutcome.Say("Память покупок сейчас недоступна.")
        if (forget != null) {
            val attribute = attributeOf(forget) ?: return SkillOutcome.Say("Не поняла, какой факт забыть: размер, бренд или питомца?")
            val removed = p.forget(attribute)
            return SkillOutcome.Say(
                if (removed > 0) "Забыла: ${attributeLabel(attribute)}. Больше не буду это учитывать."
                else "Такого в памяти покупок и не было.",
            )
        }
        val facts = p.all()
        if (facts.isEmpty()) {
            return SkillOutcome.Say("О покупках пока ничего не помню. Скажите размер или любимый бренд — запомню.")
        }
        return SkillOutcome.Say("Помню про покупки: ${facts.joinToString("; ") { it.label }}.")
    }

    /** «Положи второй в корзину», «убери из корзины». */
    suspend fun cart(text: String): SkillOutcome.Say {
        val verdict = ShoppingActionValidator.check(text)
        verdict.reason?.let { return SkillOutcome.Say(it) }
        val s = live() ?: return SkillOutcome.Say("Сначала найдите товар — потом положу его в корзину.")
        if (s.products.isEmpty()) return SkillOutcome.Say("В прошлом поиске нет товаров, нечего класть в корзину.")
        val product = pick(s.products, text)
            ?: return SkillOutcome.Say("Уточните, какой товар: «положи первый» — по номеру из списка.")
        if (verdict.action == ShoppingActionValidator.Action.REMOVE) {
            val ok = host.addToCart(product, product.chosenSize, 0) != null
            return SkillOutcome.Say(
                if (ok) "Убрала ${product.title} из корзины."
                else "Убрать самой не получилось — откройте карточку ${product.title} на ${product.marketplace.title}.",
            )
        }
        if (product.sizes.isNotEmpty() && product.chosenSize == null && s.query.size == null) {
            session = s.copy(question = null, chosen = product, at = time.now())
            return SkillOutcome.Say("Какой размер у ${product.title}? Есть: ${product.sizes.take(6).joinToString(", ")}.")
        }
        return addToCart(product, product.chosenSize ?: s.query.size, s.query.quantity)
    }

    /** Размер к выбранному товару — после вопроса «какой размер?». */
    suspend fun cartSize(text: String): SkillOutcome.Say? {
        val s = session ?: return null
        val product = s.chosen ?: return null
        // Ответ на «какой размер?» — размер цифрами, а не слово «да».
        val size = ShoppingIntent.size(RuTokenizer.normalize(text))
            ?: text.trim().takeIf { it.matches(Regex("""\d{2,3}(?:[.,]5)?""")) }?.replace(',', '.')
        if (size == null || product.sizes.none { it.trim() == size.trim() }) {
            return SkillOutcome.Say("Такого размера нет. Есть: ${product.sizes.take(8).joinToString(", ")}.")
        }
        session = s.copy(chosen = null, at = time.now())
        return addToCart(product.copy(chosenSize = size), size, s.query.quantity)
    }

    fun reset() {
        session = null
    }

    // ------------------------------------------------------------------ Поиск и ответ

    private suspend fun start(query: ShoppingQuery): SkillOutcome.Say {
        val facts = profile?.facts() ?: ShoppingFacts()
        val questions = query.questions(facts)
        if (questions.isNotEmpty()) {
            val q = questions.first()
            val needed = when (q.kind) {
                ShoppingQuestionKind.SIZE -> "Какой размер нужен?"
                ShoppingQuestionKind.SUBJECT -> "Для кого покупаем — кошка, собака, птица?"
            }
            val hint = q.suggestion?.let { suggestion ->
                when (q.kind) {
                    ShoppingQuestionKind.SIZE -> " Помню ваш размер: $suggestion — его брать?"
                    else -> " Прошлый раз: $suggestion — так и оставить?"
                }
            }
            session = ShoppingSession(query = query, question = q.kind, at = time.now())
            return SkillOutcome.Say(needed + hint.orEmpty(), followUp = true)
        }
        // Каталог может молчать: дольше таймаута не ждём — иначе «Думаю» висит, а человек не знает, что делать.
        val result = runCatching { withTimeoutOrNull(SEARCH_TIMEOUT_MS) { host.searchProducts(query) } }
            .getOrElse { e ->
                Logger.w(TAG, "Поиск товаров не удался", e)
                null
            } ?: ShoppingSearchResult(query = query, results = emptyList())
        val ranked = ShoppingRanking.best(result.products, query)
        if (ranked.isEmpty()) return nothingFound(query, result)

        session = ShoppingSession(query = query, question = null, products = ranked, at = time.now())
        return SkillOutcome.Say(summary(ranked, query, result), followUp = false, products = ranked)
    }

    /**
     * Показывать нечего. Причина у каждой площадки своя, и человеку нужна именно она:
     * «каталог закрыт» и «под ваши условия ничего нет» — разные вещи. В обоих случаях открываем
     * поиск на площадке, чтобы просьба не закончилась пустым ответом.
     */
    private suspend fun nothingFound(query: ShoppingQuery, result: ShoppingSearchResult): SkillOutcome.Say {
        session = ShoppingSession(query = query, question = null, at = time.now())
        val target = firstMarketplace(query)
        val catalogsDown = result.results.isEmpty() || result.products.isEmpty() && result.results.all { !it.ok }
        val url = searchUrl(query)
        val opened = runCatching { host.openShoppingUrl(url) }.getOrDefault(false)
        val reason = when {
            !catalogsDown -> "Под ваши условия ничего не нашлось."
            query.marketplaces.singleOrNull() == Marketplace.OZON || target == Marketplace.OZON ->
                "Ozon не отдаёт каталог другим приложениям."
            else -> "Каталог ${target.title} не ответил."
        }
        // Обещать «открыла», не открыв, нельзя: говорим ровно то, что произошло.
        val tail = if (opened) " Открыла поиск «${query.searchText()}» на ${target.title}."
        else " Поиск «${query.searchText()}» на ${target.title} — по ссылке."
        return SkillOutcome.Say(reason + tail, link = url)
    }

    /** Страница поиска на площадке: её же оставляем ссылкой в истории. */
    private fun searchUrl(query: ShoppingQuery): String {
        val marketplace = firstMarketplace(query)
        val encoded = runCatching { URLEncoder.encode(query.searchText(), "UTF-8") }.getOrDefault(query.searchText())
        return marketplace.searchUrl(encoded)
    }

    /** Куда вести, если показывать нечего: туда, где каталог у нас открытый, — иначе на выбранную площадку. */
    private fun firstMarketplace(query: ShoppingQuery): Marketplace =
        query.marketplaces.firstOrNull { it == Marketplace.WB }
            ?: query.marketplaces.minByOrNull { it.ordinal }
            ?: Marketplace.WB

    /**
     * Короткий ответ: сколько нашлось, что лучше и почему. Без пересказа всего списка — он в карточках.
     */
    private fun summary(
        products: List<ShoppingProduct>,
        query: ShoppingQuery,
        result: ShoppingSearchResult,
    ): String {
        val best = products.first()
        val prices = products.mapNotNull { it.price }
        val cheapest = prices.minOrNull()
        val head = when {
            products.size == 1 -> "Нашла вариант:"
            else -> "Нашла ${products.size} ${productWord(products.size)}."
        }
        val pricePart = cheapest?.let { " Цены от ${ShoppingProduct.formatMoney(it)} ₽." }.orEmpty()
        val bestPart = buildString {
            append(" Лучший — ${best.title}")
            best.price?.let { append(" за ${ShoppingProduct.formatMoney(it)} ₽") }
            best.ratingLabel?.let { append(" ($it)") }
            append(" на ${best.marketplace.title}.")
        }
        val failedPart = result.problems
            .map { it.marketplace.title }
            .takeIf { it.isNotEmpty() && it.size < query.marketplaces.size }
            ?.let { " ${it.joinToString(" и ")} не ответил${if (it.size == 1) "" else "и"}." }
            .orEmpty()
        val tail = " Скажите «положи первый в корзину», и я открою карточку — оформляете вы."
        return head + pricePart + bestPart + failedPart + tail
    }

    private fun productWord(count: Int): String = when {
        count % 10 == 1 && count % 100 != 11 -> "товар"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "товара"
        else -> "товаров"
    }

    // ------------------------------------------------------------------ Корзина

    private suspend fun addToCart(product: ShoppingProduct, size: String?, quantity: Int): SkillOutcome.Say {
        val result = runCatching { host.addToCart(product, size, quantity) }.getOrNull()
        return when (result) {
            is CartResult.Added -> SkillOutcome.Say(
                "Положила ${product.title} в корзину ${product.marketplace.title}" +
                    (if (result.quantity > 1) ", ${result.quantity} шт." else "") + ". Оформление и оплату — только вы.",
            )
            is CartResult.NeedsVariant -> SkillOutcome.Say("Выберите ${result.kind}: ${result.options.take(8).joinToString(", ")}.", followUp = true)
            is CartResult.Manual -> SkillOutcome.Say(result.reason, followUp = false, products = listOf(product))
            is CartResult.Failed -> SkillOutcome.Say("Не получилось добавить: ${result.reason}. Открываю карточку.")
                .also { runCatching { host.openShoppingUrl(product.url) } }
            null -> {
                runCatching { host.openShoppingUrl(product.url) }
                SkillOutcome.Say(
                    "Открыла карточку «${product.title}» на ${product.marketplace.title}: добавьте в корзину сами, " +
                        "я не оформляю покупки за вас.",
                    products = listOf(product),
                )
            }
        }
    }

    /** «первый», «второй», «третий», по номеру и «последний» — из показанного списка. */
    private fun pick(products: List<ShoppingProduct>, text: String): ShoppingProduct? {
        val t = RuTokenizer.normalize(text)
        val words = listOf("перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят")
        val byWord = words.indexOfFirst { t.contains(it) }
        if (byWord >= 0) return products.getOrNull(byWord)
        Regex("""(\d{1,2})""").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { return products.getOrNull(it - 1) }
        if ("последн" in t) return products.lastOrNull()
        // Без номера — «этот», «его»: берём лучший, но только если товар один.
        return products.singleOrNull() ?: products.firstOrNull().takeIf { products.size == 1 }
    }

    // ------------------------------------------------------------------ Память покупок

    /**
     * Постоянные факты из состоявшейся просьбы: размер обуви и одежды, вид питомца.
     * Разовая цена и бренд в память не попадают — иначе Нова «запомнит» сегодняшний бюджет.
     */
    private suspend fun rememberFromQuery(query: ShoppingQuery) {
        val p = profile ?: return
        val size = query.size
        if (size != null && query.category.needsSize) {
            val attribute = if (query.category == ShoppingCategory.SHOES) "shoe_size" else "clothes_size"
            runCatching { p.remember(attribute, size) }
        }
        val pet = query.subject
        if (pet != null && query.category == ShoppingCategory.PET) {
            runCatching { p.remember("pet_kind", pet) }
        }
    }

    private fun attributeOf(text: String): String? {
        val t = RuTokenizer.normalize(text)
        return when {
            "обув" in t -> "shoe_size"
            "одежд" in t -> "clothes_size"
            "бренд" in t || "марк" in t -> "preferred_brand"
            "питом" in t || "животн" in t || "корм" in t -> "pet_kind"
            "цвет" in t -> "preferred_color"
            "размер" in t -> "shoe_size"
            else -> null
        }
    }

    private fun live(): ShoppingSession? {
        val s = session ?: return null
        if (Duration.between(s.at, time.now()) > TTL) {
            session = null
            return null
        }
        return s
    }

    private fun attributeLabel(attribute: String): String = when (attribute) {
        "shoe_size" -> "размер обуви"
        "clothes_size" -> "размер одежды"
        "preferred_brand" -> "любимый бренд"
        "pet_kind" -> "питомец"
        "preferred_color" -> "любимый цвет"
        else -> attribute
    }

    private companion object {
        const val TAG = "Shopping"
        val TTL: Duration = Duration.ofMinutes(20)
        /** Сколько ждём каталог площадки, прежде чем сказать «не ответил». */
        const val SEARCH_TIMEOUT_MS = 15_000L
    }
}

/** Состояние разговора о покупке: живёт между фразами, чтобы уточнение не начинало поиск заново. */
private data class ShoppingSession(
    val query: ShoppingQuery,
    val question: ShoppingQuestionKind? = null,
    val products: List<ShoppingProduct> = emptyList(),
    /** Товар, для которого ждём размер перед корзиной. */
    val chosen: ShoppingProduct? = null,
    val at: Instant,
)

/** Вид уточнения — чтобы не тащить в навык весь класс вопроса. */
typealias ShoppingQuestionKind = ai.nova.core.shopping.ShoppingQuestion.Kind
