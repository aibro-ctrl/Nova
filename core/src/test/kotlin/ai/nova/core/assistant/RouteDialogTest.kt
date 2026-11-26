package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Маршрут голосом: вид места («аптека», «кафе») Нова отдаёт картам — у них полный справочник,
 * а название и адрес находит сама и строит путь до точки. Без карт и интернета — на подставном
 * телефоне: здесь важно поведение разговора.
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

    /**
     * «Аптека» — вид места: сначала смотрим, есть ли что-то совсем рядом. Ничего нет — слово
     * уходит картам: их справочник полнее, там есть и аптека на первом этаже жилого дома.
     */
    @Test
    fun nearestIsSearchedByTheMapsAppWhenNothingIsAround() = runTest {
        val device = FakeDevice()
        val e = TestEnv(device = device)

        val reply = e.engine.handle("построй маршрут до аптеки").text
        assertEquals(listOf("аптека"), device.searched, "рядом проверяем один раз")
        assertTrue(device.routed.isEmpty(), "точку не выдумываем: ${device.routed}")
        val command = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("аптека", command.destination)
        assertTrue(command.walking, "до аптеки идут пешком")
        assertTrue(reply.contains("аптека"), reply)
    }

    /** Аптека в двух шагах — ведём к ней по координатам: это точно ближайшая. */
    @Test
    fun pharmacyAroundTheCornerIsRoutedByPoint() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Аптека Вита", 90), place("Аптека на Ленина", 400))
        val e = TestEnv(device = device)

        val reply = e.engine.handle("построй маршрут до аптеки").text
        assertEquals("Аптека Вита", device.routed.single().name)
        assertTrue(device.performed.isEmpty(), "карты не нужны — точка рядом: ${device.performed}")
        assertTrue(reply.contains("Аптека Вита"), reply)
        assertTrue(reply.contains("90 метр"), reply)
    }

    /**
     * У аптек порог строже: в открытой карте аптека на первом этаже дома часто не отмечена.
     * Аптека в 150 метрах — уже повод спросить карты, а не вести в соседнее здание.
     */
    @Test
    fun pharmacyBeyondAHundredMetersIsSearchedByMaps() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Сибирский лекарь", 150))
        val e = TestEnv(device = device)

        e.engine.handle("найди ближайшую аптеку")
        assertTrue(device.routed.isEmpty(), "аптека в 150 м — сверяемся с картами: ${device.routed}")
        assertEquals("аптека", device.performed.filterIsInstance<DeviceCommand.Navigate>().single().destination)
    }

    /** Дальше «совсем рядом» — отдаём слово картам: соседняя аптека может оказаться не ближайшей. */
    @Test
    fun farPharmacyIsSearchedByMaps() = runTest {
        val device = FakeDevice()
        device.found["аптека"] = listOf(place("Дальняя аптека", 900))
        val e = TestEnv(device = device)

        e.engine.handle("построй маршрут до аптеки")
        assertTrue(device.routed.isEmpty(), "маршрут по точке не строим: ${device.routed}")
        assertEquals("аптека", device.performed.filterIsInstance<DeviceCommand.Navigate>().single().destination)
    }

    /** До заправки и парковки едут: пеший маршрут до них бессмыслен. */
    @Test
    fun fuelAndParkingAreReachedByCar() = runTest {
        val device = FakeDevice()
        val e = TestEnv(device = device)

        e.engine.handle("маршрут до заправки")

        val command = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("заправка", command.destination)
        assertEquals(false, command.walking)
    }

    /** «Ещё» после вида места: список всех аптек рядом — в самом приложении карт. */
    @Test
    fun eshyoPointsToTheMapsAppList() = runTest {
        val device = FakeDevice()
        val e = TestEnv(device = device)
        e.engine.handle("маршрут до аптеки")

        val next = e.engine.handle("ещё").text
        assertTrue(next.contains("карт"), next)
        assertEquals(1, device.performed.size, "места не перелистываем повторно: ${device.performed}")
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
        val command = device.performed.filterIsInstance<DeviceCommand.Navigate>().single()
        assertEquals("суши-бар", command.destination)
        assertTrue(command.walking, "до суши-бара идут пешком")
        assertTrue(reply.contains("суши-бар"), reply)
    }

    /** Название — не вид места: его Нова находит сама и ведёт по координатам. */
    @Test
    fun namedPlaceAsksNoQuestions() = runTest {
        val device = FakeDevice()
        device.found["Пушкин"] = listOf(place("Кафе Пушкин", 400))
        val e = TestEnv(device = device)

        val reply = e.engine.handle("построй маршрут до Пушкин").text
        assertEquals("Кафе Пушкин", device.routed.single().name)
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
        val e = TestEnv(device = device)
        e.engine.handle("маршрут до аптеки")

        val reply = e.engine.handle("нет, спасибо").text
        assertTrue(reply.contains("не строю"), reply)
        // После отказа маршрут второй раз не открываем и «ещё» уже не про места.
        assertEquals(1, device.performed.size, "лишние команды: ${device.performed}")
        assertNull(device.routed.firstOrNull())
    }
}
