package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Пятьдесят фраз про маршрут по всем видам мест: аптека, кафе, заправка, отель, банкомат,
 * торговый центр, автобусная остановка и остальное.
 *
 * Проверяется то, что видит человек: место доехало до карт правильным словом, пешком или на авто,
 * и Нова не перехватила покупки, траты и вопросы. Рядом с телефоном здесь ничего нет — значит,
 * место ищут карты; случай «рядом есть» проверяется отдельно в [RouteDialogTest].
 */
class RouteCategoriesTest {

    private class FakeDevice : DeviceController {
        val found = mutableMapOf<String, List<PlaceCandidate>>()
        val performed = mutableListOf<DeviceCommand>()
        val searched = mutableListOf<String>()
        val routed = mutableListOf<PlaceCandidate>()

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
            return DeviceResult("Строю маршрут.")
        }

        fun navigate() = performed.filterIsInstance<DeviceCommand.Navigate>().singleOrNull()
    }

    /** Фраза человека → слово, которое получают карты, и как идём: пешком или на машине. */
    private data class Case(val phrase: String, val word: String, val walking: Boolean = true)

    private val cases = listOf(
        // Аптека
        Case("найди ближайшую аптеку", "аптека"),
        Case("маршрут до аптеки", "аптека"),
        Case("построй маршрут до ближайшей аптеки", "аптека"),
        Case("до аптеки", "аптека"),
        Case("где ближайшая аптека", "аптека"),
        Case("аптека рядом", "аптека"),
        Case("отвези меня в аптеку", "аптека"),
        Case("маршрут до аптеки в 2ГИС", "аптека"),
        // Кафе, кофейни, еда
        Case("маршрут до кафе", "кафе"),
        Case("построй маршрут до ресторана", "ресторан"),
        Case("найди кафе рядом", "кафе"),
        Case("отведи в кофейню", "кофейня"),
        Case("маршрут до пиццерии", "пиццерия"),
        Case("хочу суши", "суши"),
        Case("до шаурмы", "шаурма"),
        // Заправки — на машине
        Case("маршрут до заправки", "заправка", walking = false),
        Case("найди ближайшую заправку", "заправка", walking = false),
        Case("где заправиться", "заправка", walking = false),
        Case("построй маршрут до АЗС", "заправка", walking = false),
        // Отели — на машине
        Case("маршрут до отеля", "гостиница", walking = false),
        Case("найди гостиницу рядом", "гостиница", walking = false),
        Case("отвези в отель", "гостиница", walking = false),
        Case("построй маршрут до ближайшего отеля", "гостиница", walking = false),
        Case("где переночевать", "гостиница", walking = false),
        // Банки и банкоматы
        Case("маршрут до банкомата", "банкомат"),
        Case("найди ближайший банкомат", "банкомат"),
        Case("где снять деньги", "банкомат"),
        Case("маршрут до банка", "банк"),
        Case("построй маршрут до отделения банка", "банк"),
        // Торговые центры и магазины
        Case("маршрут до торгового центра", "торговый центр"),
        Case("найди ближайший торговый центр", "торговый центр"),
        Case("построй маршрут до ТЦ", "торговый центр"),
        Case("маршрут до супермаркета", "супермаркет"),
        Case("найди продуктовый магазин", "супермаркет"),
        Case("маршрут до магазина одежды", "магазин одежды"),
        // Кино, рынок, стоматология, парковка
        Case("маршрут до кинотеатра", "кинотеатр"),
        Case("найди рынок", "рынок"),
        Case("маршрут до стоматологии", "стоматология"),
        Case("где припарковаться", "парковка", walking = false),
        // Автобусные остановки
        Case("маршрут до автобусной остановки", "автобусная остановка"),
        Case("найди ближайшую остановку", "автобусная остановка"),
        Case("где остановка автобуса", "автобусная остановка"),
        // Кухня и уточнения
        Case("маршрут до суши-бара", "суши-бар"),
        Case("маршрут до ближайшего кафе", "кафе"),
    )

    /** Виды мест и очереди в карты: слово правильное, пешком или на авто — по виду места. */
    @Test
    fun everyCategoryReachesTheMapsWithTheRightWord() = runTest {
        val problems = mutableListOf<String>()
        for (case in cases) {
            val device = FakeDevice()
            val reply = runCatching { TestEnv(device = device).engine.handle(case.phrase).text }
                .getOrElse { "ОШИБКА: $it" }
            val nav = device.navigate()
            when {
                nav == null -> problems += "«${case.phrase}» → маршрута нет (ответ: $reply)"
                nav.destination != case.word -> problems += "«${case.phrase}» → карты получили «${nav.destination}», ждали «${case.word}»"
                nav.walking != case.walking -> problems += "«${case.phrase}» → ${if (nav.walking) "пешком" else "на авто"}, ждали ${if (case.walking) "пешком" else "на авто"}"
                device.routed.isNotEmpty() -> problems += "«${case.phrase}» → рядом ничего нет, а маршрут построен по точке"
            }
        }
        assertTrue(problems.isEmpty(), "Проблемы (${problems.size} из ${cases.size}):\n" + problems.joinToString("\n"))
    }

    /** Покупки, траты, вопросы и записи маршрутом не становятся: «рынок» в вопросе — не поездка. */
    @Test
    fun ordinaryPhrasesAreNotRoutes() = runTest {
        val phrases = listOf(
            "купи продукты",
            "добавь в список покупок молоко",
            "потратила 500 на кафе",
            "что такое фондовый рынок",
            "сколько стоит билет в кино",
        )
        val problems = mutableListOf<String>()
        for (phrase in phrases) {
            val device = FakeDevice()
            val reply = runCatching { TestEnv(device = device).engine.handle(phrase).text }
                .getOrElse { "ОШИБКА: $it" }
            if (device.navigate() != null) problems += "«$phrase» → ушло в маршрут: ${device.navigate()?.destination}"
            if (reply.contains("аршрут")) problems += "«$phrase» → в ответе маршрут: $reply"
        }
        assertTrue(problems.isEmpty(), "Проблемы (${problems.size} из ${phrases.size}):\n" + problems.joinToString("\n"))
    }

    /** Название и адрес Нова находит сама: карты получают координаты, а не слово. */
    @Test
    fun namesAndAddressesAreRoutedByPoint() = runTest {
        val nameCases = listOf(
            "маршрут до Пушкин" to "Пушкин",
            "маршрут до Галереи" to "галерея",
            "построй маршрут до Большевистская 36" to "Большевистская 36",
            "маршрут до Вокзала" to "Вокзал",
            "как доехать до Аэропорта" to "Аэропорт",
        )
        val problems = mutableListOf<String>()
        for ((phrase, found) in nameCases) {
            val device = FakeDevice()
            device.found[found] = listOf(place(found, 900))
            val e = TestEnv(device = device)
            val reply = runCatching { e.engine.handle(phrase).text }.getOrElse { "ОШИБКА: $it" }
            when {
                device.routed.isEmpty() -> problems += "«$phrase» → маршрут по точке не построен (ответ: $reply)"
                !device.routed.single().name.equals(found, ignoreCase = true) -> problems += "«$phrase» → повели к «${device.routed.single().name}», ждали «$found»"
            }
        }
        assertTrue(problems.isEmpty(), "Проблемы (${problems.size} из ${nameCases.size}):\n" + problems.joinToString("\n"))
    }

    /** «Поесть» без уточнения — вопрос про кухню, а не маршрут в первый попавшийся фастфуд. */
    @Test
    fun vagueFoodAsksTheQuestionFirst() = runTest {
        val device = FakeDevice()
        val e = TestEnv(device = device)

        val question = e.engine.handle("где поесть").text
        assertTrue(question.contains("перекусить", ignoreCase = true), question)
        assertTrue(device.performed.isEmpty(), "без ответа маршрут не строим: ${device.performed}")

        e.engine.handle("хочу суши")
        assertEquals("суши-бар", device.navigate()?.destination)
    }

    private fun place(name: String, meters: Int) = PlaceCandidate(
        name = name,
        address = "улица Ленина, 5",
        lat = 55.0 + meters / 100000.0,
        lon = 82.9,
        meters = meters,
    )
}
