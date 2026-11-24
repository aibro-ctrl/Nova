package ai.nova.app.device

import ai.nova.core.assistant.PlaceCandidate
import ai.nova.core.assistant.PlaceKind
import ai.nova.core.assistant.Places
import ai.nova.core.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Ближайшие места по названию — через открытый справочник OpenStreetMap (Nominatim).
 *
 * Нужен ровно для одного: карты строят маршрут по координатам, а по слову только показывают список
 * («аптека» → результаты поиска). Поэтому место сначала находим сами — с уклоном в сторону того,
 * где человек сейчас, — а картам отдаём точку.
 *
 * Запрос уходит в интернет, как и любой поиск на карте. Правила справочника: не чаще одного запроса
 * в секунду и с честным именем приложения — поэтому ответы кэшируются на десять минут.
 */
class StreetPlaces(private val http: HttpClient) {

    private data class Hit(val at: Long, val list: List<PlaceCandidate>)

    private val cache = ConcurrentHashMap<String, Hit>()
    private val throttle = Mutex()
    private var lastCallAt = 0L

    suspend fun search(query: String, city: String?, here: Pair<Double, Double>?, limit: Int = 6): List<PlaceCandidate> {
        val q = clean(query)
        if (q.isBlank()) return emptyList()
        val at = here?.let { round(it.first) to round(it.second) }
        val key = "$q|${city.orEmpty().lowercase()}|${at?.first ?: ""},${at?.second ?: ""}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.list }
        val list = runCatching { request(q, city, here, limit) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Logger.w(TAG, "Поиск места «$q» не удался", it)
            emptyList()
        }
        if (list.isNotEmpty()) cache[key] = Hit(System.currentTimeMillis(), list)
        if (cache.size > 40) cache.entries.removeIf { System.currentTimeMillis() - it.value.at > TTL_MS }
        return list
    }

    private suspend fun request(query: String, city: String?, here: Pair<Double, Double>?, limit: Int): List<PlaceCandidate> {
        val text = if (city != null && !query.contains(city, ignoreCase = true)) "$query, $city" else query
        val url = buildString {
            append("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&accept-language=ru")
            append("&limit=").append(MAX_RESULTS)
            append("&q=").append(enc(text))
            if (here != null) {
                // Рамка вокруг человека: чужие города и одноимённые улицы в другой области не нужны.
                append("&viewbox=")
                append(here.second - BOX_LON).append(',').append(here.first + BOX_LAT).append(',')
                append(here.second + BOX_LON).append(',').append(here.first - BOX_LAT)
                append("&bounded=1")
            }
        }
        throttle.withLock {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCallAt)
            if (wait > 0) delay(wait)
            lastCallAt = System.currentTimeMillis()
        }
        val body = http.get(url) {
            header("User-Agent", AGENT)
            header("Accept", "application/json")
        }.bodyAsText()
        return Nominatim.places(body, Places.kind(query), here, limit)
    }

    private fun clean(text: String) = text.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}\s\-.,]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun round(v: Double) = Math.round(v * 100.0) / 100.0

    private companion object {
        const val TAG = "Nova/Places"
        const val AGENT = "Nova-Assistant/2.29.0 (Android; +https://github.com/aibro-ctrl/Nova)"
        const val TTL_MS = 10 * 60_000L
        const val MIN_INTERVAL_MS = 1_100L
        const val MAX_RESULTS = 20
        /** Половина рамки поиска: примерно 25 км на запад-восток и 33 км на север-юг. */
        const val BOX_LON = 0.35
        const val BOX_LAT = 0.30
    }
}

/**
 * Разбор ответа справочника: только точки с координатами, нужного вида и в разумной дали.
 * Отдельно от сети — чтобы правила отбора можно было проверить тестом.
 */
internal object Nominatim {

    fun places(body: String, kind: PlaceKind?, here: Pair<Double, Double>?, limit: Int = 6): List<PlaceCandidate> {
        val array = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        val parsed = array.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val lat = o.double("lat") ?: return@mapNotNull null
            val lon = o.double("lon") ?: return@mapNotNull null
            val tag = listOfNotNull(o.str("category"), o.str("type")).joinToString("/")
            val display = o.str("display_name").orEmpty()
            val name = o.str("name").orEmpty()
            PlaceCandidate(
                name = name.ifBlank { display.substringBefore(',') },
                address = shortAddress(o, name, display),
                lat = lat,
                lon = lon,
                meters = here?.let { distanceMeters(it.first, it.second, lat, lon) },
            ) to tag
        }
        val sorted = matching(parsed, kind).sortedBy { it.meters ?: Int.MAX_VALUE }
        // Совсем далёкое — уже не «ближайшее»: лучше честно сказать, что рядом не нашла.
        return sorted.filter { place -> place.meters?.let { it < MAX_METERS } ?: true }.take(limit)
    }

    /**
     * Свободный поиск по слову цепляет лишнее: по «аптеке» приходит и монумент с похожим адресом.
     * Держим только объекты нужного вида (теги OpenStreetMap), а если разметки нет — не фильтруем.
     */
    private fun matching(parsed: List<Pair<PlaceCandidate, String>>, kind: PlaceKind?): List<PlaceCandidate> {
        val tags = kind?.let { OSM[it] } ?: return parsed.map { it.first }
        val only = parsed.filter { (_, tag) -> tags.any { want -> tag == want || tag.startsWith("$want/") } }
        return (only.ifEmpty { parsed }).map { it.first }
    }

    /** Адрес без повторов названия: «улица Ленина, 5, Новосибирск» вместо простыни из справочника. */
    private fun shortAddress(o: JsonObject, name: String, display: String): String {
        val a = runCatching { o["address"]?.jsonObject }.getOrNull()
        val road = a?.str("road") ?: a?.str("pedestrian") ?: a?.str("square")
        val house = a?.str("house_number")
        val town = a?.str("city") ?: a?.str("town") ?: a?.str("village")
        val short = listOfNotNull(
            road?.let { if (house != null) "$it, $house" else it }
                ?: display.substringBefore(',').takeIf { it != name },
            town,
        ).joinToString(", ")
        return short.ifBlank { display }.take(90)
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.double(key: String): Double? =
        runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()

    /** Расстояние по прямой: для «ближайшего» достаточно, карты потом считают точный путь. */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return (r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toInt()
    }

    /** Вид места в разметке OpenStreetMap: «категория/тип» из ответа справочника. */
    private val OSM = mapOf(
        PlaceKind.FOOD to listOf("amenity/cafe", "amenity/restaurant", "amenity/fast_food", "amenity/food_court", "amenity/bar", "amenity/pub"),
        PlaceKind.COFFEE to listOf("amenity/cafe", "shop/coffee"),
        PlaceKind.PHARMACY to listOf("amenity/pharmacy", "shop/chemist"),
        PlaceKind.CLOTHES to listOf("shop/clothes", "shop/shoes", "shop/fashion", "shop/boutique"),
        PlaceKind.MALL to listOf("shop/mall", "shop/department_store"),
        PlaceKind.GROCERY to listOf("shop/supermarket", "shop/convenience", "shop/grocery", "shop/greengrocer"),
        PlaceKind.FUEL to listOf("amenity/fuel"),
        PlaceKind.BANK to listOf("amenity/bank"),
        PlaceKind.ATM to listOf("amenity/atm"),
        PlaceKind.CINEMA to listOf("amenity/cinema"),
        PlaceKind.HOTEL to listOf("tourism/hotel", "tourism/hostel", "tourism/guest_house"),
        PlaceKind.PARKING to listOf("amenity/parking"),
        PlaceKind.DENTIST to listOf("amenity/dentist", "amenity/clinic"),
        PlaceKind.MARKET to listOf("amenity/marketplace", "shop/market"),
    )

    private const val MAX_METERS = 40_000
}
