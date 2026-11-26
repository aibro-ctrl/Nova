package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 3.0.1: короткая сводка разговора и маршрут до названного места.
 *
 * Человек спросил «о чём мы говорили сегодня» и получил перечень из восьми реплик со временем —
 * слушать это невозможно. И «построй маршрут до торгового центра Галерея» открывало поиск карт:
 * справочник не понимает падежи, поэтому место искали фразой целиком.
 */
class Hotfix30Test {

    private class NoPlaces : DeviceController {
        val searched = mutableListOf<String>()
        val performed = mutableListOf<DeviceCommand>()

        override suspend fun perform(command: DeviceCommand): DeviceResult {
            performed += command
            return DeviceResult("Ищу «${(command as? DeviceCommand.Navigate)?.destination.orEmpty()}».")
        }

        override suspend fun findPlaces(query: String, limit: Int): List<PlaceCandidate> {
            searched += query
            return emptyList()
        }
    }

    private fun env(device: DeviceController = UnsupportedDevice) =
        TestEnv(device = device).apply { settings = AssistantSettings(useAI = false) }

    @Test
    fun talkedTodayIsASummaryNotAList() = runTest {
        val e = env()
        e.engine.handle("какая погода")
        e.engine.handle("расскажи историю про кота")
        e.engine.handle("который час")
        e.engine.handle("какая погода")

        val text = e.engine.handle("о чём мы говорили сегодня").text
        assertFalse(text.contains("•"), "не перечень реплик: $text")
        assertFalse(Regex("""в \d{2}:\d{2}""").containsMatchIn(text), "без времени у каждой реплики: $text")
        assertTrue(text.startsWith("Сегодня вы"), text)
        assertTrue(text.contains("дважды") || text.contains("2 раза"), "повтор схлопывается: $text")
        assertTrue(text.length < 220, "сводка короткая: $text")
    }

    @Test
    fun talkedTodayKeepsOneVerb() = runTest {
        val e = env()
        e.engine.handle("построй маршрут до аптеки")
        e.engine.handle("найди инструкцию в интернете")

        val text = e.engine.handle("о чём мы говорили сегодня").text
        assertTrue(text.contains("искали маршрут"), text)
        assertTrue(text.contains("и в интернете"), text)
        assertFalse(text.contains("и искали"), "глагол не повторяем: $text")
    }

    @Test
    fun talkedTodayWithoutData() = runTest {
        val e = env()
        assertTrue(e.engine.handle("о чём мы говорили сегодня").text.contains("не нашла"))
    }

    @Test
    fun placeNameWithoutKindWords() {
        assertEquals("Галерея", Places.name("торгового центра Галерея"))
        assertNull(Places.name("аптека"))
        assertNull(Places.name("ближайший торговый центр"))
        // Предлог в остатке — значит, фразу только описывают, а не называют: маршрут строим по ней целиком.
        assertNull(Places.name("аптека на ленина"))
        assertNull(Places.name("кафе в парке"))
        assertEquals(
            listOf("Галерея Новосибирск", "торгового центра Галерея"),
            Places.searchQueries("торгового центра Галерея", "Новосибирск"),
        )
        // Города нет — сначала название, потом фраза как сказали.
        assertEquals(listOf("Галерея", "торгового центра Галерея"), Places.searchQueries("торгового центра Галерея", null))
    }

    @Test
    fun namedMallSearchedByName() = runTest {
        val device = NoPlaces()
        val e = env(device)
        e.engine.handle("построй маршрут до торгового центра Галерея")

        // Рядом ничего нет — место ищут карты, и получают название без слов-типов:
        // «галерея», а не фразу целиком.
        assertEquals(listOf("торгового центра Галерея"), device.searched)
        val nav = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("Галерея", nav.destination)
    }

    /** «Аптека» без уточнений — вид места: рядом пусто, значит слово уходит картам. */
    @Test
    fun plainPharmacyGoesToMaps() = runTest {
        val device = NoPlaces()
        val e = env(device)
        e.engine.handle("построй маршрут до аптеки")
        // Сначала смотрим, что есть совсем рядом, — и только потом отдаём слово картам.
        assertEquals(listOf("аптека"), device.searched)
        val nav = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("аптека", nav.destination)
        assertTrue(nav.walking, "до аптеки идут пешком")
    }
}
