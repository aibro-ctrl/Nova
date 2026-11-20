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

    private class Host(private val installed: List<MusicApp>, private val paused: Boolean = false) : SkillHost {
        val launched = ArrayList<Pair<String, MusicApp>>()
        val played = ArrayList<String>()
        var remembered: MusicApp? = null
        override fun hasMusicApp(app: MusicApp) = app in installed
        override suspend fun playInMusicApp(query: String, app: MusicApp): Boolean { launched += query to app; return true }
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

    @Test fun `подсказка включает ту же песню в названном приложении`() = runTest {
        val host = Host(listOf(MusicApp.YANDEX, MusicApp.SPOTIFY))
        val e = env(host)
        val preview = e.engine.handle("включи песню кино — группа крови").text
        assertTrue(host.played.isNotEmpty(), preview)

        val reply = e.engine.handle("включи в яндекс музыке").text
        assertTrue(reply.contains("группа крови", ignoreCase = true), reply)
        assertEquals(listOf("кино — группа крови" to MusicApp.YANDEX), host.launched)
    }

    @Test fun `нет музыкальных приложений - честно говорим`() = runTest {
        val host = Host(emptyList())
        val reply = env(host).engine.handle("включи музыку").text
        assertTrue(reply.contains("нет музыкального приложения"), reply)
    }
}
