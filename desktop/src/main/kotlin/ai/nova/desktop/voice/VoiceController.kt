package ai.nova.desktop.voice

import ai.nova.core.assistant.AssistantReply
import ai.nova.core.assistant.InputSource
import ai.nova.core.util.Logger
import ai.nova.core.voice.SpeechText
import ai.nova.desktop.DesktopContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Голос на компьютере: кнопка микрофона (или Ctrl+Пробел) → фраза → Нова → ответ вслух.
 * Если Нова задала вопрос, в диалоговом режиме сразу слушает ответ (до трёх раз подряд).
 * Повторное нажатие на любой стадии — остановить: скачивание модели, слушание или речь.
 */
class VoiceController(private val c: DesktopContainer) {
    sealed interface State {
        data object Idle : State
        /** [percent] null — модель загружается в память (без процентов). */
        data class Preparing(val percent: Int?) : State
        data class Listening(val partial: String, val level: Float) : State
        data class Thinking(val heard: String) : State
        data class Speaking(val text: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    private var errorJob: Job? = null
    /** Пользователь прервал — диалог не продолжаем. */
    @Volatile private var interrupted = false

    val input = SpeechInput(c.dataDir)
    /** Синтезатор Windows — запасной голос (и для тех, кто выбрал голоса Windows). */
    val output = SpeechOutput()
    /** Встроенный русский «Голос Нова» — как на телефоне. */
    val nova = NovaVoice(c.dataDir)

    /** Можно ли вообще говорить на этом компьютере. */
    val canSpeak: Boolean get() = nova.anyReady() || output.available

    /** Проговорить текст выбранным голосом и дождаться конца (блокирующий вызов, из фонового потока). */
    fun say(text: String) {
        val s = c.settings.value
        val spoken = s.voiceMode == "nova" && nova.anyReady() && nova.speak(text, s.novaVoice, s.novaSpeed / 100f)
        if (!spoken) output.speak(text, s.voiceName, s.speechRate)
    }

    private fun stopSpeech() { nova.stop(); output.stop() }

    /** Нажатие на микрофон: начать слушать или остановить то, что идёт сейчас. */
    fun toggle() {
        when (_state.value) {
            is State.Listening -> input.stop()
            is State.Speaking -> { interrupted = true; stopSpeech() }
            is State.Preparing -> stopAll()
            is State.Thinking -> Unit
            else -> start()
        }
    }

    fun stopAll() {
        interrupted = true
        input.stop(); stopSpeech(); job?.cancel()
        _state.value = State.Idle
    }

    /** Выход из программы. */
    fun shutdown() {
        interrupted = true
        input.stop(); job?.cancel()
        nova.release(); output.shutdown(); input.close()
    }

    /** Подготовить заранее то, что уже есть на диске: синтезатор и (если модель на месте) распознавание. */
    fun warmUp() {
        val s = c.settings.value
        if (!s.voiceReplies) return
        if (s.voiceMode == "nova" && nova.anyReady()) {
            Thread({ runCatching { nova.warmUp(s.novaVoice) } }, "nova-voice-warmup").apply { isDaemon = true }.start()
        } else {
            output.warmUp()
        }
    }

    private fun fail(message: String) {
        _state.value = State.Error(message)
        errorJob?.cancel()
        // Ошибка видна 8 секунд, потом строка состояния сама исчезает.
        errorJob = c.scope.launch { delay(8_000); if (_state.value is State.Error) _state.value = State.Idle }
    }

    private fun start() {
        if (job?.isActive == true) return
        interrupted = false
        errorJob?.cancel()
        job = c.scope.launch(Dispatchers.IO) {
            try {
                if (input.modelDir() == null) {
                    _state.value = State.Preparing(0)
                    if (!input.ensureModel { _state.value = State.Preparing(it) }) {
                        if (interrupted) { _state.value = State.Idle; return@launch }
                        fail("Не удалось скачать модель речи. Проверьте интернет и попробуйте ещё раз.")
                        return@launch
                    }
                }
                if (!input.hasMicrophone()) {
                    fail("Микрофон не найден. Подключите его и разрешите доступ: Параметры Windows → Конфиденциальность → Микрофон.")
                    return@launch
                }
                if (!input.loaded) {
                    _state.value = State.Preparing(null)
                    if (!input.prepare()) { fail("Модель речи повреждена. Переустановите Нова."); return@launch }
                    if (interrupted) { _state.value = State.Idle; return@launch }
                }
                var rounds = 0
                while (rounds < 3 && !interrupted) {
                    rounds++
                    _state.value = State.Listening("", 0f)
                    val heard = try {
                        input.listen { partial, level -> if (_state.value is State.Listening) _state.value = State.Listening(partial, level) }
                    } catch (e: Exception) {
                        Logger.w("Voice", "Микрофон недоступен", e)
                        fail("Микрофон занят другой программой или недоступен.")
                        return@launch
                    }
                    if (heard.isBlank() || interrupted) break
                    _state.value = State.Thinking(heard)
                    val reply = c.send(heard, InputSource.VOICE) ?: break
                    speak(reply)
                    val again = c.settings.value.dialogMode && (reply.awaitingAnswer || reply.awaitingConfirmation) && !reply.endsDialog
                    if (!again) break
                }
                if (_state.value !is State.Error) _state.value = State.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("Voice", "Голосовой режим упал", e)
                fail("Голос не сработал: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /** Ответ вслух (если включено). */
    suspend fun speak(reply: AssistantReply) {
        val s = c.settings.value
        if (!s.voiceReplies || !canSpeak || reply.text.isBlank() || interrupted) return
        _state.value = State.Speaking(reply.text)
        withContext(Dispatchers.IO) { say(SpeechText.forSpeech(reply.text)) }
        if (_state.value is State.Speaking) _state.value = State.Idle
    }
}
