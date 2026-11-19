package ai.nova.core.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Где я?»: адрес по координатам — улица и ближайший дом (Nominatim). */
class WhereAmITest {
    private val withHouse = """{"display_name":"Баумана, 12, Казань, Татарстан, Россия","address":{
        "road":"улица Баумана","house_number":"12","suburb":"Вахитовский район","city":"Казань","state":"Татарстан","country":"Россия"}}"""

    @Test fun streetAndHouse() {
        assertEquals("улица Баумана, 12, Казань", ReverseGeoService.parse(withHouse))
    }

    @Test fun streetWithoutHouse() {
        val json = """{"address":{"pedestrian":"улица Баумана","city":"Казань"}}"""
        assertEquals("улица Баумана, Казань", ReverseGeoService.parse(json))
    }

    @Test fun districtWhenNoStreet() {
        val json = """{"address":{"suburb":"Вахитовский район","city":"Казань"}}"""
        assertEquals("Вахитовский район, Казань", ReverseGeoService.parse(json))
    }

    @Test fun fallsBackToDisplayName() {
        val json = """{"display_name":"Казань, Татарстан, Россия"}"""
        assertEquals("Казань, Татарстан, Россия", ReverseGeoService.parse(json))
    }

    @Test fun brokenJsonIsNull() {
        assertNull(ReverseGeoService.parse("не json"))
        assertNull(ReverseGeoService.parse("{}"))
    }
}
