package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Маршрут голосом: Нова находит ближайшее место, строит путь до точки и умеет показать следующее
 * по «ещё». Без карт и интернета — на подставном телефоне: здесь важно поведение разговора.
 */
class RouteDialogTest {

    private class FakeDevice : DeviceController {
        val found = mutableMapOf<String, List<PlaceCandidate>>()
        val routed = mutableListOf<PlaceCandidate>()
        val searched = mutableListOf<String>()
        val performed = mutableListOf<DeviceCommand>()

        override suspend fun perform(command: DeviceCommand): DeviceResult {
            performed += command
            return DeviceResult("Ищу «${(command as? DeviceCommand.Navigate)?.destination.orEmpty()}».")
        }

        override suspend fun findPlaces(query: String, limit: Int): List<PlaceCandidate> {
            searched += query
            return found[query].orEmpty()
        }

        override fun routeTo(place: PlaceCandidate, app: MapsApp?): DeviceResult {
            routed += place
            return DeviceResult("Строю маршрут в Google Картах.")
        }
    }

    private fun place(name: String, meters: Int) =
        PlaceCandidate(name = name, address = "улица Ленина, 5", lat = 55.0 + meters / 100000.0, lon = 82.9, meters = meters)

    @Test
    fun pharmacyGoesToTheNearestOne() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Аптека Вита", 350), place("Магнит Аптека", 900), place("Аптека Плюс", 1500))
        val e = TestEnv(device = device)

        val reply = e.engine.handle("построй маршрут до аптеки").text
        assertEquals(listOf("аптека"), device.searched)
        assertEquals("Аптека Вита", device.routed.single().name)
        assertTrue(reply.contains("Аптека Вита"), reply)
        assertTrue(reply.contains("350 метров"), reply)
        assertTrue(reply.contains("ещё", ignoreCase = true), reply)
    }

    @Test
    fun anotherOneOnEshyo() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Аптека Вита", 350), place("Магнит Аптека", 900))
        val e = TestEnv(device = device)
        e.engine.handle("маршрут до аптеки")

        val next = e.engine.handle("ещё").text
        assertEquals(listOf("Аптека Вита", "Магнит Аптека"), device.routed.map { it.name })
        assertTrue(next.contains("Магнит Аптека"), next)

        // Места кончились — честно говорим и больше не ищем.
        val last = e.engine.handle("ещё").text
        assertTrue(last.contains("Больше мест"), last)
    }

    @Test
    fun vagueFoodAsksFirstAndThenRoutes() = runTest {
        val device = FakeDevice()
        device.found["суши-бар"] = listOf(place("Суши Wok", 700))
        val e = TestEnv(device = device)

        val question = e.engine.handle("хочу поесть").text
        assertTrue(question.contains("перекусить", ignoreCase = true), question)
        assertTrue(device.searched.isEmpty(), "до ответа ничего не ищем: ${device.searched}")

        val reply = e.engine.handle("хочу суши").text
        assertEquals(listOf("суши-бар"), device.searched)
        assertTrue(reply.contains("Суши Wok"), reply)
    }

    @Test
    fun namedPlaceAsksNoQuestions() = runTest {
        val device = FakeDevice()
        device.found["кафе"] = listOf(place("Кафе Пушкин", 400))
        val e = TestEnv(device = device)

        val reply = e.engine.handle("построй маршрут до кафе").text
        assertTrue(reply.contains("Кафе Пушкин"), reply)
    }

    @Test
    fun exactAddressRoutesAtOnceWithoutAnotherOffer() = runTest {
        val device = FakeDevice()
        device.found["Большевистская 36"] = listOf(place("Большевистская 36", 2100), place("Большевистская 38", 2200))
        val e = TestEnv(device = device)

        val reply = e.engine.handle("маршрут до Большевистская 36").text
        assertEquals("Большевистская 36", device.routed.single().name)
        assertTrue(reply.contains("2,1 км"), reply)
        assertTrue(!reply.contains("ещё"), "точный адрес не предлагаем менять: $reply")
    }

    @Test
    fun noPlacesFoundHandsNameToMaps() = runTest {
        val device = FakeDevice()
        val e = TestEnv(device = device)

        e.engine.handle("маршрут до вокзала")
        val command = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("вокзал", command.destination)
        assertTrue(device.routed.isEmpty())
    }

    @Test
    fun refusalCancelsTheOffer() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Аптека Вита", 350), place("Магнит Аптека", 900))
        val e = TestEnv(device = device)
        e.engine.handle("маршрут до аптеки")

        val reply = e.engine.handle("нет, спасибо").text
        assertTrue(reply.contains("не строю"), reply)
        assertEquals(1, device.routed.size)

        // После отказа «ещё» — уже обычная фраза, а не продолжение маршрута.
        assertNull(device.performed.lastOrNull() as? DeviceCommand.Navigate)
    }
}
