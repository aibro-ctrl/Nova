package ai.nova.app.device

import ai.nova.core.assistant.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отбор мест из ответа справочника: без сети, на заранее записанном ответе. */
class StreetPlacesTest {

    private fun hit(
        name: String,
        category: String,
        type: String,
        lat: String,
        lon: String,
    ) = """
        {
          "lat": "$lat", "lon": "$lon", "name": "$name", "display_name": "$name, Новосибирск",
          "category": "$category", "type": "$type",
          "address": { "road": "улица Ленина", "house_number": "5", "city": "Новосибирск" }
        }
    """.trimIndent()

    @Test
    fun keepsOnlyTheAskedKind() {
        val body = "[${hit("Аптека Вита", "amenity", "pharmacy", "55.01", "82.90")}," +
            "${hit("Монумент славы", "historic", "monument", "55.02", "82.91")}]"

        val found = Nominatim.places(body, PlaceKind.PHARMACY, here = null)

        assertEquals(listOf("Аптека Вита"), found.map { it.name })
    }

    @Test
    fun keepsEverythingWhenTheKindIsUnknown() {
        val body = "[${hit("Аптека Вита", "amenity", "pharmacy", "55.01", "82.90")}," +
            "${hit("Монумент славы", "historic", "monument", "55.02", "82.91")}]"

        assertEquals(2, Nominatim.places(body, kind = null, here = null).size)
    }

    @Test
    fun fallsBackWhenNothingMatchesTheKind() {
        // Разметка бывает неполной: пустой ответ хуже, чем ближайшее похожее название.
        val body = "[${hit("Аптека на Ленина", "building", "yes", "55.01", "82.90")}]"

        assertEquals(listOf("Аптека на Ленина"), Nominatim.places(body, PlaceKind.PHARMACY, here = null).map { it.name })
    }

    @Test
    fun sortsByDistanceAndDropsFarAway() {
        val body = "[${hit("Далёкая", "amenity", "pharmacy", "56.50", "83.50")}," +
            "${hit("Ближняя", "amenity", "pharmacy", "55.001", "82.900")}," +
            "${hit("Средняя", "amenity", "pharmacy", "55.030", "82.900")}]"

        val found = Nominatim.places(body, PlaceKind.PHARMACY, here = 55.0 to 82.9)

        assertEquals(listOf("Ближняя", "Средняя"), found.map { it.name })
        assertTrue("метры считаются от телефона: ${found.first().meters}", (found.first().meters ?: 0) < 500)
    }

    @Test
    fun brokenAnswerIsNotACrash() {
        assertEquals(emptyList<String>(), Nominatim.places("не json", PlaceKind.PHARMACY, here = null).map { it.name })
    }
}
