package ai.nova.app

import ai.nova.app.voice.NovaVoiceModels
import ai.nova.core.voice.SpeechText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Настоящая проверка офлайн-синтеза на устройстве: скачивание голоса, работа движка и ударения.
 * Скачивание занимает ~65 МБ, поэтому тест идёт только с сетью и по явной команде (см. testTag).
 */
@RunWith(AndroidJUnit4::class)
class NovaVoiceAudioTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun femaleVoiceDownloadsAndSpeaks() {
        val models = NovaVoiceModels(context)
        val id = "vera"
        if (!models.isReady(id)) {
            println("Скачиваю голос $id…")
            val started = System.currentTimeMillis()
            val ok = kotlinx.coroutines.runBlocking { models.download(id) }
            println("Голос $id: $ok за ${(System.currentTimeMillis() - started) / 1000} с, состояние ${models.state(id).value}")
            // Голос не скачался — это и есть тот отказ, который видит пользователь в настройках.
            assertTrue("голос $id не скачался: ${models.state(id).value}", ok)
        }
        assertTrue("голос $id не установлен", models.isReady(id))

        val tts = engine(id)
        try {
            val plain = speak(tts, "Она звонит мне про каталог")
            assertTrue("движок вернул пустой звук", plain.size > 5_000)
            // Тот же текст дважды — тот же звук: генерация детерминированная (шум отключён).
            assertArrayEquals(speak(tts, "Она звонит мне про каталог"), plain, 0f)

            // Числа и суммы уходят в синтез словами — их движок читает верно.
            val money = SpeechText.forSpeech("Списание 890,50 ₽ и 12 штук")
            assertFalse("в тексте для речи остались цифры: $money", money.any(Char::isDigit))
            assertTrue("сумма не озвучена", speak(tts, money).size > 5_000)

            // Рецептные сокращения раньше читались по буквам — «гэ-эр», «ша-тэ», «эс-тэ».
            val recipe = SpeechText.forSpeech("Возьми 200 гр муки, 2 шт яйца и 1 ст. л. сахара.")
            assertFalse("сокращения не раскрыты: $recipe", Regex("""\b(?:гр|шт|ст|ч)\b""").containsMatchIn(recipe))
            assertTrue("рецепт не озвучен: $recipe", speak(tts, recipe).size > 5_000)
        } finally {
            tts.release()
        }
    }

    @Test
    fun longAnswerIsSpokenToTheEnd() {
        val models = NovaVoiceModels(context)
        val ready = NovaVoiceModels.VOICES.firstOrNull { models.isReady(it.id) }
        org.junit.Assume.assumeTrue("ни один голос не скачан — проверять нечего", ready != null)
        val tts = engine(ready!!.id)
        try {
            val text = "Вот рецепт борща. " + (1..25).joinToString(" ") {
                "Шаг $it: нарежьте овощи, добавьте бульон и варите двадцать минут."
            }
            val phrases = ai.nova.app.voice.NovaVoice.split(text)
            assertTrue("длинный текст не поделился на фразы", phrases.size > 5)
            // Каждая фраза синтезируется: ни одна не теряется и не обрезается движком.
            for (phrase in phrases) {
                val spoken = SpeechText.forSpeech(phrase)
                val samples = speak(tts, spoken)
                assertTrue("фраза не озвучена: $spoken", samples.size > 2_000)
            }
        } finally {
            tts.release()
        }
    }

    private fun engine(id: String): OfflineTts {
        val models = NovaVoiceModels(context)
        val dir = models.dir(id)
        val voice = models.voice(id)
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, voice.modelFile).absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath,
            dataDir = File(dir, "espeak-ng-data").absolutePath,
            // Шум выключен: без него одинаковый текст даёт одинаковый звук и тест может сравнивать.
            noiseScale = 0f,
            noiseScaleW = 0f,
            lengthScale = 1f,
        )
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"))
        return OfflineTts(config = config)
    }

    private fun speak(tts: OfflineTts, text: String): FloatArray = tts.generate(text, 0, 1f).samples
}
