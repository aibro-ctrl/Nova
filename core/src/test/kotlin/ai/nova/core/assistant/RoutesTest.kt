package ai.nova.core.assistant

import ai.nova.core.nlp.RuQuery
import ai.nova.core.skills.SavedPlace
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Маршруты: Nova должна понимать не только «построй маршрут до вокзала», но и просто «музей» или «где поесть». */
class RoutesTest {

    private val now = Instant.parse("2026-11-23T05:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")

    private fun parse(phrase: String): AssistantPlan? = LocalCommandParser().parse(phrase, now, zone)

    private fun navigate(phrase: String): DeviceCommand.Navigate? =
        (parse(phrase)?.actions?.singleOrNull() as? AssistantAction.Device)?.command as? DeviceCommand.Navigate

    private fun destination(phrase: String): String? = navigate(phrase)?.destination

    @Test fun explicitRoutePhrases() {
        assertEquals("вокзал", destination("построй маршрут до вокзала"))
        assertEquals("музей", destination("построй маршрут до музея"))
        assertEquals("рынок", destination("навигатор до рынка"))
        assertEquals("аэропорт", destination("отвези меня в аэропорт"))
        assertEquals("дача", destination("поехали на дачу"))
        assertEquals("метро", destination("как дойти до метро"))
        assertEquals("аптека", destination("как доехать до аптеки"))
        assertEquals("Лувр", destination("проложи маршрут до Лувра"))
    }

    /** Так строил маршрут пользователь — и получал напоминание «Маршрут домой гис» или задачу «Осмотреть музей». */
    @Test fun userPhrasesFromTheChat() {
        assertEquals("дом", destination("маршрут домой в 2гис"))
        assertEquals(MapsApp.TWOGIS, navigate("маршрут домой в 2гис")?.app)
        assertEquals("дом", destination("маршрут домой в 2gis"))
        assertEquals("музей", destination("маршрут музей"))
        assertEquals("музей", destination("в 2гис маршрут до музея"))
        assertEquals(MapsApp.TWOGIS, navigate("в 2гис маршрут до музея")?.app)
        assertEquals("музей", destination("маршрут до музея в 2gis"))
        assertEquals("аптека", destination("маршрут яндекс карты до аптеки"))
        assertEquals(MapsApp.YANDEX, navigate("маршрут яндекс карты до аптеки")?.app)
        assertEquals("аэропорт", destination("маршрут аэропорт"))
        assertEquals("кино", destination("поехали кино"))
    }

    /** «Маршрут в 2гис» без места — это не напоминание и не трата, а вопрос «куда ехать?». */
    @Test fun routeWithAppButWithoutPlaceAsksWhere() {
        val plan = parse("маршрут 2гис")
        assertEquals(emptyList(), plan?.actions)
        assertEquals(true, plan?.reply?.contains("Куда построить маршрут"), plan?.reply)
    }

    @Test fun timeIsNotAPlace() {
        assertNull(destination("маршрут на сегодня"))
        // И это не задача «Маршрут» — лучше переспросить.
        val plan = parse("маршрут на сегодня")
        assertEquals(emptyList(), plan?.actions)
        assertEquals(true, plan?.reply?.contains("Куда построить маршрут"), plan?.reply)
    }

    /** Живые фразы из чата на телефоне: с «найди», с опечатками и с распознанным «построй-ка». */
    @Test fun findRouteAndTypos() {
        // «найди маршрут домой» искалось в интернете и отвечало статьёй про трамваи Казани.
        assertEquals("дом", destination("найди маршрут домой"))
        assertEquals("дом", destination("найди маршрут дом"))
        assertEquals("краеведческий музей", destination("найди маршрут краеведческий музей"))
        assertEquals("музей", destination("найди маршрут до музея"))
        assertEquals("аптека", destination("найди дорогу до аптеки"))
        // «постройки маршрут до дом», «построй маршут до дом» — «не совсем поняла команду».
        assertEquals("дом", destination("постройки маршрут до дом"))
        assertEquals("дом", destination("построй маршут до дом"))
        assertEquals("дом", destination("построй-ка маршрут до дома"))
        assertEquals("аэропорт", destination("построй маршрут аэропорт"))
        // «найди маршрут» без места — уточняем, а не ищем в интернете.
        val plan = parse("найди маршрут")
        assertEquals(emptyList(), plan?.actions)
        assertEquals(true, plan?.reply?.contains("Куда построить маршрут"), plan?.reply)
    }

    @Test fun placeWithoutCommand() {
        // Именно эти фразы раньше заканчивались «не поняла».
        assertEquals("музей", destination("музей"))
        assertEquals("аптека", destination("ближайшая аптека"))
        assertEquals("аптека", destination("найди ближайшую аптеку"))
        assertEquals("аптека", destination("где найти аптеку"))
        assertEquals("банкомат", destination("где банкомат"))
        assertEquals("заправка", destination("заправка рядом"))
        assertEquals("кино", destination("хочу в кино"))
        assertEquals("аптека", destination("до аптеки"))
        assertEquals("дом", destination("дом"))
        assertEquals("работа", destination("на работу"))
    }

    @Test fun foodMeansCafe() {
        // Вид места не назван — сначала спросим «посидеть или перекусить», а не повезём в случайное кафе.
        for (phrase in listOf("поесть", "где поесть", "куда поесть", "хочу поесть", "пообедать", "перекусить", "сходим поесть")) {
            val nav = navigate(phrase)
            assertEquals("поесть", nav?.destination, phrase)
            assertEquals(true, nav?.vague, phrase)
        }
        // Вид места назвали прямо — спрашивать нечего.
        assertEquals("кафе", navigate("построй маршрут до кафе")?.destination)
        assertEquals(false, navigate("построй маршрут до кафе")?.vague)
    }

    @Test fun mapsAppFromSpeech() {
        assertEquals(MapsApp.TWOGIS, navigate("построй маршрут до дома в 2ГИС")?.app)
        assertEquals("дом", navigate("построй маршрут до дома в 2ГИС")?.destination)
    }

    @Test fun routeWithoutPlaceAsksWhere() {
        for (phrase in listOf("маршрут", "поехали", "навигатор")) {
            val plan = parse(phrase) ?: error("«$phrase» не разобрана")
            assertNull(plan.actions.singleOrNull() as? AssistantAction.Device, phrase)
            assertEquals(true, plan.reply.contains("Куда построить маршрут"), phrase)
        }
    }

    @Test fun doesNotStealOtherCommands() {
        // Траты и задачи с названием места остаются тратами и задачами.
        assertNull(navigate("расход 1500 аптека"))
        assertNull(navigate("потратила 450 на кафе"))
        assertNull(navigate("завтра схожу в парикмахерскую"))
        // «домой» — это главный экран, а не маршрут.
        assertEquals(DeviceCommand.Global(GlobalAction.HOME), (parse("домой")?.actions?.singleOrNull() as AssistantAction.Device).command)
        // Видео и личные вопросы не превращаются в карту.
        assertEquals("рыбалка", ((parse("найди видео про рыбалку")?.actions?.singleOrNull() as AssistantAction.Device).command as DeviceCommand.Play).query)
        assertEquals(AssistantAction.Search("зарядка", emptyList(), null), parse("где моя зарядка")?.actions?.singleOrNull())
        // «До свидания» — прощание, а не маршрут до «свидания».
        assertNull(navigate("до свидания"))
    }

    @Test fun placeFormForMaps() {
        assertEquals("рынок", RuQuery.placeForm("до рынка"))
        assertEquals("музей", RuQuery.placeForm("до музея"))
        assertEquals("аптека", RuQuery.placeForm("ближайшая аптека"))
        assertEquals("аптека", RuQuery.placeForm("ближайшей аптеки"))
        assertEquals("заправка", RuQuery.placeForm("заправка рядом"))
        assertEquals("кафе", RuQuery.placeForm("поесть"))
        // Многословные адреса не трогаем: «Казани, Баумана 5» карты понимают как есть.
        assertEquals("Казани, Баумана 5", RuQuery.placeForm("в Казани, Баумана 5"))
    }

    @Test fun savedPlaceMatchesInflectedForms() {
        // Приложение сверяет место с сохранёнными («я дома»): «до дома» и «дом» — одно и то же место.
        assertEquals(true, RuQuery.samePlace("дом", "до дома"))
        assertEquals(true, RuQuery.samePlace("работа", "на работу"))
        assertEquals(false, RuQuery.samePlace("дом", "музей"))
    }
}

/** Куда вести маршрут: сохранённое место по координатам или название с городом. */
class RoutePlannerTest {

    private val home = SavedPlace("дом", 55.7558, 37.6173)
    private val saved = listOf(home, SavedPlace("работа", 55.7, 37.6))

    @Test fun savedPlaceUsesCoordinates() {
        val home = RoutePlanner.target("дом", "Казань", saved) ?: error("место не найдено")
        assertEquals("55.7558,37.6173", home.query)
        assertEquals("дом", home.place?.name)
        // «До дома» — то же место, что и «дом».
        assertEquals("55.7558,37.6173", RoutePlanner.target("до дома", null, saved)?.query)
        assertEquals("55.7,37.6", RoutePlanner.target("на работу", null, saved)?.query)
    }

    @Test fun unknownPlaceSearchesWithCity() {
        val target = RoutePlanner.target("аптека", "Казань", saved) ?: error("место не найдено")
        assertEquals("аптека, Казань", target.query)
        assertEquals(null, target.place)
    }

    @Test fun unsavedHomeIsNotSearchedAsWord() {
        // Искать слово «дом» на карте бессмысленно: пусть пользователь сохранит место.
        assertEquals(null, RoutePlanner.target("дом", "Казань", emptyList()))
        assertEquals(null, RoutePlanner.target("работа", null, emptyList()))
        // А обычное место без сохранённых мест ищется как раньше.
        assertEquals("вокзал", RoutePlanner.target("вокзал", null, emptyList())?.query)
    }
}
