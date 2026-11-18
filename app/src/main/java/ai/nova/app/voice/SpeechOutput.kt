package ai.nova.app.voice

import ai.nova.core.util.Logger
import ai.nova.core.voice.TextToSpeechProvider

/**
 * Кто озвучивает ответы Нова.
 *  - «Голос Нова» (встроенный) — одинаково по-русски на любом телефоне;
 *  - синтезатор телефона — только проверенный (Google, Samsung, RHVoice) и только если в нём есть русский.
 * Непроверенным синтезаторам (Vivo/iFlytek, Huawei, Xiaomi, Oppo…) не верим: они отвечают «русский есть»,
 * а читают текст китайской моделью. Пока встроенный голос не скачан, такой телефон отвечает только текстом.
 */
class SpeechOutput(
    private val system: () -> AndroidTtsProvider,
    private val systemCreated: () -> Boolean,
    private val nova: NovaVoice,
    private val mode: () -> Mode,
) : TextToSpeechProvider {

    enum class Mode { AUTO, NOVA, SYSTEM }

    enum class Engine { NOVA, SYSTEM, NONE }

    /** Чем Нова будет говорить по-русски прямо сейчас. */
    suspend fun current(): Engine = when (mode()) {
        Mode.NOVA -> if (nova.isAvailable()) Engine.NOVA else Engine.NONE
        Mode.SYSTEM -> Engine.SYSTEM
        Mode.AUTO -> when {
            nova.isAvailable() -> Engine.NOVA
            systemTrusted() -> Engine.SYSTEM
            else -> Engine.NONE
        }
    }

    /** Синтезатор телефона проверенный и знает русский. */
    suspend fun systemTrusted(): Boolean {
        val s = system()
        val status = s.checkRussian()
        return status == AndroidTtsProvider.RuStatus.OK && s.activeEngine() in TRUSTED
    }

    /** Нужно ли предложить скачать встроенный голос (на этом телефоне по-русски сейчас говорить нечем). */
    suspend fun needsNovaVoice(): Boolean = current() == Engine.NONE

    override val isReady: Boolean get() = nova.isAvailable() || (systemCreated() && system().isReady)

    override suspend fun speak(text: String) = speakIn(text, null)

    override suspend fun speakIn(text: String, language: String?) {
        if (text.isBlank()) return
        // Перевод на другой язык — только синтезатором телефона (у встроенного голоса лишь русский).
        if (language != null && language != "ru") { system().speakIn(text, language); return }
        when (current()) {
            Engine.NOVA -> if (!nova.speak(text)) system().speak(text)
            Engine.SYSTEM -> system().speak(text)
            Engine.NONE -> Logger.w(TAG, "Нет русского голоса — ответ только текстом")
        }
    }

    override fun stop() {
        nova.stop()
        if (systemCreated()) system().stop()
    }

    override fun shutdown() {
        nova.release()
        if (systemCreated()) system().shutdown()
    }

    companion object {
        private const val TAG = "Speech"

        /** Синтезаторы, которые действительно говорят по-русски, если так отвечают. */
        val TRUSTED = setOf(
            AndroidTtsProvider.GOOGLE_TTS,
            "com.samsung.SMT",
            "com.github.olga_yakovleva.rhvoice.android",
        )
    }
}
