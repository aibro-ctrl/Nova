package ai.nova.core.skills

import ai.nova.core.assistant.RuFormat
import ai.nova.core.data.NovaJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Точка на карте. */
data class GeoPoint(val lat: Double, val lon: Double, val name: String? = null)

/** Нет связи или источник не ответил — ответ «нет интернета», а не падение. */
class InfoUnavailable(message: String) : Exception(message)

/** Общие для «живых» ответов HTTP-запросы: таймаут, User-Agent, понятная ошибка. */
internal suspend fun HttpClient.fetchText(url: String, userAgent: String = NOVA_USER_AGENT): String {
    var last: Exception? = null
    // Мобильная сеть рвётся: один повтор после короткой паузы.
    repeat(2) { attempt ->
        val r: HttpResponse = try {
            get(url) {
                // Навыку не нужно ждать 90 секунд, как AI: нет ответа за 10 — «нет связи».
                if (this@fetchText.pluginOrNull(HttpTimeout) != null) timeout { connectTimeoutMillis = 5_000; requestTimeoutMillis = 10_000; socketTimeoutMillis = 10_000 }
                header("User-Agent", userAgent)
                header("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*")
                header("Accept-Language", "ru")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            // Таймаут не повторяем: второй раз ждать столько же бессмысленно.
            if (e is HttpRequestTimeoutException || e is java.net.SocketTimeoutException || e.cause is java.net.SocketTimeoutException) return@repeat
            if (attempt == 0) kotlinx.coroutines.delay(700)
            return@repeat
        }
        if (r.status.value in 500..599 && attempt == 0) { kotlinx.coroutines.delay(700); return@repeat }
        if (!r.status.isSuccess()) throw InfoUnavailable("источник ответил ${r.status.value}")
        // Ответ сервиса не должен раздуть память: читаем не больше MAX_BODY_BYTES (новости, погода и справка гораздо меньше).
        val declared = r.headers["Content-Length"]?.toLongOrNull() ?: 0L
        if (declared > MAX_BODY_BYTES) throw InfoUnavailable("источник ответил слишком большим файлом")
        val bytes = r.bodyAsBytes()
        if (bytes.size > MAX_BODY_BYTES) throw InfoUnavailable("источник ответил слишком большим файлом")
        return decodeBody(bytes, r.headers["Content-Type"])
    }
    // Причина в сообщении — по ней видно, что не так (нет сети, сертификат, таймаут).
    val cause = last?.let { e -> generateSequence<Throwable>(e) { it.cause }.last()::class.simpleName } ?: "нет ответа"
    throw InfoUnavailable("нет связи ($cause)")
}

/** Представляемся честно: сайты видят, кто их спрашивает. */
internal const val NOVA_USER_AGENT = "NovaAssistant/2.2 (Android; +https://github.com/aibro-ctrl/Nova)"

/**
 * Некоторые RSS-сервисы (Bing Новости) отдают настоящую ленту только браузеру,
 * а программе — HTML без новостей. Для них используем самый простой браузерный заголовок.
 */
internal const val BROWSER_USER_AGENT = "Mozilla/5.0"

/** Предел размера ответа сетевого навыка. */
internal const val MAX_BODY_BYTES = 3L * 1024 * 1024

/** Кодировка: из заголовка, из пролога XML («windows-1251»), иначе UTF-8. */
internal fun decodeBody(bytes: ByteArray, contentType: String?): String {
    val fromHeader = contentType?.let { Regex("""(?i)charset=["']?([\w-]+)""").find(it)?.groupValues?.get(1) }
    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
    val fromXml = Regex("""<\?xml[^>]*encoding=["']([\w-]+)["']""").find(head)?.groupValues?.get(1)
    val name = fromHeader ?: fromXml ?: "UTF-8"
    val cs = runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    return String(bytes, cs).removePrefix("\uFEFF")
}

/** Простой кеш на время: одинаковый вопрос подряд не ходит в сеть. */
internal class TtlCache<K, V>(private val ttlMillis: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val map = HashMap<K, Pair<Long, V>>()
    @Synchronized fun get(key: K): V? = map[key]?.takeIf { now() - it.first < ttlMillis }?.second
    /** Есть ли свежая запись (в том числе null — «не нашли», чтобы не спрашивать сеть снова). */
    @Synchronized fun contains(key: K): Boolean = map[key]?.let { now() - it.first < ttlMillis } == true
    @Synchronized fun put(key: K, value: V) { map[key] = now() to value; if (map.size > 64) map.clear() }
}

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonArray.numAt(i: Int): Double? = (getOrNull(i) as? JsonPrimitive)?.doubleOrNull

/** Вопрос о погоде. */
data class WeatherQuery(
    /** Город словами как сказано («в Казани»); null — где пользователь сейчас. */
    val place: String? = null,
    /** День; null — «сейчас». */
    val date: LocalDate? = null,
    val aspect: Aspect = Aspect.GENERAL,
) {
    enum class Aspect { GENERAL, RAIN, SNOW, UMBRELLA, WEEK, WIND, WARM }
}

/**
 * Погода из нескольких источников: если один заблокирован в стране или недоступен, берём следующий.
 *  - MET Norway (met.no) — основной: без ключа, 7+ дней, доступен там, где Open-Meteo заблокирован;
 *  - Open-Meteo — резервный (геокодер и прогноз);
 *  - wttr.in — последний резерв: сам находит город и отдаёт 3 дня.
 * Недоступный источник пропускается 10–30 минут, чтобы каждый вопрос не ждал таймаут.
 */
class WeatherService(private val http: HttpClient) {
    data class Place(val name: String, val lat: Double, val lon: Double, val country: String? = null)
    data class Day(val date: LocalDate, val code: Int, val min: Double, val max: Double, val rainChance: Int?, val wind: Double?)
    data class Forecast(val temp: Double?, val feels: Double?, val code: Int?, val wind: Double?, val days: List<Day>)

    private val geoCache = TtlCache<String, Place?>(24 * 3600_000L)
    private val forecastCache = TtlCache<String, Forecast>(15 * 60_000L)

    /** До какого времени источник считается недоступным. */
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun available(source: String, now: Long) = (downUntil[source] ?: 0L) <= now
    private fun markDown(source: String, now: Long, ttl: Long) { downUntil[source] = now + ttl }

    /** Ищет город; падежи («в Казани», «в Нижнем Новгороде») пробуем снять. */
    suspend fun findPlace(spoken: String): Place? {
        val key = spoken.lowercase().trim()
        if (geoCache.contains(key)) return geoCache.get(key)
        val alias = ALIASES[key.replace('ё', 'е').trim('.', ' ')]
        for (candidate in listOfNotNull(alias) + nameCandidates(spoken)) {
            val best = geocode(candidate)
            if (best != null) { geoCache.put(key, best); return best }
        }
        geoCache.put(key, null)
        return null
    }

    private suspend fun geocode(candidate: String): Place? {
        val now = System.currentTimeMillis()
        // 1. Open-Meteo (русские названия, страна в ответе).
        if (available(OPEN_METEO_GEO, now)) {
            try {
                val url = "https://geocoding-api.open-meteo.com/v1/search?name=${candidate.encodeURLParameter()}&count=10&language=ru&format=json"
                pickPlace(parsePlaces(http.fetchText(url)), candidate)?.let { return it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                markDown(OPEN_METEO_GEO, now, GEO_RETRY_MS)
            }
        }
        // 2. OpenStreetMap Nominatim: работает без ключа, есть страна (для приоритета российских городов).
        if (available(NOMINATIM, now)) {
            try {
                val url = "https://nominatim.openstreetmap.org/search?q=${candidate.encodeURLParameter()}&format=jsonv2&limit=8&accept-language=ru&addressdetails=1"
                pickPlace(parseNominatim(http.fetchText(url)), candidate)?.let { return it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                markDown(NOMINATIM, now, GEO_RETRY_MS)
            }
        }
        // 3. wttr.in: сам находит ближайший город по названию.
        if (available(WTTR, now)) {
            try {
                wttrPlace(candidate)?.let { return it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                markDown(WTTR, now, GEO_RETRY_MS)
            }
        }
        return null
    }

    suspend fun forecast(lat: Double, lon: Double): Forecast {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        forecastCache.get(key)?.let { return it }
        val now = System.currentTimeMillis()
        var last: Exception? = null
        for (source in FORECAST_SOURCES) {
            if (!available(source, now)) continue
            try {
                val f = when (source) {
                    MET_NO -> parseMetNo(http.fetchText(metNoUrl(lat, lon)))
                    OPEN_METEO -> parseForecast(http.fetchText(openMeteoUrl(lat, lon)))
                    else -> parseWttr(http.fetchText(wttrUrl(lat, lon)))
                }
                if (f.days.isEmpty() && f.temp == null) throw InfoUnavailable("источник не дал прогноз")
                forecastCache.put(key, f)
                return f
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Заблокированный или упавший источник не должен тормозить следующие вопросы.
                markDown(source, now, FORECAST_RETRY_MS)
                last = e
            }
        }
        throw when (last) {
            is InfoUnavailable -> last
            null -> InfoUnavailable("нет связи (все источники недоступны)")
            else -> InfoUnavailable("нет связи (${last.message ?: last::class.simpleName})")
        }
    }

    private fun metNoUrl(lat: Double, lon: Double) =
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${"%.4f".format(Locale.US, lat)}&lon=${"%.4f".format(Locale.US, lon)}"

    private fun openMeteoUrl(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=7&wind_speed_unit=ms"

    private fun wttrUrl(lat: Double, lon: Double) =
        "https://wttr.in/${"%.4f".format(Locale.US, lat)},${"%.4f".format(Locale.US, lon)}?format=j1&lang=ru"

    private suspend fun wttrPlace(name: String): Place? {
        val url = "https://wttr.in/${name.encodeURLParameter()}?format=j1&lang=ru"
        return parseWttrPlace(http.fetchText(url), name)
    }

    companion object {
        private val ru = Locale("ru")
        /** «20 ноября» — месяц всегда в родительном падеже, независимо от данных локали в прошивке. */
        private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
        private fun dayMonth(date: LocalDate) = "${date.dayOfMonth} ${MONTHS_GEN[date.monthValue - 1]}"
        private val weekdayShort = DateTimeFormatter.ofPattern("EEEE", ru)

        /** Разговорные названия городов. */
        private val ALIASES = mapOf(
            "питер" to "Санкт-Петербург", "питере" to "Санкт-Петербург", "спб" to "Санкт-Петербург", "петербурге" to "Санкт-Петербург",
            "мск" to "Москва", "екб" to "Екатеринбург", "екате" to "Екатеринбург", "нск" to "Новосибирск", "новосибе" to "Новосибирск",
        )

        private const val MET_NO = "met.no"
        private const val OPEN_METEO = "open-meteo"
        private const val OPEN_METEO_GEO = "open-meteo-geo"
        private const val NOMINATIM = "nominatim"
        private const val WTTR = "wttr"

        /** Порядок источников прогноза. */
        private val FORECAST_SOURCES = listOf(MET_NO, OPEN_METEO, WTTR)

        private const val FORECAST_RETRY_MS = 10 * 60_000L
        private const val GEO_RETRY_MS = 30 * 60_000L

        /**
         * Вероятность осадков по сумме за сутки (met.no её не отдаёт).
         * Оценка нужна только чтобы ответ звучал по-человечески, а не как точный прогноз.
         */
        fun rainChance(mm: Double, code: Int): Int = when {
            mm >= 5 -> 95
            mm >= 2 -> 88
            mm >= 0.5 -> 75
            mm >= 0.1 -> 55
            mm > 0.01 -> 45
            isRain(code) || isSnow(code) -> 40
            else -> 0
        }

        /** Символы met.no («heavyrainshowers_day») → коды ВМО. */
        fun symbolToWmo(symbol: String): Int {
            val s = symbol.substringBefore('_')
            return when {
                s == "clearsky" -> 0
                s == "fair" -> 1
                s == "partlycloudy" -> 2
                s == "cloudy" -> 3
                s == "fog" -> 45
                s.contains("thunder") && s.contains("snow") -> 96
                s.contains("thunder") && s.contains("sleet") -> 96
                s.contains("thunder") -> 95
                s.startsWith("heavysnow") && s.contains("showers") -> 86
                s.startsWith("lightsnow") && s.contains("showers") -> 85
                s.startsWith("snow") && s.contains("showers") -> 85
                s == "heavysnow" -> 75
                s == "lightsnow" -> 71
                s == "snow" -> 73
                s == "heavysleet" -> 67
                s == "sleet" || s == "lightsleet" -> 66
                s.startsWith("heavyrain") && s.contains("showers") -> 82
                s.startsWith("lightrain") && s.contains("showers") -> 80
                s.startsWith("rain") && s.contains("showers") -> 81
                s == "heavyrain" -> 65
                s == "lightrain" -> 61
                s == "rain" -> 63
                s.contains("rain") -> 61
                s.contains("snow") -> 71
                s.contains("sleet") -> 66
                else -> 1
            }
        }

        /** Коды wttr.in (World Weather Online) → коды ВМО. */
        fun wwoToWmo(code: Int): Int = when (code) {
            113 -> 0
            116 -> 2
            119, 122 -> 3
            143, 248, 260 -> 45
            176, 263, 266, 281, 284, 293 -> 61
            296, 299, 353 -> 63
            302, 305, 308, 356, 359 -> 65
            179, 182, 185, 317, 320, 362, 365, 368, 371, 374, 377 -> 66
            311, 314, 350 -> 67
            227, 230, 323, 326, 329, 332, 335, 338 -> 73
            386, 389, 392, 395 -> 95
            else -> 3
        }

        fun parseNominatim(json: String): List<Place> {
            val arr = runCatching { NovaJson.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.text("name")?.takeIf { it.isNotBlank() }
                    ?: o.text("display_name")?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val lat = o.text("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.text("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                val country = (o["address"] as? JsonObject)?.text("country_code")?.uppercase()
                Place(name, lat, lon, country)
            }
        }

        fun parseWttrPlace(json: String, candidate: String): Place? {
            val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val area = root.arr("nearest_area")?.firstOrNull() as? JsonObject ?: return null
            val name = (area.arr("areaName")?.firstOrNull() as? JsonObject)?.text("value") ?: return null
            val lat = area.text("latitude")?.toDoubleOrNull() ?: return null
            val lon = area.text("longitude")?.toDoubleOrNull() ?: return null
            val country = (area.arr("country")?.firstOrNull() as? JsonObject)?.text("value")
            val cc = if (country != null && (country.contains("Россия", true) || country.contains("Russia", true))) "RU" else null
            return pickPlace(listOf(Place(name, lat, lon, cc)), candidate)
        }

        /** compact-формат MET Norway: почасовые точки на несколько дней вперёд. */
        fun parseMetNo(json: String, zone: ZoneId = ZoneId.systemDefault()): Forecast {
            val root = NovaJson.parseToJsonElement(json).jsonObject
            val series = (root["properties"] as? JsonObject)?.arr("timeseries") ?: return Forecast(null, null, null, null, emptyList())
            class Acc {
                var min: Double? = null
                var max: Double? = null
                var wind: Double? = null
                var precip: Double = 0.0
                var code: Int? = null
                var bestHour: Int = -1
            }
            val accs = LinkedHashMap<LocalDate, Acc>()
            var curTemp: Double? = null
            var curWind: Double? = null
            var curCode: Int? = null
            series.forEachIndexed { index, el ->
                val entry = el as? JsonObject ?: return@forEachIndexed
                val time = (entry["time"] as? JsonPrimitive)?.contentOrNull
                val at = time?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() } ?: return@forEachIndexed
                val data = entry["data"] as? JsonObject ?: return@forEachIndexed
                val details = (data["instant"] as? JsonObject)?.get("details") as? JsonObject
                val temp = details?.num("air_temperature")
                val windMs = details?.num("wind_speed")
                val oneHour = data["next_1_hours"] as? JsonObject
                val sixHours = data["next_6_hours"] as? JsonObject
                val symbol = (oneHour?.get("summary") as? JsonObject)?.text("symbol_code")
                    ?: (sixHours?.get("summary") as? JsonObject)?.text("symbol_code")
                val code = symbol?.let { symbolToWmo(it) }
                val mm = (oneHour?.get("details") as? JsonObject)?.num("precipitation_amount")
                    ?: ((sixHours?.get("details") as? JsonObject)?.num("precipitation_amount")?.div(6.0))
                if (index == 0) {
                    curTemp = temp
                    curWind = windMs
                    curCode = code
                }
                val acc = accs.getOrPut(at.toLocalDate()) { Acc() }
                temp?.let {
                    acc.min = minOf(acc.min ?: it, it)
                    acc.max = maxOf(acc.max ?: it, it)
                }
                windMs?.let { acc.wind = maxOf(acc.wind ?: it, it) }
                acc.precip += mm ?: 0.0
                val hour = at.hour
                if (code != null && (acc.bestHour < 0 || kotlin.math.abs(hour - 12) < kotlin.math.abs(acc.bestHour - 12))) {
                    acc.code = code
                    acc.bestHour = hour
                }
            }
            val days = accs.entries.take(7).mapNotNull { (date, acc) ->
                val min = acc.min ?: return@mapNotNull null
                val max = acc.max ?: min
                val code = acc.code ?: 3
                Day(date, code, min, max, rainChance(acc.precip, code), acc.wind)
            }
            return Forecast(curTemp, null, curCode, curWind, days)
        }

        /** j1-формат wttr.in: 3 дня, текущая погода, ветер в км/ч. */
        fun parseWttr(json: String): Forecast {
            val root = NovaJson.parseToJsonElement(json).jsonObject
            val cur = root.arr("current_condition")?.firstOrNull() as? JsonObject
            val temp = cur?.text("temp_C")?.toDoubleOrNull()
            val feels = cur?.text("FeelsLikeC")?.toDoubleOrNull()
            val code = cur?.text("weatherCode")?.toIntOrNull()?.let { wwoToWmo(it) }
            val wind = cur?.text("windspeedKmph")?.toDoubleOrNull()?.div(3.6)
            val days = root.arr("weather").orEmpty().take(7).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val date = o.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val min = o.text("mintempC")?.toDoubleOrNull() ?: return@mapNotNull null
                val max = o.text("maxtempC")?.toDoubleOrNull() ?: min
                val hours = o.arr("hourly").orEmpty().mapNotNull { it as? JsonObject }
                val noon = hours.firstOrNull { it.text("time") == "1200" } ?: hours.getOrNull(hours.size / 2)
                val dayCode = noon?.text("weatherCode")?.toIntOrNull()?.let { wwoToWmo(it) } ?: 3
                val chance = noon?.text("chanceofrain")?.toIntOrNull()
                    ?: hours.mapNotNull { it.text("chanceofrain")?.toIntOrNull() }.maxOrNull() ?: 0
                val maxWind = hours.mapNotNull { it.text("windspeedKmph")?.toDoubleOrNull() }.maxOrNull()?.div(3.6)
                Day(date, dayCode, min, max, chance, maxWind)
            }
            return Forecast(temp, feels, code, wind, days)
        }

        fun parsePlaces(json: String): List<Place> {
            val root = NovaJson.parseToJsonElement(json).jsonObject
            return root.arr("results")?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Place(o.text("name") ?: return@mapNotNull null, o.num("latitude") ?: return@mapNotNull null, o.num("longitude") ?: return@mapNotNull null, o.text("country_code"))
            } ?: emptyList()
        }

        /** Город, чьё название начинается с основы сказанного; российские — в приоритете. */
        fun pickPlace(found: List<Place>, candidate: String): Place? {
            val stem = candidate.lowercase().replace('ё', 'е').take(maxOf(3, candidate.length - 2))
            // Ничего похожего — не подставляем случайную деревню («погода в эти…» → «Эти»).
            val matching = found.filter { it.name.lowercase().replace('ё', 'е').startsWith(stem) }
            return matching.firstOrNull { it.country == "RU" } ?: matching.firstOrNull()
        }

        /** «Казани» → «Казань», «Казани», «Казан»; «Нижнем Новгороде» → «Нижний Новгород»… */
        fun nameCandidates(spoken: String): List<String> {
            val s = spoken.trim().replace(Regex("""\s+"""), " ")
            val words = s.split(' ', '-').filter { it.isNotEmpty() }
            val out = LinkedHashSet<String>()
            fun nominative(w: String): String {
                val l = w.lowercase()
                val base = when {
                    l.endsWith("ем") && l.length > 4 -> w.dropLast(2) + "ий" // Нижнем → Нижний
                    l.endsWith("ой") && l.length > 4 -> w.dropLast(2) + "ая" // Белой → Белая
                    l.endsWith("ом") && l.length > 4 -> w.dropLast(2) // Новгородом → Новгород
                    l.endsWith("ске") || l.endsWith("ге") || l.endsWith("де") || l.endsWith("ре") || l.endsWith("не") || l.endsWith("те") || l.endsWith("ле") && !l.endsWith("еле") -> w.dropLast(1) // Омске → Омск
                    l.endsWith("ве") || l.endsWith("ке") -> w.dropLast(1) + "а" // Москве → Москва, Уфе…
                    l.endsWith("и") && l.length > 4 -> w.dropLast(1) + "ь" // Казани → Казань
                    l.endsWith("е") && l.length >= 3 -> w.dropLast(1) + "а" // Уфе → Уфа
                    l.endsWith("у") && l.length > 3 -> w.dropLast(1) + "а" // в Москву
                    else -> w
                }
                return base
            }
            out += words.joinToString(" ") { nominative(it) }.let { if (s.contains('-')) it.replace(' ', '-') else it }
            out += words.joinToString(" ") { nominative(it) }
            // Как сказали — на случай названий, где догадка о падеже не сработала («Сочи»).
            out += s
            // Беглая гласная: «в Орле» → «Орел», «в Пскове» уже есть выше.
            if (words.size == 1 && Regex("""(?iu)[бвгджзклмнпрстфхцчшщ]ле$""").containsMatchIn(s)) out += s.dropLast(2) + "ел"
            // Основа слова: геокодер ищет по началу названия.
            out += words.joinToString(" ") { w -> if (w.length > 5) w.dropLast(2) else if (w.length > 3) w.dropLast(1) else w }
            return out.filter { it.length >= 2 }.take(4)
        }

        fun parseForecast(json: String): Forecast {
            val root = NovaJson.parseToJsonElement(json).jsonObject
            val cur = root["current"] as? JsonObject
            val daily = root["daily"] as? JsonObject
            val days = ArrayList<Day>()
            if (daily != null) {
                val dates = daily.arr("time") ?: JsonArray(emptyList())
                for (i in dates.indices) {
                    val date = runCatching { LocalDate.parse((dates[i] as JsonPrimitive).content) }.getOrNull() ?: continue
                    days += Day(
                        date = date,
                        code = daily.arr("weather_code")?.numAt(i)?.toInt() ?: 0,
                        min = daily.arr("temperature_2m_min")?.numAt(i) ?: continue,
                        max = daily.arr("temperature_2m_max")?.numAt(i) ?: continue,
                        rainChance = daily.arr("precipitation_probability_max")?.numAt(i)?.roundToInt(),
                        wind = daily.arr("wind_speed_10m_max")?.numAt(i),
                    )
                }
            }
            return Forecast(cur?.num("temperature_2m"), cur?.num("apparent_temperature"), (cur?.get("weather_code") as? JsonPrimitive)?.intOrNull, cur?.num("wind_speed_10m"), days)
        }

        /** Коды погоды ВМО → слова. */
        fun describe(code: Int): String = when (code) {
            0 -> "ясно"
            1 -> "в основном ясно"
            2 -> "переменная облачность"
            3 -> "пасмурно"
            45, 48 -> "туман"
            51, 53, 55 -> "морось"
            56, 57 -> "ледяная морось"
            61 -> "небольшой дождь"
            63 -> "дождь"
            65 -> "сильный дождь"
            66, 67 -> "ледяной дождь"
            71 -> "небольшой снег"
            73 -> "снег"
            75 -> "сильный снег"
            77 -> "снежная крупа"
            80 -> "небольшой ливень"
            81 -> "ливень"
            82 -> "сильный ливень"
            85 -> "небольшой снегопад"
            86 -> "сильный снегопад"
            95 -> "гроза"
            96, 99 -> "гроза с градом"
            else -> "без осадков"
        }

        fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95
        fun isSnow(code: Int) = code in 71..77 || code in 85..86

        fun deg(t: Double): String {
            val r = t.roundToInt()
            return when {
                r > 0 -> "+$r°"
                r < 0 -> "−${-r}°"
                else -> "0°"
            }
        }

        private fun wind(w: Double?): String? = w?.roundToInt()?.takeIf { it >= 1 }?.let { "ветер $it м/с" }

        /** Текст ответа по готовому прогнозу. */
        fun answer(q: WeatherQuery, placeName: String?, f: Forecast, today: LocalDate): String {
            val where = placeName?.let { "$it: " } ?: ""
            val whereIn = placeName?.let { " ($it)" } ?: ""
            val day = q.date?.let { d -> f.days.firstOrNull { it.date == d } }
            if (q.date != null && day == null) return "Прогноз есть только на неделю вперёд — на ${RuFormat.date(q.date, today)} пока не знаю."
            val target = day ?: f.days.firstOrNull { it.date == today }
            val dayWord = q.date?.let { RuFormat.date(it, today) } ?: "сегодня"
            when (q.aspect) {
                WeatherQuery.Aspect.RAIN, WeatherQuery.Aspect.UMBRELLA -> {
                    // «Идёт ли сейчас дождь» — сначала то, что за окном.
                    if (q.date == null && f.code != null && isRain(f.code)) {
                        return "Да, сейчас$whereIn ${describe(f.code)}. ${if (q.aspect == WeatherQuery.Aspect.UMBRELLA) "Зонт лучше взять." else "Возьмите зонт."}"
                    }
                    val d = target ?: return "${where}не удалось получить прогноз."
                    val chance = d.rainChance ?: 0
                    val rainy = isRain(d.code) || chance >= 50
                    return when {
                        rainy -> "Да, $dayWord$whereIn вероятен дождь — $chance%. ${if (q.aspect == WeatherQuery.Aspect.UMBRELLA) "Зонт лучше взять." else "Возьмите зонт."}"
                        chance >= 30 -> "Возможно: $dayWord$whereIn вероятность дождя $chance%. Зонт на всякий случай не помешает."
                        isSnow(d.code) -> "Дождя не будет, но $dayWord$whereIn ожидается ${describe(d.code)}."
                        else -> "Нет, $dayWord$whereIn дождя не ожидается${if (chance > 0) " (вероятность $chance%)" else ""}. ${if (q.aspect == WeatherQuery.Aspect.UMBRELLA) "Зонт не нужен." else ""}".trim()
                    }
                }
                WeatherQuery.Aspect.SNOW -> {
                    val d = target ?: return "${where}не удалось получить прогноз."
                    return if (isSnow(d.code)) "Да, $dayWord$whereIn ожидается ${describe(d.code)}." else "Нет, $dayWord$whereIn снега не ожидается: ${describe(d.code)}, от ${deg(d.min)} до ${deg(d.max)}."
                }
                WeatherQuery.Aspect.WEEK -> {
                    val lines = f.days.take(7).map { d ->
                        val name = when (d.date) { today -> "Сегодня"; today.plusDays(1) -> "Завтра"; else -> weekdayShort.format(d.date).replaceFirstChar { it.uppercase() } + ", " + dayMonth(d.date) }
                        "$name: ${deg(d.min)}…${deg(d.max)}, ${describe(d.code)}${d.rainChance?.takeIf { it >= 40 }?.let { " ($it%)" } ?: ""}"
                    }
                    return "Погода на неделю${whereIn}:\n" + lines.joinToString("\n")
                }
                else -> Unit
            }
            // Сейчас.
            if (q.date == null) {
                val parts = ArrayList<String>()
                f.temp?.let { t ->
                    var s = "сейчас ${deg(t)}"
                    f.code?.let { s += ", ${describe(it)}" }
                    f.feels?.let { fl -> if (kotlin.math.abs(fl - t) >= 3) s += ", ощущается как ${deg(fl)}" }
                    wind(f.wind)?.let { s += ", $it" }
                    parts += s
                }
                target?.let { d ->
                    var s = "днём до ${deg(d.max)}, ночью до ${deg(d.min)}"
                    d.rainChance?.takeIf { it >= 40 }?.let { s += ", вероятность осадков $it%" }
                    parts += s
                }
                if (parts.isEmpty()) return "${where}не удалось получить прогноз."
                return where + parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "." 
            }
            val d = target ?: return "${where}не удалось получить прогноз."
            val head = "${dayWord.replaceFirstChar { it.uppercase() }}, ${dayMonth(d.date)}$whereIn"
            var body = "от ${deg(d.min)} до ${deg(d.max)}, ${describe(d.code)}"
            d.rainChance?.takeIf { it >= 20 }?.let { body += ", вероятность осадков $it%" }
            wind(d.wind)?.let { body += ", $it" }
            val advice = when {
                isRain(d.code) || (d.rainChance ?: 0) >= 60 -> " Зонт пригодится."
                d.max <= -10 -> " Одевайтесь теплее."
                d.max >= 28 -> " Будет жарко — не забудьте воду."
                else -> ""
            }
            return "$head: $body.$advice"
        }
    }
}
