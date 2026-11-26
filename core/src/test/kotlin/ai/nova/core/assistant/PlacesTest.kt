package ai.nova.core.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Маршрут — это путь до точки, а не поиск адреса.
 *
 * Здесь проверяем офлайн-часть: что человек назвал (кафе, аптека, адрес), какие слова означают
 * «ещё» и «посидеть или перекусить», и какую ссылку получат карты.
 */
class PlacesTest {

    @Test
    fun kindRecognisedFromEverydayWords() {
        assertEquals(PlaceKind.FOOD, Places.kind("поесть"))
        assertEquals(PlaceKind.FOOD, Places.kind("ресторан"))
        assertEquals(PlaceKind.PHARMACY, Places.kind("ближайшая аптека"))
        assertEquals(PlaceKind.CLOTHES, Places.kind("магазин одежды"))
        assertEquals(PlaceKind.MALL, Places.kind("торговый центр"))
        assertEquals(PlaceKind.GROCERY, Places.kind("за продуктами в магазин"))
        assertEquals(PlaceKind.FUEL, Places.kind("заправка"))
        assertEquals(PlaceKind.ATM, Places.kind("банкомат"))
        assertEquals(PlaceKind.CINEMA, Places.kind("кино"))
        assertNull(Places.kind("вокзал"))
        assertNull(Places.kind("Большевистская 36"))
    }

    @Test
    fun vagueFoodOnlyWithoutDetails() {
        assertTrue(Places.vague("поесть"))
        assertTrue(Places.vague("хочу поесть"))
        assertTrue(Places.vague("перекусить"))
        // Вид места уже назван — уточнять нечего.
        assertFalse(Places.vague("кафе"))
        assertFalse(Places.vague("суши"))
    }

    @Test
    fun foodAnswerBecomesSearchQuery() {
        assertEquals("фастфуд", Places.refineFood("быстро перекусить"))
        assertEquals("кафе", Places.refineFood("лучше посидеть"))
        assertEquals("суши-бар", Places.refineFood("хочу суши"))
        assertEquals("пиццерия", Places.refineFood("пиццу"))
        assertEquals("грузинский ресторан", Places.refineFood("грузинскую кухню"))
        assertEquals("кафе", Places.refineFood("любое"))
        assertNull(Places.refineFood("поставь будильник на семь"))
    }

    @Test
    fun nextMeansAnotherPlace() {
        listOf("ещё", "еще", "другое", "другой вариант", "следующее", "не то", "не подходит", "давай другое").forEach {
            assertTrue(Places.isNext(it), it)
        }
        listOf("построй маршрут до дома", "спасибо", "напомни про хлеб").forEach {
            assertFalse(Places.isNext(it), it)
        }
    }

    @Test
    fun addressIsRecognisedByHouseNumber() {
        assertTrue(Places.looksLikeAddress("Большевистская 36"))
        assertTrue(Places.looksLikeAddress("ул. Ленина, 5"))
        assertTrue(Places.looksLikeAddress("дом 12"))
        // Время, деньги и расстояния — не адрес.
        assertFalse(Places.looksLikeAddress("через 5 минут"))
        assertFalse(Places.looksLikeAddress("500 рублей"))
        assertFalse(Places.looksLikeAddress("3 км"))
        assertFalse(Places.looksLikeAddress("аптека"))
    }

    @Test
    fun distanceIsSpokenInMetresAndKilometres() {
        assertEquals("350 метров отсюда", Places.distance(350))
        assertEquals("2,4 км отсюда", Places.distance(2_400))
        assertEquals("", Places.distance(null))
        assertEquals("", Places.distance(0))
    }

    @Test
    fun googleGetsPointOrAddress() {
        // Проверено на телефоне: Google Карты по такой ссылке сразу включают навигацию.
        assertEquals("google.navigation:q=55.03,82.92", MapsApp.GOOGLE.routeTo(55.03, 82.92, "аптека"))
        assertEquals("google.navigation:q=%D0%B0%D0%BF%D1%82%D0%B5%D0%BA%D0%B0", MapsApp.GOOGLE.routeTo(null, null, "аптека"))
    }

    @Test
    fun twoGisRoutesByPointAndByWord() {
        // Проверено на телефоне: ссылка 2ГИС строит маршрут и по координатам, и по слову —
        // место по слову приложение ищет само, по своему справочнику.
        // Порядок координат у 2ГИС — «долгота,широта».
        assertEquals("dgis://2gis.ru/routeSearch/rsType/car/to/82.92,55.03", MapsApp.TWOGIS.routeTo(55.03, 82.92, "аптека"))
        assertEquals(
            "dgis://2gis.ru/routeSearch/rsType/pedestrian/to/82.92,55.03",
            MapsApp.TWOGIS.routeTo(55.03, 82.92, "аптека", walking = true),
        )
        assertEquals(
            "dgis://2gis.ru/routeSearch/rsType/pedestrian/to/%D0%B0%D0%BF%D1%82%D0%B5%D0%BA%D0%B0",
            MapsApp.TWOGIS.routeTo(null, null, "аптека", walking = true),
        )
    }

    @Test
    fun yandexGetsRouteLink() {
        assertTrue(MapsApp.YANDEX.routeTo(55.03, 82.92, "аптека").startsWith("https://yandex.ru/maps/?rtext=~55.03,82.92"))
        assertTrue(MapsApp.YANDEX.routeTo(null, null, "аптека").startsWith("https://yandex.ru/maps/?text="))
    }

    @Test
    fun webFallbacksStayInsideTheSameMaps() {
        assertTrue(MapsApp.GOOGLE.routeWeb(55.03, 82.92, "аптека").startsWith("https://www.google.com/maps/dir/"))
        assertTrue(MapsApp.TWOGIS.routeWeb(null, null, "аптека").startsWith("https://2gis.ru/search/"))
        assertTrue(MapsApp.YANDEX.routeWeb(55.03, 82.92, "аптека").startsWith("https://yandex.ru/maps/"))
    }
}
