package ai.nova.core.skills

import ai.nova.core.TestEnv
import ai.nova.core.assistant.AssistantSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Включи музыку» без названия песни: своя пауза, выбор приложения и песня из прошлой подсказки. */
class MusicAppTest {
    private val itunes = """{"resultCount":1,"results":[{"wrapperType":"track","kind":"song",
        "artistName":"Кино","trackName":"Группа крови","previewUrl":"https://audio-ssl.itunes.apple.com/preview.m4a"}]}"""

    private fun http() = HttpClient(MockEngine { r ->
        if (r.url.toString().contains("itunes.apple.com")) {
            respond(itunes, HttpStatusCode.OK, headersOf("Content-Type", "application/json; charset=utf-8"))
        } else {
            respond("", HttpStatusCode.NotFound)
        }
    })

    private class Host(
        private val installed: List<MusicApp>,
        private val paused: Boolean = false,
        private val plays: Boolean = true,
    ) : SkillHost {
        val launched = ArrayList<Pair<String, MusicApp>>()
        val played = ArrayList<String>()
        var remembered: MusicApp? = null
        override fun hasMusicApp(app: MusicApp) = app in installed
        override suspend fun playInMusicApp(query: String, app: MusicApp): Boolean {
            launched += query to app
            return plays
        }
        override fun resumeMusic() = paused
        override fun musicApp() = remembered
        override fun rememberMusicApp(app: MusicApp) { remembered = app }
        override suspend fun playTrack(track: MusicTrack): Boolean { played += track.label; return true }
    }

    private fun env(host: Host) = TestEnv(skillsFactory = { t -> Skills(host, http(), t) })
        .apply { settings = AssistantSettings(useAI = false) }

    @Test fun `своя музыка на паузе продолжается`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX), paused = true)
        val reply = env(host).engine.handle("включи музыку").text
        assertTrue(reply.contains("Продолжаю"), reply)
        assertTrue(host.launched.isEmpty(), "приложение открывать не нужно: ${host.launched}")
    }

    @Test fun `единственное приложение открывается сразу`() = runTest {
        val host = Host(listOf(MusicApp.ZVUK))
        val reply = env(host).engine.handle("включи музыку").text
        assertTrue(reply.contains("Звук"), reply)
        assertEquals(listOf("" to MusicApp.ZVUK), host.launched)
        assertEquals(MusicApp.ZVUK, host.remembered)
    }

    @Test fun `из двух приложений спрашиваем и запоминаем выбор`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX, MusicApp.ZVUK))
        val e = env(host)
        val question = e.engine.handle("включи музыку").text
        assertTrue(question.contains("Яндекс Музыка") && question.contains("Звук"), question)
        assertTrue(host.launched.isEmpty(), "до ответа ничего не открываем")

        val reply = e.engine.handle("яндекс музыка").text
        assertTrue(reply.contains("Яндекс Музыка"), reply)
        assertEquals(listOf("" to MusicApp.YANDEX), host.launched)
        assertEquals(MusicApp.YANDEX, host.remembered)
    }

    @Test fun `названную песню включает приложение, а не короткий фрагмент`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX, MusicApp.SPOTIFY))
        val e = env(host)
        // Приложение ещё не выбрано, а их два: сначала спрашиваем, где включать.
        val question = e.engine.handle("включи песню кино — группа крови").text
        assertTrue(question.contains("Яндекс Музыка") && question.contains("Spotify"), question)
        assertTrue(host.launched.isEmpty(), "до ответа ничего не открываем")

        val reply = e.engine.handle("яндекс музыка").text
        // Полный трек играет приложение с лицензией: встроенный плеер умеет только 30 секунд.
        assertEquals(listOf("кино — группа крови" to MusicApp.YANDEX), host.launched)
        assertTrue(host.played.isEmpty(), "фрагмент не нужен, если есть приложение: ${host.played}")
        assertTrue(reply.contains("кино", ignoreCase = true), reply)
        assertEquals(MusicApp.YANDEX, host.remembered, "выбор запоминаем, чтобы больше не спрашивать")

        val again = e.engine.handle("включи в яндекс музыке").text
        assertTrue(again.contains("группа крови", ignoreCase = true), again)
        assertEquals("кино — группа крови", host.launched.last().first)
    }

    @Test fun `выбранное приложение больше не спрашиваем`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX, MusicApp.ZVUK))
        host.remembered = MusicApp.ZVUK
        val reply = env(host).engine.handle("включи песню кино — группа крови").text
        assertEquals(listOf("кино — группа крови" to MusicApp.ZVUK), host.launched)
        assertTrue(reply.contains("Звук"), reply)
    }

    @Test fun `без музыкальных приложений песня играет встроенным плеером`() = runTest {
        val host = Host(emptyList())
        val reply = env(host).engine.handle("включи песню кино — группа крови").text
        assertTrue(host.launched.isEmpty(), "приложений нет — открывать нечего")
        assertTrue(host.played.isNotEmpty(), reply)
    }

    @Test fun `исполнитель без названия песни тоже играет`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX))
        val reply = env(host).engine.handle("включи мне Многоточие").text
        assertEquals(MusicApp.YANDEX, host.launched.single().second)
        assertTrue(host.launched.single().first.equals("Многоточие", ignoreCase = true), host.launched.toString())
        assertTrue(reply.contains("Многоточие", ignoreCase = true), reply)
    }

    @Test fun `выбор в настройках главнее проверки установки`() = runTest {
        // Название пакета у сборок разное, и проверка «установлено» может не узнать приложение.
        // Человек уже выбрал — второй раз не спрашиваем.
        val host = Host(emptyList())
        host.remembered = MusicApp.ZVUK
        val e = env(host)
        val reply = e.engine.handle("включи песню кино — группа крови").text
        assertEquals(listOf("кино — группа крови" to MusicApp.ZVUK), host.launched)
        assertTrue(!reply.contains("В каком приложении"), "не спрашиваем: $reply")
    }

    @Test fun `выбор запоминается, даже если включить не удалось`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX), plays = false)
        val e = env(host)
        val reply = e.engine.handle("включи музыку").text
        assertEquals(MusicApp.YANDEX, host.remembered, "ответ остаётся в настройках: $reply")
        assertTrue(reply.contains("Не получилось"), reply)
    }

    @Test fun `нет музыкальных приложений - честно говорим`() = runTest {
        val host = Host(emptyList())
        val reply = env(host).engine.handle("включи музыку").text
        assertTrue(reply.contains("нет музыкального приложения"), reply)
    }
}
