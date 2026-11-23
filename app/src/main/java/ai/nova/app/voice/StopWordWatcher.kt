package ai.nova.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import ai.nova.core.data.NovaJson
import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Recognizer

/** Слова, которые всегда останавливают Нова — и в разговоре, и пока она говорит. */
object StopWords {
    private val PHRASES = setOf(
        "стоп", "стой", "хватит", "замолчи", "замолчи пожалуйста", "тихо", "остановись", "прекрати", "перестань", "молчи",
        "стоп стоп", "всё стоп", "все стоп", "нова стоп", "стоп нова", "хватит говорить",
    )

    /** Вся фраза — команда остановки (имя ассистента и «пожалуйста» не мешают). */
    fun isStop(text: String, name: String = "нова"): Boolean {
        val skip = setOf(RuTokenizer.normalize(name).trim(), "пожалуйста", "нова")
        val words = RuTokenizer.normalize(text).split(' ', ',', '.', '!', '?').map { it.trim() }.filter { it.isNotEmpty() && it !in skip }
        if (words.isEmpty()) return false
        return words.joinToString(" ") in PHRASES || words.all { it in SINGLE }
    }

    private val SINGLE = setOf("стоп", "стой", "хватит", "замолчи", "тихо", "молчи", "прекрати", "перестань", "остановись")

    /** Словарь для распознавателя: только слова остановки, всё остальное — «[unk]». */
    val GRAMMAR: String = (SINGLE.toList() + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }
}

/** Чем закончилось слушание во время речи Нова. */
enum class Interruption {
    NONE,
    /** Сказали «стоп»/«хватит» — разговор заканчивается. */
    STOP_WORD,
    /** Просто начали говорить — озвучку останавливаем, но разговор продолжаем. */
    SPEECH,
}

/**
 * Слушает «стоп», пока Нова говорит. Отдельная маленькая грамматика Vosk знает только слова остановки,
 * а запись идёт с эхоподавлением — собственный голос Нова почти не мешает. Всё на устройстве.
 *
 * Кроме слова «стоп» ловится и просто начало речи ([Interruption.SPEECH]): человек привык перебивать
 * собеседника, а не ждать конца фразы. Порог громкости выше шумового фона в несколько раз и держится
 * не меньше [SPEECH_FRAMES] кадров подряд — щелчок или эхо собственного голоса не считаются.
 */
class StopWordWatcher(private val context: Context, private val engine: VoskEngine, private val models: VoskModelManager) {

    /** Пользователь сказал «стоп» — разговор заканчивается. Обёртка над [awaitInterruption]. */
    @SuppressLint("MissingPermission")
    suspend fun awaitStop(): Boolean = awaitInterruption() == Interruption.STOP_WORD

    /**
     * Слушает, пока Нова говорит: «стоп» останавливает разговор, [bargeIn] — любая речь останавливает озвучку.
     * [Interruption.NONE] — слушать нельзя (нет модели, микрофона или разрешения). Отменяется снаружи.
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitInterruption(bargeIn: Boolean = false): Interruption = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext Interruption.NONE
        // Встроенная модель распаковывается при первом ответе — со следующего ответа «стоп» работает и во время речи.
        if (!models.ensureReady()) return@withContext Interruption.NONE
        val model = engine.model() ?: return@withContext Interruption.NONE
        val rate = VoskSpeechProvider.SAMPLE_RATE.toInt()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext Interruption.NONE
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            recognizer = Recognizer(model, rate.toFloat(), StopWords.GRAMMAR).apply { setWords(true) }
            // VOICE_COMMUNICATION включает системное эхоподавление: динамик (ответ Нова) вычитается из микрофона.
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext Interruption.NONE
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            record.startRecording()
            val buffer = ShortArray(minBuf / 2)
            var partial = ""
            var floor = 0.0
            var loudFrames = 0
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                // Ошибка чтения (микрофон занят или отобран) — выходим; пустое чтение не должно грузить процессор.
                if (n < 0) return@withContext Interruption.NONE
                if (n == 0) { kotlinx.coroutines.delay(20); continue }
                if (bargeIn) {
                    val rms = rms(buffer, n)
                    // Шумовой фон обновляем только на тихих кадрах, иначе собственный голос поднимет порог.
                    if (rms < floor * 1.5 || floor == 0.0) floor = floor * 0.9 + rms * 0.1
                    val threshold = maxOf(MIN_RMS, floor * 3.5)
                    loudFrames = if (rms > threshold) loudFrames + 1 else 0
                    if (loudFrames >= SPEECH_FRAMES) return@withContext Interruption.SPEECH
                }
                if (recognizer.acceptWaveForm(buffer, n)) {
                    if (heardStop(recognizer.result)) return@withContext Interruption.STOP_WORD
                    partial = ""
                } else {
                    // Ждать конца фразы не нужно: слово распознаётся уже на лету.
                    // Два одинаковых промежуточных результата подряд — это не случайный шум, останавливаем ответ.
                    val now = partialText(recognizer.partialResult)
                    if (now.isNotEmpty() && StopWords.isStop(now)) {
                        if (now == partial) return@withContext Interruption.STOP_WORD
                        partial = now
                    } else {
                        partial = ""
                    }
                }
            }
            Interruption.NONE
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось слушать «стоп»", e)
            Interruption.NONE
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { recognizer?.close() }
        }
    }

    /** Текст промежуточного результата Vosk ({"partial":"стоп"}). */
    private fun partialText(json: String): String =
        runCatching { NovaJson.parseToJsonElement(json).jsonObject["partial"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .getOrDefault("")

    /** Итог фразы: только слово остановки и с уверенностью распознавателя — иначе это эхо или шум. */
    private fun heardStop(json: String): Boolean {
        val obj = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return false
        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!StopWords.isStop(text)) return false
        val words = runCatching { obj["result"]?.jsonArray.orEmpty() }.getOrDefault(emptyList())
        val conf = words.mapNotNull { runCatching { it.jsonObject["conf"]?.jsonPrimitive?.doubleOrNull }.getOrNull() }
        return conf.isEmpty() || conf.average() >= MIN_CONFIDENCE
    }

    /** Средняя громкость кадра (0…32767): по ней видно начало речи, даже если слова ещё не распознаны. */
    private fun rms(buffer: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buffer[i].toDouble(); sum += v * v }
        return kotlin.math.sqrt(sum / n.coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "StopWord"
        private const val MIN_CONFIDENCE = 0.85
        /** Нижний порог речи: тише этого — шум комнаты, а не голос. */
        private const val MIN_RMS = 900.0
        /** Сколько кадров подряд должно быть громко: ~300 мс при кадре 40 мс. */
        private const val SPEECH_FRAMES = 8
    }
}
