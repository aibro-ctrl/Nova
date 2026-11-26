package ai.nova.app

import ai.nova.app.device.StreetPlaces
import ai.nova.app.ui.MainActivity
import ai.nova.core.assistant.MapsApp
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка на телефоне: «ближайшая аптека» — это аптека рядом, а не через город.
 *
 * Раньше аптеку искала Нова по открытой карте: ответы приходили по релевантности (первая точка —
 * за 38 км), а аптек на первых этажах домов в открытой карте нет вовсе. Из-за этого «ближайшей»
 * оказывалась аптека в соседнем здании, хотя своя была в этом же доме.
 *
 * Теперь вид места («аптека», «кафе», «заправка») ищут сами карты — у них полный справочник города
 * и часы работы, и никаких ключей для этого не нужно. Нова только передаёт слово картам проверенной
 * ссылкой. Название и адрес Нова по-прежнему находит сама.
 *
 * Экран открыт намеренно: без видимой активности Android не отдаёт геолокацию приложению.
 */
@RunWith(AndroidJUnit4::class)
class NearbyPlacesDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<NovaApp>()

    private fun installed(maps: MapsApp) =
        runCatching { app.packageManager.getPackageInfo(maps.pkg, 0) }.isSuccess

    private suspend fun withScreen(block: suspend (AppContainer) -> Unit) {
        val screen = ActivityScenario.launch(MainActivity::class.java)
        try {
            block(app.container)
        } finally {
            screen.close()
        }
    }

    /**
     * «Аптека» — вид места: Нова сверяется с открытой картой, а если рядом ничего нет —
     * слово уходит картам: у них есть и аптека на первом этаже дома.
     */
    @Test
    fun pharmacyIsSearchedByTheMapsApp() = runBlocking {
        withScreen { c ->
            val found = c.device.findPlaces("аптека")
            Log.i("Nearby", "рядом по открытой карте → " + found.take(3).map { "${it.meters} м · ${it.title}" })

            val reply = c.engine.handle("найди ближайшую аптеку")
            Log.i("Nearby", "ответ Новы: " + reply.text)
            assertTrue("Нова не передала маршрут картам: ${reply.text}", reply.text.contains("аршрут"))
            val maps = MapsApp.entries.filter { installed(it) }
            Log.i("Nearby", "карты на телефоне: " + maps.map { it.title })
            assumeTrue("на телефоне нет приложения карт — проверять нечего", maps.isNotEmpty())
            // Если карты не открылись, Нова честно говорит про браузер — тогда название не проверить.
            if (!reply.text.contains("в браузере")) {
                assertTrue(
                    "в ответе нет приложения карт: ${reply.text}",
                    maps.any { reply.text.contains(it.title) },
                )
            }
        }
    }

    /** Ссылка 2ГИС со словом должна открываться самим приложением, а не браузером. */
    @Test
    fun twoGisResolvesTheWordRoute() {
        assumeTrue("2ГИС не установлен", installed(MapsApp.TWOGIS))
        val link = MapsApp.TWOGIS.routeTo(null, null, "аптека", walking = true)
        Log.i("Nearby", "ссылка 2ГИС: $link")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(MapsApp.TWOGIS.pkg)
        assertNotNull("2ГИС не берётся открыть маршрут по слову", intent.resolveActivity(app.packageManager))
    }

    /** Название и адрес Нова находит сама — они не зависят от справочника карт. */
    @Test
    fun addressIsStillFoundByNova(): Unit = runBlocking {
        val http = HttpClient(Android) {
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
        }

        val found = StreetPlaces(http).search("Большевистская, 36", city = "Новосибирск", here = 55.0302 to 82.9204)
        Log.i("Nearby", "адрес → " + found.map { "${it.meters} м · ${it.title}" })
        assertTrue("адрес не нашёлся", found.isNotEmpty())
        assertTrue("адрес в ${found.first().meters} м — слишком далеко", (found.first().meters ?: Int.MAX_VALUE) < 5_000)
        // Точка есть — картам можно отдать координаты, а не слово.
        val point = found.first()
        assertNotNull(point.lat)
        assertEquals(true, MapsApp.TWOGIS.routeTo(point.lat, point.lon, point.title).startsWith("dgis://2gis.ru/routeSearch/"))
    }
}
