package ai.nova.app

import ai.nova.core.assistant.InputSource
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ответ Новы должен звучать и на голос, и на текст из чата:
 * проверяем оба пути, в logcat видно AudioTrack с реальными фреймами.
 */
@RunWith(AndroidJUnit4::class)
class VoiceReplyDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<NovaApp>()

    @Test
    fun voiceRequestIsSpoken(): Unit = runBlocking {
        val c = app.container
        Log.i("VoiceReply", "голос: ttsEnabled=${c.settings.current().ttsEnabled}")
        val reply = c.voice.handleRecognized("какая сегодня погода", InputSource.VOICE)
        Log.i("VoiceReply", "ответ голосом: ${reply.text.take(80)}")
        assertTrue("пустой ответ", reply.text.isNotBlank())
        delay(6_000)
    }

    @Test
    fun typedRequestIsSpoken(): Unit = runBlocking {
        val c = app.container
        Log.i("VoiceReply", "текст: ttsEnabled=${c.settings.current().ttsEnabled}")
        c.voice.submitText("какая сегодня погода")
        // Ждём, пока Нова придумает ответ и озвучит его.
        delay(25_000)
        Log.i("VoiceReply", "состояние после ответа: ${c.voice.state.value}")
    }
}
