package ai.nova.core.shopping

import ai.nova.core.domain.MemoryRepository
import ai.nova.core.model.MemoryItem
import ai.nova.core.skills.SkillCommand
import ai.nova.core.skills.SkillPhrases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Помощник по покупкам: разбор просьбы, отбор, память и границы корзины. */
class ShoppingTest {

    // ------------------------------------------------------------------ Разбор просьбы

    @Test
    fun `разбирает кеды с размером и бюджетом`() {
        val q = ShoppingIntent.parse("хочу кеды Puma, 46 размер, до 10 тысяч")
        assertEquals(ShoppingCategory.SHOES, q.category)
        assertEquals("Puma", q.brand)
        assertEquals("46", q.size)
        assertEquals(10_000, q.priceTo)
        assertFalse(q.text.contains("хочу"))
        assertFalse(q.text.contains("размер"))
        assertTrue(q.searchText().contains("puma", ignoreCase = true))
    }

    @Test
    fun `полкило и лимит цены для корма`() {
        val q = ShoppingIntent.parse("найди корм для неразлучников, не меньше полукилограмма, до 1000")
        assertEquals(ShoppingCategory.PET, q.category)
        assertEquals("неразлучники", q.subject)
        assertEquals("500 г", q.volume)
        assertEquals(1000, q.priceTo)
    }

    @Test
    fun `товары из разных категорий распознаются`() {
        // Словарь категорий не должен упираться в одежду: товар ищут по просьбе о покупке.
        val cases = mapOf(
            "купи молоток" to ShoppingCategory.HOME,
            "купи шуруповерт" to ShoppingCategory.HOME,
            "купи корм для кошек" to ShoppingCategory.PET,
            "купи подгузники" to ShoppingCategory.KIDS,
            "купи наушники беспроводные" to ShoppingCategory.TECH,
            "купи сковороду" to ShoppingCategory.HOME,
            "найди для покупки шоколад" to ShoppingCategory.FOOD,
            "купи шампунь" to ShoppingCategory.BEAUTY,
            "купи шины 205 55 16" to ShoppingCategory.AUTO,
            "купи моторное масло" to ShoppingCategory.AUTO,
            "купи гантели" to ShoppingCategory.SPORT,
            "купи семена томатов" to ShoppingCategory.GARDEN,
            "купи рюкзак" to ShoppingCategory.HOME,
            "купи чемодан" to ShoppingCategory.HOME,
            "купи зарядное устройство" to ShoppingCategory.TECH,
            "купи постельное белье 2 спальное" to ShoppingCategory.HOME,
            "купи смартфон" to ShoppingCategory.TECH,
            "купи наполнитель для кошачьего туалета" to ShoppingCategory.PET,
            "купи шариковую ручку" to ShoppingCategory.BOOKS,
        )
        val today = java.time.LocalDate.of(2026, 9, 25)
        cases.forEach { (text, category) ->
            assertEquals(category, ShoppingIntent.category(text), text)
            val cmd = SkillPhrases.parse(text, today)
            assertTrue(cmd is SkillCommand.Shopping, "$text → $cmd")
            assertEquals(category, (cmd as SkillCommand.Shopping).query.category, text)
        }
    }

    @Test
    fun `слово найди само по себе магазин не открывает`() {
        // «Найди» слишком широкое: маршрут, аптека, видео и статья тоже начинаются с него.
        val today = java.time.LocalDate.of(2026, 9, 25)
        listOf(
            "найди маршрут домой",
            "найди аптеку",
            "найди видео кота",
            "найди фильм",
            "найди часы работы аптеки",
            "найди молоток",
            "найди корм для кошек",
            "найди рецепт борща",
            "найди информацию о Гагарине",
        ).forEach { text ->
            val cmd = SkillPhrases.parse(text, today)
            assertTrue(cmd !is SkillCommand.Shopping, "$text → $cmd")
        }
    }

    @Test
    fun `просьба о покупке и признаки товара магазин открывают`() {
        val today = java.time.LocalDate.of(2026, 9, 25)
        listOf(
            "купи молоток",
            "купить кроссовки",
            "хочу купить кроссовки 42 размер",
            "найди для покупки кеды 44 размер",
            "закажи корм для кошки",
            "подбери подарок для кошки",
            "сколько стоят кеды",
            "найди кеды 44 размер",
            "найди кеды до 8000",
            "хочу кеды puma 46 размер до 10 тысяч",
        ).forEach { text ->
            val cmd = SkillPhrases.parse(text, today)
            assertTrue(cmd is SkillCommand.Shopping, "$text → $cmd")
        }
    }

    @Test
    fun `животное из просьбы не спрашиваем повторно`() {
        // Кому покупаем — уже сказано в самой просьбе: переспрашивать нечего.
        mapOf(
            "найди корм для кошек" to "кошка",
            "найди корм для кошки" to "кошка",
            "найди корм для кота" to "кошка",
            "найди корм для котёнка" to "кошка",
            "кошачий корм" to "кошка",
            "наполнитель для кошачьего туалета" to "кошка",
            "найди корм для собак" to "собака",
            "найди корм для собаки" to "собака",
            "собачий мяч" to "собака",
            "найди корм для щенка" to "собака",
            "найди корм для попугая" to "попугай",
            "найди корм для птиц" to "птица",
            "найди корм для рыбок" to "рыба",
            "найди корм для хомяка" to "хомяк",
            "найди корм для кролика" to "кролик",
            "найди корм для черепахи" to "черепаха",
            "найди наполнитель для грызунов" to "грызуны",
        ).forEach { (text, pet) ->
            val q = ShoppingIntent.parse(text)
            assertEquals(pet, q.subject, text)
            assertTrue(
                q.questions(ShoppingFacts()).none { it.kind == ShoppingQuestion.Kind.SUBJECT },
                "$text спрашивает вид животного",
            )
        }
    }

    @Test
    fun `постельное белье не требует размера одежды`() {
        val q = ShoppingIntent.parse("найди постельное белье 2 спальное")
        assertEquals(ShoppingCategory.HOME, q.category)
        assertTrue(q.questions(ShoppingFacts()).isEmpty(), q.questions(ShoppingFacts()).toString())
    }

    @Test
    fun `у игрушек и конструкторов размер не спрашивают`() {
        val today = java.time.LocalDate.of(2026, 9, 25)
        val cmd = SkillPhrases.parse("купи конструктор лего", today)
        assertTrue(cmd is SkillCommand.Shopping, cmd.toString())
        val query = (cmd as SkillCommand.Shopping).query
        assertFalse(query.category.needsSize, query.category.title)
        assertTrue(query.questions(ShoppingFacts()).isEmpty(), query.questions(ShoppingFacts()).toString())
    }

    @Test
    fun `справка и рецепт не превращаются в поиск товара`() {
        val today = java.time.LocalDate.of(2026, 9, 25)
        listOf(
            "найди рецепт борща",
            "найди информацию о Гагарине",
            "найди курс доллара",
            "найди песню Queen",
            "купи хлеб",
        ).forEach { text ->
            assertTrue(SkillPhrases.parse(text, today) !is SkillCommand.Shopping, text)
        }
    }

    @Test
    fun `новый поиск меняет бренд и сохраняет бюджет`() {
        val first = ShoppingIntent.parse("хочу кеды до 10 тысяч")
        val next = ShoppingIntent.parse("теперь найди Nike", previous = first)
        assertEquals("Nike", next.brand)
        assertEquals(10_000, next.priceTo)
        assertEquals(ShoppingCategory.SHOES, next.category)
    }

    @Test
    fun `размер из памяти подставляется сам`() {
        val q = ShoppingIntent.parse("хочу кроссовки", ShoppingFacts(shoeSize = "44"))
        assertEquals("44", q.size)
        assertTrue(q.questions(ShoppingFacts(shoeSize = "44")).none { it.kind == ShoppingQuestion.Kind.SIZE })
    }

    @Test
    fun `без размера спрашиваем только размер`() {
        val q = ShoppingIntent.parse("хочу кроссовки")
        val questions = q.questions(ShoppingFacts())
        assertEquals(listOf(ShoppingQuestion.Kind.SIZE), questions.map { it.kind })
    }

    @Test
    fun `бюджет не спрашиваем`() {
        val q = ShoppingQuery(text = "наушники", category = ShoppingCategory.TECH)
        assertTrue(q.questions(ShoppingFacts()).isEmpty())
    }

    @Test
    fun `про питомца спрашиваем отдельно`() {
        val q = ShoppingQuery(text = "корм", category = ShoppingCategory.PET)
        assertEquals(listOf(ShoppingQuestion.Kind.SUBJECT), q.questions(ShoppingFacts()).map { it.kind })
    }

    @Test
    fun `ответ на вопрос уточняет, а не начинает заново`() {
        val q = ShoppingIntent.parse("хочу кроссовки")
        val answered = ShoppingIntent.answer("46", q.copy(priceTo = 5000))
        assertNotNull(answered)
        assertEquals("46", answered.size)
        assertEquals("кроссовки", answered.text)
    }

    @Test
    fun `пустой ответ не подменяет просьбу`() {
        val q = ShoppingIntent.parse("хочу кроссовки")
        assertNull(ShoppingIntent.answer("включи музыку", q))
    }

    // ------------------------------------------------------------------ Отбор

    @Test
    fun `пять из двух отзывов не обгоняет четыре восемь из тысячи`() {
        val lucky = product(id = "1", title = "Кеды Puma", price = 4000, rating = 5.0, reviews = 2)
        val solid = product(id = "2", title = "Кеды Puma", price = 4500, rating = 4.8, reviews = 1000)
        val best = ShoppingRanking.best(listOf(lucky, solid), ShoppingQuery(text = "кеды puma"))
        assertEquals("2", best.first().id)
    }

    @Test
    fun `дороже бюджета не показываем`() {
        val cheap = product(id = "1", price = 3000, title = "Кеды Puma")
        val dear = product(id = "2", price = 30_000, title = "Кеды Puma")
        val best = ShoppingRanking.best(listOf(cheap, dear), ShoppingQuery(text = "кеды", priceTo = 10_000))
        assertEquals(listOf("1"), best.map { it.id })
    }

    @Test
    fun `чужой размер отсекается, если площадка его сообщила`() {
        val q = ShoppingQuery(text = "кеды", size = "46", category = ShoppingCategory.SHOES)
        val wrong = product(id = "1", title = "Кеды", sizes = listOf("42", "43"))
        val right = product(id = "2", title = "Кеды", sizes = listOf("46"))
        assertEquals(listOf("2"), ShoppingRanking.best(listOf(wrong, right), q).map { it.id })
    }

    @Test
    fun `корм не заменяется универсальным`() {
        val q = ShoppingQuery(text = "корм", category = ShoppingCategory.PET, subject = "неразлучники")
        val generic = product(id = "1", title = "Корм для всех птиц")
        val exact = product(id = "2", title = "Корм для неразлучников")
        assertEquals(listOf("2"), ShoppingRanking.best(listOf(generic, exact), q).map { it.id })
    }

    @Test
    fun `корм для кошки находит кошачий и для кошек`() {
        // «Для кошки» — это и «для кошек», и «кошачий»: формы слова в названии чужие.
        val q = ShoppingQuery(text = "корм для кошки", category = ShoppingCategory.PET, subject = "кошка")
        val forCats = product(id = "1", title = "Корм для кошек Hills")
        val catFood = product(id = "2", title = "Кошачий корм Royal Canin")
        val forDogs = product(id = "3", title = "Корм для собак Pedigree")
        assertEquals(
            setOf("1", "2"),
            ShoppingRanking.best(listOf(forCats, catFood, forDogs), q).map { it.id }.toSet(),
        )
    }

    @Test
    fun `животное не повторяется в запросе к площадке`() {
        // «корм для кошки кошка» площадка понимает хуже, а Нова так и искала.
        assertEquals("корм для кошки", ShoppingIntent.parse("купи корм для кошки").searchText())
        assertEquals("кошачий корм", ShoppingIntent.parse("купи кошачий корм").searchText())
        assertEquals("корм для попугая", ShoppingIntent.parse("купи корм для попугая").searchText())
        assertEquals(
            "корм для неразлучников",
            ShoppingIntent.parse("купи корм для неразлучников, до 1000").searchText().replace(" 1000", ""),
        )
    }

    @Test
    fun `второй магазин не пропадает из-за дешёвых карточек первого`() {
        // Десять дешёвых карточек Wildberries не должны закрыть Ozon: сравнивать цены иначе не с чем.
        val wb = (1..12).map { product(id = "wb$it", price = 1000 + it, title = "Кеды Puma") }
        val ozon = product(id = "ozon1", price = 9000, title = "Кеды Puma Oz").copy(marketplace = Marketplace.OZON)
        val best = ShoppingRanking.best(wb + ozon, ShoppingQuery(text = "кеды puma"))
        assertEquals(ShoppingRanking.LIMIT, best.size)
        assertTrue(best.any { it.marketplace == Marketplace.OZON }, best.map { it.marketplace }.toString())
        // Второй магазин идёт сразу за лидером, а не в конце списка.
        assertEquals(Marketplace.OZON, best[1].marketplace)
    }

    @Test
    fun `список не добивается случайными товарами`() {
        val items = (1..3).map { product(id = "$it", title = "Кеды", price = 1000 * it) }
        assertEquals(3, ShoppingRanking.best(items, ShoppingQuery(text = "кеды")).size)
    }

    @Test
    fun `один товар не показывается дважды`() {
        val items = listOf(product(id = "1", title = "Кеды"), product(id = "1", title = "Кеды"))
        assertEquals(1, ShoppingRanking.best(items, ShoppingQuery(text = "кеды")).size)
    }

    // ------------------------------------------------------------------ Корзина и оплата

    @Test
    fun `оплату и оформление Нова не делает`() {
        assertTrue(ShoppingActionValidator.check("оплати заказ").blocked)
        assertTrue(ShoppingActionValidator.check("оформи заказ").blocked)
        assertTrue(ShoppingActionValidator.check("подтверди заказ и спиши деньги").blocked)
        assertNotNull(ShoppingActionValidator.refusal("введи карту и оплати"))
    }

    @Test
    fun `в корзину положить можно`() {
        val verdict = ShoppingActionValidator.check("положи второй в корзину")
        assertFalse(verdict.blocked)
        assertEquals(ShoppingActionValidator.Action.ADD, verdict.action)
    }

    // ------------------------------------------------------------------ Площадки

    @Test
    fun `разбор каталога Wildberries`() {
        val json = """
            {"data":{"products":[
              {"id":123456,"name":"Кеды Puma Rebound","brand":"Puma","salePriceU":459900,"priceU":659900,
               "reviewRating":4.8,"feedbacks":1240,"sizes":[{"name":"46","origName":"46"}]},
              {"id":222222,"name":"","brand":"Puma","salePriceU":100}
            ]}}
        """.trimIndent()
        val products = WildberriesProvider(null).parse(json, limit = 10)
        assertEquals(1, products.size)
        val p = products.single()
        assertEquals("Кеды Puma Rebound", p.title)
        assertEquals(4599, p.price)
        assertEquals(6599, p.oldPrice)
        assertEquals(listOf("46"), p.sizes)
        assertEquals("https://www.wildberries.ru/catalog/123456/detail.aspx", p.url)
        assertTrue(p.imageUrl!!.startsWith("https://basket-"))
    }

    @Test
    fun `цена из размера, когда общей нет`() {
        val json = """{"products":[{"id":99,"name":"Куртка","sizes":[{"origName":"L","price":{"total":250000}}]}]}"""
        val p = WildberriesProvider(null).parse(json, limit = 10).single()
        assertEquals(2500, p.price)
        assertEquals(listOf("L"), p.sizes)
    }

    @Test
    fun `чужой ответ не роняет поиск`() {
        assertEquals(emptyList(), WildberriesProvider(null).parse("<html>403</html>", limit = 10))
        assertEquals(emptyList(), WildberriesProvider(null).parse("""{"data":{}}""", limit = 10))
    }

    @Test
    fun `Ozon честно сообщает, что каталога нет`() = runTest {
        val result = OzonProvider().search(ShoppingQuery(text = "кеды"), 10)
        assertTrue(result.products.isEmpty())
        assertEquals(IntegrationStatus.SEARCH_ONLY, result.status)
        assertNotNull(result.error)
    }

    // ------------------------------------------------------------------ Память покупок

    @Test
    fun `постоянный факт запоминается, разовый нет`() = runTest {
        val profile = ShoppingProfile(FakeMemory())
        assertNotNull(profile.remember("shoe_size", "46"))
        assertNull(profile.remember("price_to", "10000"))
        val facts = profile.facts()
        assertEquals("46", facts.shoeSize)
    }

    @Test
    fun `повторный факт не дублируется`() = runTest {
        val memory = FakeMemory()
        val profile = ShoppingProfile(memory)
        profile.remember("shoe_size", "46")
        profile.remember("shoe_size", "46")
        assertEquals(1, memory.items.size)
    }

    @Test
    fun `человек может забыть факт`() = runTest {
        val memory = FakeMemory()
        val profile = ShoppingProfile(memory)
        profile.remember("pet_kind", "кошка")
        assertEquals(1, profile.forget("pet_kind"))
        assertNull(profile.facts().petKind)
        assertEquals(0, profile.forget("pet_kind"))
    }

    @Test
    fun `факт из реплики`() {
        val profile = ShoppingProfile(FakeMemory())
        assertEquals("shoe_size" to "46", profile.factFrom("у меня 46 размер обуви", ShoppingCategory.SHOES))
        assertEquals("clothes_size" to "48", profile.factFrom("мой размер 48", ShoppingCategory.CLOTHES))
        assertEquals("pet_kind" to "кошка", profile.factFrom("у меня есть кошка", ShoppingCategory.PET))
    }

    // ------------------------------------------------------------------ Карточки в истории чата

    @Test
    fun `карточки сохраняются в истории и читаются обратно`() {
        val items = listOf(product(id = "1", title = "Кеды | Puma ¦ 46", price = 4599, rating = 4.8, reviews = 1240))
        val stored = ShoppingCards.append("Нашла 1 товар.", items)
        val decoded = ShoppingCards.decode(stored)
        assertEquals("Нашла 1 товар.", decoded.text)
        assertEquals(1, decoded.products.size)
        assertEquals("Кеды | Puma ¦ 46", decoded.products.single().title)
        assertEquals(4599, decoded.products.single().price)
        assertEquals(Marketplace.WB, decoded.products.single().marketplace)
    }

    @Test
    fun `фотография товара остаётся в истории вместе с карточкой`() {
        val withPhoto = product(id = "1", title = "Кеды Puma").copy(imageUrl = "https://basket.example/1.jpg")
        val decoded = ShoppingCards.decode(ShoppingCards.append("Нашла вариант:", listOf(withPhoto)))
        assertEquals("https://basket.example/1.jpg", decoded.products.single().imageUrl)
        // Старая запись без фотографии читается как раньше — без картинки и без ошибок.
        val sep = " \u00A6 "
        val old = ShoppingCards.decode(
            "#товар#" + sep + listOf("wb", "1", "4599", "4.8", "12", "%D0%9A%D0%B5%D0%B4%D1%8B", "https%3A%2F%2Fwww.wildberries.ru%2Fcatalog%2F1%2Fdetail.aspx")
                .joinToString(sep),
        )
        assertEquals(1, old.products.size)
        assertNull(old.products.single().imageUrl)
    }

    @Test
    fun `обычный текст не портится`() {
        val decoded = ShoppingCards.decode("Просто ответ без товаров.")
        assertEquals("Просто ответ без товаров.", decoded.text)
        assertTrue(decoded.products.isEmpty())
    }

    // ------------------------------------------------------------------ Деньги в ответе

    @Test
    fun `цена читается человеком`() {
        assertEquals("4\u00A0599 ₽", product(price = 4599).priceLabel)
        assertEquals("1\u00A0200\u00A0000 ₽", product(price = 1_200_000).priceLabel)
        assertEquals("отзыв", ShoppingProduct.reviewsWord(1))
        assertEquals("отзыва", ShoppingProduct.reviewsWord(3))
        assertEquals("отзывов", ShoppingProduct.reviewsWord(11))
    }

    private fun product(
        id: String = "1",
        title: String = "Кеды",
        price: Int? = 1000,
        rating: Double? = 4.5,
        reviews: Int? = 100,
        sizes: List<String> = emptyList(),
    ) = ShoppingProduct(
        id = id,
        marketplace = Marketplace.WB,
        title = title,
        price = price,
        rating = rating,
        reviews = reviews,
        url = "https://www.wildberries.ru/catalog/$id/detail.aspx",
        sizes = sizes,
    )

    @Test
    fun `буквенный размер одежды не становится брендом`() {
        val q = ShoppingIntent.parse("найди мне футболку puma xxl размера")
        assertEquals("XXL", q.size)
        assertEquals("Puma", q.brand)
        assertEquals("футболку puma XXL", q.searchText())
    }

    @Test
    fun `бренд не повторяется в запросе к площадке`() {
        val q = ShoppingIntent.parse("хочу кеды puma 46 размер до 10 тысяч")
        assertEquals("кеды puma 46 размер", q.searchText())
    }

    private class FakeMemory : MemoryRepository {
        val items = ArrayList<MemoryItem>()
        private var next = 0

        override fun observe(): Flow<List<MemoryItem>> = flowOf(items)
        override suspend fun all(): List<MemoryItem> = items.toList()
        override suspend fun get(id: String): MemoryItem? = items.firstOrNull { it.id == id }
        override suspend fun create(content: String, category: String): MemoryItem {
            val item = MemoryItem("m${next++}", content, category, Instant.now(), Instant.now())
            items += item
            return item
        }

        override suspend fun update(item: MemoryItem): MemoryItem {
            items.replaceAll { if (it.id == item.id) item else it }
            return item
        }

        override suspend fun delete(id: String): Boolean = items.removeAll { it.id == id }
    }
}
