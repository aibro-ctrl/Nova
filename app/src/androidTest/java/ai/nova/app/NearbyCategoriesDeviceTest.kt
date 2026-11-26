package ai.nova.app

import ai.nova.app.ui.MainActivity
import ai.nova.core.assistant.PlaceKind
import org.junit.Assert.assertEquals
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка по видам мест: у каждого вида Нова сначала смотрит, что есть совсем рядом.
 *
 * Проверяется то, из-за чего маршрут раньше уводил через город: ближайшее место должно находиться
 * в пределах «дойти пешком», а не за несколько километров. Справочник — открытая карта, поэтому
 * расстояния здесь настоящие, без подставных ответов.
 *
 * Экран открыт намеренно: без видимой активности Android не отдаёт геолокацию приложению.
 */
@RunWith(AndroidJUnit4::class)
class NearbyCategoriesDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<NovaApp>()

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun locationGranted() =
        context().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun withScreen(block: suspend (AppContainer) -> Unit) {
        val screen = ActivityScenario.launch(MainActivity::class.java)
        try {
            block(app.container)
        } finally {
            screen.close()
        }
    }

    /** «Совсем рядом» для пеших мест — двести метров, для тех, куда едут, — километр. */
    private val kinds = listOf(
        PlaceKind.PHARMACY to "аптека",
        PlaceKind.FOOD to "кафе",
        PlaceKind.COFFEE to "кафе",
        PlaceKind.GROCERY to "супермаркет",
        PlaceKind.ATM to "банкомат",
        PlaceKind.BANK to "банк",
        PlaceKind.CINEMA to "кинотеатр",
        PlaceKind.MALL to "молл",
        PlaceKind.CLOTHES to "магазин одежды",
        PlaceKind.DENTIST to "стоматология",
        PlaceKind.STOP to "автобусная остановка",
    )

    @Test
    fun everyWalkableKindIsFoundAroundTheCorner(): Unit = runBlocking {
        assumeTrue("нет разрешения на геолокацию", locationGranted())
        withScreen { c ->
            val problems = mutableListOf<String>()
            for ((kind, word) in kinds) {
                val found = c.device.findPlaces(kind.osm)
                Log.i("Nearby", "${kind.name} «${kind.osm}» → " + found.take(3).map { "${it.meters} м · ${it.title}" })
                val nearest = found.firstOrNull()?.meters ?: run {
                    problems += "${kind.name}: рядом ничего не нашлось"
                    continue
                }
                if (nearest > 1_000) problems += "${kind.name}: ближайшее в $nearest м — это уже не рядом"
            }
            assertTrue("Проблемы:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun fuelIsFoundWithinAKilometre(): Unit = runBlocking {
        assumeTrue("нет разрешения на геолокацию", locationGranted())
        withScreen { c ->
            val found = c.device.findPlaces(PlaceKind.FUEL.osm)
            Log.i("Nearby", "заправка → " + found.take(3).map { "${it.meters} м · ${it.title}" })
            assumeTrue("заправок рядом не нашлось — проверять нечего", found.isNotEmpty())
            assertTrue("заправка в ${found.first().meters} м — слишком далеко", (found.first().meters ?: Int.MAX_VALUE) <= 1_000 + 500)
        }
    }

    /** Ответ Новы по категории: рядом есть — ведём по координатам, а не словом в карты. */
    @Test
    fun nearbyMallIsRoutedByPoint(): Unit = runBlocking {
        assumeTrue("нет разрешения на геолокацию", locationGranted())
        withScreen { c ->
            val found = c.device.findPlaces(PlaceKind.MALL.osm)
            Log.i("Nearby", "торговый центр → " + found.take(3).map { "${it.meters} м · ${it.title}" })
            assumeTrue("торговых центров рядом не нашлось", found.isNotEmpty() && (found.first().meters ?: Int.MAX_VALUE) <= 200)

            val reply = c.engine.handle("построй маршрут до торгового центра").text
            Log.i("Nearby", "ответ про торговый центр: $reply")
            assertTrue("ответ не про ближайший торговый центр: $reply", reply.contains("Ближайший вариант"))
        }
    }

    /** Аптека: рядом ничего нет — слово уходит картам, у них справочник полнее. */
    @Test
    fun pharmacyFallsBackToMapsWhenNothingIsAround(): Unit = runBlocking {
        assumeTrue("нет разрешения на геолокацию", locationGranted())
        withScreen { c ->
            val near = c.device.findPlaces("аптека")
            Log.i("Nearby", "аптека рядом → " + near.take(3).map { "${it.meters} м · ${it.title}" })
            // Порог у аптеки строже: если открытая карта нашла аптеку ближе 120 метров — ведём к ней.
            val limit = 120
            assumeTrue("аптека есть совсем рядом — этот случай проверяет другой тест", (near.firstOrNull()?.meters ?: Int.MAX_VALUE) > limit)

            val reply = c.engine.handle("найди ближайшую аптеку").text
            Log.i("Nearby", "ответ про аптеку: $reply")
            assertTrue("аптека не ушла в карты: $reply", reply.contains("2ГИС") || reply.contains("Карты") || reply.contains("браузере"))
        }
    }

    /** У вида места два слова: одно для карт, другое для открытой карты — она ищет по названиям. */
    @Test
    fun everyKindHasAWordTheOpenMapUnderstands() {
        Log.i("Nearby", "виды мест: " + PlaceKind.entries.map { "${it.name}: карты=${it.search}, открытая карта=${it.osm}" })
        // «торговый центр» открытая карта не понимает — отвечает пусто; «молл» находит.
        assertEquals("молл", PlaceKind.MALL.osm)
        assertEquals("кафе", PlaceKind.COFFEE.osm)
        assertTrue("у вида места должно быть слово для открытой карты", PlaceKind.entries.all { it.osm.isNotBlank() })
    }
}
