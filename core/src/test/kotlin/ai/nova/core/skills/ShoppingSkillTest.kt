package ai.nova.core.skills

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import ai.nova.core.model.MessageRole
import ai.nova.core.shopping.CartResult
import ai.nova.core.shopping.Marketplace
import ai.nova.core.shopping.MarketplaceResult
import ai.nova.core.shopping.ShoppingProduct
import ai.nova.core.shopping.ShoppingQuery
import ai.nova.core.shopping.ShoppingSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Покупки голосом: уточнение, подборка в ответе и корзина без оплаты. */
class ShoppingSkillTest {

    private open class Host : SkillHost {
        val queries = ArrayList<ShoppingQuery>()
        val opened = ArrayList<String>()
        val cart = ArrayList<Pair<String, String?>>()
        /** Что ушло в список покупок, когда корзина площадки недоступна. */
        val saved = ArrayList<ai.nova.core.shopping.ShoppingProduct>()
        var result: List<ShoppingProduct> = listOf(
            ShoppingProduct(
                id = "1", marketplace = Marketplace.WB, title = "Кеды Puma Rebound",
                price = 4599, rating = 4.8, reviews = 1240, url = "https://www.wildberries.ru/catalog/1/detail.aspx",
            ),
            ShoppingProduct(
                id = "2", marketplace = Marketplace.WB, title = "Кеды Puma Smash",
                price = 3899, rating = 4.6, reviews = 320, url = "https://www.wildberries.ru/catalog/2/detail.aspx",
            ),
        )

        override fun shoppingMarketplaces(): Set<Marketplace> = Marketplace.entries.toSet()

        override suspend fun searchProducts(query: ShoppingQuery): ShoppingSearchResult {
            queries += query
            return ShoppingSearchResult(query, listOf(MarketplaceResult(Marketplace.WB, products = result)), result)
        }

        override suspend fun addToCart(product: ShoppingProduct, size: String?, quantity: Int): CartResult {
            cart += product.title to size
            return CartResult.Manual(product, "Открыла карточку — добавьте в корзину сами")
        }

        override suspend fun addToShoppingList(product: ai.nova.core.shopping.ShoppingProduct): Boolean {
            saved += product
            return true
        }

        override suspend fun openShoppingUrl(url: String): Boolean {
            opened += url
            return true
        }
    }

    private fun env(host: Host = Host()): Pair<TestEnv, Skills> {
        lateinit var skills: Skills
        val e = TestEnv(skillsFactory = { t -> Skills(host, null, t).also { skills = it } })
            .apply { settings = AssistantSettings(useAI = false) }
        return e to skills
    }

    @Test
    fun `находит кеды и показывает карточки`() = runTest {
        val host = Host()
        val (e, skills) = env(host)
        val out = assertIs<SkillOutcome.Say>(skills.handle("хочу кеды Puma 46 размер до 10 тысяч", e.settings, null))
        assertEquals(2, out.products.size)
        assertTrue(out.text.contains("Лучший"))
        assertFalse(out.text.contains("хочу"))
        assertEquals("46", host.queries.single().size)
        assertEquals(10_000, host.queries.single().priceTo)
    }

    @Test
    fun `без размера сначала спрашивает, потом ищет`() = runTest {
        val host = Host()
        val (e, skills) = env(host)
        val ask = assertIs<SkillOutcome.Say>(skills.handle("хочу кроссовки", e.settings, null))
        assertTrue(ask.followUp)
        assertTrue(ask.text.contains("размер", ignoreCase = true))
        assertTrue(host.queries.isEmpty())

        val out = assertIs<SkillOutcome.Say>(skills.handle("46", e.settings, null))
        assertEquals("46", host.queries.single().size)
        assertEquals(2, out.products.size)
    }

    @Test
    fun `корзина площадки недоступна — товар уходит в список покупок`() = runTest {
        val host = Host()
        val (e, skills) = env(host)
        skills.handle("хочу кеды Puma 46 размер до 10 тысяч", e.settings, null)
        val out = assertIs<SkillOutcome.Say>(skills.handle("положи первый в корзину", e.settings, null))
        assertEquals("Кеды Puma Rebound" to "46", host.cart.single())
        // Браузер сами не открываем: товар со ссылкой и ценой остаётся в списке покупок.
        assertTrue(host.opened.isEmpty())
        val line = host.saved.single()
        assertEquals("Кеды Puma Rebound", line.title)
        assertEquals(4599, line.price)
        assertEquals("https://www.wildberries.ru/catalog/1/detail.aspx", line.url)
        assertEquals(ai.nova.core.shopping.Marketplace.WB, line.marketplace)
        assertTrue(out.text.contains("список покупок"))
        assertEquals("https://www.wildberries.ru/catalog/1/detail.aspx", out.link)
    }

    @Test
    fun `оплату не делает даже если попросить`() = runTest {
        val host = Host()
        val (e, skills) = env(host)
        skills.handle("хочу кеды Puma 46 размер до 10 тысяч", e.settings, null)
        val out = assertIs<SkillOutcome.Say>(skills.handle("оплати заказ", e.settings, null))
        assertTrue(host.cart.isEmpty())
        assertTrue(out.text.contains("только вы") || out.text.contains("не делаю"))
    }

    @Test
    fun `без товаров оставляет ссылку на поиск, а не открывает браузер`() = runTest {
        val host = Host().apply { result = emptyList() }
        val (e, skills) = env(host)
        val out = assertIs<SkillOutcome.Say>(skills.handle("найди корм для кошки до 1000", e.settings, null))
        assertTrue(host.opened.isEmpty())
        assertTrue(out.products.isEmpty())
        // Ссылка остаётся в истории: человек откроет её сам, когда захочет.
        assertTrue(out.link!!.contains("wildberries.ru"))
        assertTrue(out.text.contains("по ссылке"))
    }

    @Test
    fun `через движок просьба доходит до покупок, а карточки остаются в истории`() = runTest {
        val host = Host()
        val (e, _) = env(host)
        e.settings = AssistantSettings(useAI = false)
        val reply = e.engine.handle("хочу кеды Puma 46 размер до 10 тысяч")
        assertEquals(2, reply.products.size)
        assertTrue(reply.text.contains("Лучший"))
        // Карточки хранятся вместе с сообщением: после перезапуска ссылка на товар остаётся в чате.
        val saved = e.store.conversations.recent(10).lastOrNull { it.role == MessageRole.ASSISTANT }
        assertNotNull(saved)
        val decoded = ai.nova.core.shopping.ShoppingCards.decode(saved.content)
        assertEquals(2, decoded.products.size)
        assertFalse(decoded.text.contains("#товар#"))
        assertTrue(decoded.products.first().url.startsWith("https://"))
    }

    @Test
    fun `служебные слова не попадают в запрос к площадке`() = runTest {
        val host = Host()
        val (e, skills) = env(host)
        skills.handle("сколько стоят кроссовки 42 размера", e.settings, null)
        assertEquals("кроссовки", host.queries.single().text)
    }

    @Test
    fun `сбой настроек площадок не оставляет без ответа`() = runTest {
        // Телефонный хост однажды уже падал на этом вызове: ответа не было вовсе.
        val host = object : Host() {
            override fun shoppingMarketplaces(): Set<Marketplace> = throw IllegalStateException("настройки недоступны")
        }
        val (e, skills) = env(host)
        val out = assertIs<SkillOutcome.Say>(skills.handle("хочу кеды Puma 46 размер до 10 тысяч", e.settings, null))
        assertEquals(2, out.products.size)
    }

    @Test
    fun `молчащий каталог не держит разговор`() = runTest {
        val host = object : Host() {
            override suspend fun searchProducts(query: ShoppingQuery): ShoppingSearchResult {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val (e, skills) = env(host)
        val out = assertIs<SkillOutcome.Say>(skills.handle("хочу кеды Puma 46 размер до 10 тысяч", e.settings, null))
        assertTrue(out.products.isEmpty())
        assertTrue(out.text.contains("не открылся") || out.text.contains("по ссылке"))
        assertTrue(host.opened.isEmpty())
        assertTrue(out.link!!.contains("wildberries.ru"))
    }

    @Test
    fun `просьба о покупке не превращается в статью`() = runTest {
        val (e, _) = env()
        assertIs<SkillCommand.Shopping>(SkillPhrases.parse("найди кеды puma 46 размер", e.time.today()))
        // Продукты и рецепты остаются за своими навыками.
        assertIs<SkillCommand.FindArticle>(SkillPhrases.parse("найди рецепт борща", e.time.today()))
        assertEquals(null, SkillPhrases.parse("купи хлеб", e.time.today()))
    }
}
