package ai.nova.core.shopping

import ai.nova.core.skills.InfoUnavailable
import ai.nova.core.skills.fetchText
import ai.nova.core.util.Logger
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Поиск товаров на площадке. Каждая площадка отвечает сама за себя: её сбой не отменяет остальные.
 */
interface ShoppingProvider {
    val marketplace: Marketplace

    /** Что площадка умеет на самом деле — это же видно в настройках. */
    val status: IntegrationStatus

    suspend fun search(query: ShoppingQuery, limit: Int): MarketplaceResult
}

/**
 * Wildberries: открытый каталог, который отдаёт тот же сайт магазина.
 *
 * Разбор намеренно осторожный: любое неожиданное поле — не падение, а пропуск товара. Цены у площадки
 * в копейках, поэтому делим на 100; чего в ответе нет, то и остаётся null — придумывать нельзя.
 */
class WildberriesProvider(private val http: HttpClient?) : ShoppingProvider {

    override val marketplace = Marketplace.WB
    override val status = IntegrationStatus.SEARCH_ONLY

    override suspend fun search(query: ShoppingQuery, limit: Int): MarketplaceResult {
        val client = http ?: return MarketplaceResult(marketplace, error = "нет доступа к сети")
        val text = query.searchText()
        if (text.isBlank()) return MarketplaceResult(marketplace, error = "пустой запрос")
        val url = SEARCH_URL + text.encodeURLParameter()
        return try {
            val body = client.fetchText(url)
            val products = parse(body, limit)
            if (products.isEmpty()) {
                MarketplaceResult(marketplace, error = null)
            } else {
                MarketplaceResult(marketplace, products = products)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Каталог закрыт для этого IP или ответил иначе — это факт, о котором говорим прямо.
            Logger.w(TAG, "Wildberries не ответил: ${e.message}")
            MarketplaceResult(marketplace, error = reason(e))
        }
    }

    private fun reason(e: Exception): String = when {
        e is InfoUnavailable -> e.message ?: "каталог недоступен"
        e is java.net.UnknownHostException -> "нет связи"
        e is java.net.SocketTimeoutException -> "не ответил вовремя"
        else -> "каталог недоступен"
    }

    /** Разбор ответа каталога: поддерживаем обе известные формы — с обёрткой `data` и без неё. */
    fun parse(json: String, limit: Int): List<ShoppingProduct> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val list = root.array("products") ?: (root["data"] as? JsonObject)?.array("products") ?: return emptyList()
        val out = ArrayList<ShoppingProduct>(minOf(limit, 30))
        for (element in list) {
            val item = element as? JsonObject ?: continue
            if (out.size >= minOf(limit, 30)) break
            productOf(item)?.let { out += it }
        }
        return out
    }

    private fun productOf(item: JsonObject): ShoppingProduct? {
        val id = item.long("id") ?: item.long("nmId") ?: return null
        val title = item.text("name") ?: item.text("imt_name") ?: return null
        val price = priceOf(item)
        val sizes = sizesOf(item)
        return ShoppingProduct(
            id = id.toString(),
            marketplace = marketplace,
            title = title,
            price = price,
            oldPrice = (item.int("priceU") ?: item.int("basicPriceU"))?.div(100)?.takeIf { price != null && it > price },
            rating = item.double("reviewRating") ?: item.double("rating") ?: item.double("nmReviewRating"),
            reviews = item.int("feedbacks") ?: item.int("nmFeedbacks"),
            brand = item.text("brand"),
            imageUrl = imageUrl(id),
            url = "https://www.wildberries.ru/catalog/$id/detail.aspx",
            sizes = sizes,
            // Список размеров у площадки — это то, что можно купить: пусто — наличие неизвестно.
            available = if (sizes.isEmpty()) null else true,
            seller = item.text("supplier") ?: item.text("seller"),
        )
    }

    /** Цена в рублях: сейчас — `salePriceU`, иначе из размера, иначе обычная. Отдаётся в копейках. */
    private fun priceOf(item: JsonObject): Int? {
        item.int("salePriceU")?.let { return it / 100 }
        item.int("priceU")?.let { return it / 100 }
        val sizes = item["sizes"] as? JsonArray ?: return null
        return sizes.filterIsInstance<JsonObject>().firstNotNullOfOrNull { size ->
            val price = size["price"] as? JsonObject ?: return@firstNotNullOfOrNull null
            price.int("total")?.div(100) ?: price.int("sale")?.div(100)
        }
    }

    private fun sizesOf(item: JsonObject): List<String> =
        (item["sizes"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { it.text("origName") ?: it.text("name") ?: it.text("tech_size") }
            .filter { it.isNotBlank() && it != "0" }
            .distinct()

    /** Адрес обложки: он собирается из идентификатора товара. Без картинки карточка тоже остаётся рабочей. */
    private fun imageUrl(id: Long): String {
        val vol = id / 100_000
        val part = id / 1_000
        return "https://basket-$vol.wbbasket.ru/vol$vol/part$part/$id/images/big/1.webp"
    }

    private companion object {
        const val TAG = "ShoppingWB"
        const val SEARCH_URL = "https://search.wb.ru/exactmatch/ru/common/v4/search" +
            "?appType=1&curr=rub&dest=-1257786&resultset=catalog&spp=30&suppressSpellcheck=false&query="
    }
}

/**
 * Ozon: публичного каталога для сторонних приложений нет, а сайт закрыт от автоматических запросов.
 * Поэтому честная роль площадки — открыть поиск, где человек сам всё видит. Разовые попытки получить
 * страницу делают короче только ответ, а не результат, и выдают мусор вместо товаров.
 */
class OzonProvider : ShoppingProvider {
    override val marketplace = Marketplace.OZON
    override val status = IntegrationStatus.SEARCH_ONLY

    override suspend fun search(query: ShoppingQuery, limit: Int): MarketplaceResult =
        MarketplaceResult(
            marketplace,
            status = IntegrationStatus.SEARCH_ONLY,
            error = "каталог Ozon не отдаётся приложениям — открываю поиск в приложении Ozon",
        )
}

/**
 * Параллельный поиск по всем площадкам сразу: медленная не задерживает быструю, ошибка одной
 * не отменяет другие.
 */
class ShoppingSearchService(private val http: HttpClient?) {

    private val providers: List<ShoppingProvider> = listOf(WildberriesProvider(http), OzonProvider())

    suspend fun search(query: ShoppingQuery): ShoppingSearchResult {
        val wanted = providers.filter { query.marketplaces.isEmpty() || it.marketplace in query.marketplaces }
        if (wanted.isEmpty()) return ShoppingSearchResult(query, emptyList())
        val results = coroutineScope {
            wanted.map { provider -> async { provider.search(query, PER_MARKET) } }.awaitAll()
        }
        return ShoppingSearchResult(query = query, results = results, products = results.flatMap { it.products })
    }

    fun statuses(): Map<Marketplace, IntegrationStatus> = providers.associate { it.marketplace to it.status }

    private companion object {
        /** Больше с одной площадки не нужно: отбор всё равно оставляет десять. */
        const val PER_MARKET = 30
    }
}

// ---------------------------------------------------------------------- Разбор JSON без падений

private fun JsonObject.array(name: String): JsonArray? = (this[name] as? JsonArray)?.takeIf { it.isNotEmpty() }

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull?.toLong() ?: it.doubleOrNull?.toLong() }

private fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }?.takeIf { it > 0 }
