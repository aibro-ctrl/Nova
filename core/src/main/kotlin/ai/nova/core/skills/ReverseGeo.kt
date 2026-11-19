package ai.nova.core.skills

import ai.nova.core.data.NovaJson
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Адрес по координатам: улица и ближайший дом. Нужно на вопрос «где я?» — чтобы человек,
 * который потерялся, мог продиктовать адрес спасателям или прохожему.
 */
class ReverseGeoService(private val http: HttpClient) {
    /** «улица Баумана, 12, Казань» — null, если адрес не определился. */
    suspend fun address(lat: Double, lon: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=jsonv2&zoom=18&addressdetails=1&accept-language=ru"
        return parse(http.fetchText(url))
    }

    companion object {
        /** Разбор ответа Nominatim. Отдельно от сети — чтобы проверять тестами. */
        fun parse(json: String): String? {
            val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val a = root["address"] as? JsonObject
            if (a != null) {
                val road = a.str("road") ?: a.str("pedestrian") ?: a.str("footway") ?: a.str("path") ?: a.str("residential")
                val house = a.str("house_number")
                val city = a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("municipality") ?: a.str("state")
                val district = a.str("suburb") ?: a.str("city_district") ?: a.str("district")
                val parts = ArrayList<String>()
                if (road != null) parts += listOfNotNull(road, house).joinToString(", ")
                if (parts.isEmpty()) (district ?: city)?.let { parts += it }
                if (city != null && parts.none { it == city }) parts += city
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
            root.str("display_name")?.let { return it }
            return null
        }

        private fun JsonObject.str(key: String): String? = (this[key])?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
