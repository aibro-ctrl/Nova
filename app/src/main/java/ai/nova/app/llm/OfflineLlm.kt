package ai.nova.app.llm

import android.content.Context
import android.os.Build
import ai.nova.core.ai.ChatMessage
import ai.nova.core.ai.LocalChat
import ai.nova.core.ai.LocalModels
import ai.nova.core.ai.ModelFile
import ai.nova.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Мост к нативной библиотеке llama.cpp (libnova_llm.so, только arm64). */
object LlamaNative {
    fun interface TokenListener { fun onToken(piece: String): Boolean }

    /** null — библиотека загружена; иначе причина, почему модель на этом телефоне недоступна. */
    val unsupportedReason: String? by lazy {
        when {
            Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" } -> "нужен 64-битный процессор ARM"
            // Сборка оптимизирована под ARMv8.2 с dot product (телефоны примерно с 2018 года).
            !cpuHasDotProd() -> "процессор телефона слишком старый для офлайн-модели"
            else -> runCatching { System.loadLibrary("nova_llm"); init(); null }.getOrElse { "библиотека не загрузилась: ${it.message}" }
        }
    }

    private fun cpuHasDotProd(): Boolean = runCatching {
        File("/proc/cpuinfo").readLines().any { it.startsWith("Features") && it.contains("asimddp") }
    }.getOrDefault(false)

    @JvmStatic external fun init()
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun generate(handle: Long, messages: Array<String>, maxTokens: Int, temperature: Float, listener: TokenListener?): String

    /** Разобрать промпт заранее (без генерации): к первому вопросу он уже лежит в памяти контекста. */
    @JvmStatic external fun prefill(handle: Long, messages: Array<String>): Int
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun free(handle: Long)
}

/**
 * Офлайн-модель для разговора. По умолчанию — встроенная Qwen 2.5 1.5B Instruct (Apache-2.0), ~1 ГБ:
 * скачивается из служебного релиза models-v1 этого репозитория (с докачкой) и проверяется по sha256.
 * Активную модель можно сменить на любую .gguf с Hugging Face (см. [ModelCatalog]) — выбор хранится в настройках.
 * Загружается в память при первом вопросе и выгружается после 5 минут без вопросов — чтобы не держать 1,5 ГБ ОЗУ.
 */
class OfflineLlm(
    private val context: Context,
    private val scope: CoroutineScope,
    private val catalog: ModelCatalog = ModelCatalog(),
    /** Системный промпт, с которым модель будет разговаривать: его разбираем заранее, при прогреве. */
    private val systemPrompt: () -> String = { "" },
) : LocalChat {
    sealed interface State {
        data object Missing : State
        /** Ищем, есть ли модель новее: пара секунд, интернета может и не быть. */
        data object Resolving : State
        data class Downloading(val progress: Float) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
        data class Unsupported(val reason: String) : State
    }

    private val dir = File(context.filesDir, "llm").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("nova_llm", Context.MODE_PRIVATE)

    /** Какая модель выбрана: встроенная Qwen 2.5 или скачанная с Hugging Face. */
    data class Active(val file: String, val url: String, val sha256: String?, val size: Long, val label: String)

    @Volatile private var model: Active = readActive()
    private val file: File get() = File(dir, model.file)
    private val part: File get() = File(dir, model.file + ".part")
    private val _state = MutableStateFlow(fileState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Короткая подсказка после «Обновить»: «Уже самая свежая модель» и подобное. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val lock = Mutex()
    private val downloadLock = Mutex()
    @Volatile private var handle = 0L
    /** stop() и free() не должны пересекаться: иначе stop попадёт в уже освобождённую память. */
    private val nativeLock = Any()
    /** «Стоп» сказали во время загрузки модели или до начала ответа — не теряем его. */
    @Volatile private var cancelled = false
    /** Человек уже спросил: разбирать промпт заранее поздно, вопрос и так ждёт. */
    @Volatile private var answered = false
    private var unloadJob: Job? = null

    init {
        // Проверка процессора и загрузка библиотеки (~десятки мс) — не на главном потоке при запуске.
        scope.launch(Dispatchers.Default) {
            LlamaNative.unsupportedReason?.let { _state.value = State.Unsupported(it) }
            // Модель читается с флеша ~1,5 с. Пусть это происходит, пока человек ещё набирает вопрос,
            // а не после нажатия: иначе первый ответ кажется вдвое дольше.
            delay(WARMUP_DELAY_MS)
            if (_state.value == State.Ready && enabled) {
                // Под тем же замком, что и ответ: иначе первый вопрос и прогрев загрузят модель дважды.
                lock.withLock {
                    withContext(Dispatchers.Default) {
                        val h = ensureLoaded() ?: return@withContext
                        // Системную часть промпта разбираем сейчас: первый вопрос начнёт отвечать сразу.
                        runCatching {
                            val sys = if (answered) "" else systemPrompt()
                            // Пустая реплика пользователя: шаблон Gemma склеивает характер с первым вопросом,
                            // поэтому такой промпт — начало любого настоящего. Первый вопрос разбирает только себя.
                            if (sys.isNotBlank()) {
                                val n = LlamaNative.prefill(h, arrayOf("system", sys, "user", ""))
                                Logger.w(TAG, "прогрев: характер в памяти, ${n} токенов")
                            }
                        }
                    }
                }
                scheduleUnload()
            }
        }
    }

    private fun fileState(): State = if (file.isFile && prefs.getBoolean(KEY_VERIFIED, false)) State.Ready else State.Missing

    private fun initialState(): State {
        LlamaNative.unsupportedReason?.let { return State.Unsupported(it) }
        return fileState()
    }

    /** Пользователь включил офлайн-модель (скачал) и не выключил. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); if (!v) scope.launch { unload() } }

    override val available: Boolean get() = enabled && _state.value == State.Ready

    /** Размер активной модели — для экрана настроек. */
    val sizeBytes: Long get() = model.size

    /** Название активной модели: «Qwen 2.5 1.5B · Q4_K_M» или выбранный файл с Hugging Face. */
    val modelLabel: String get() = model.label

    /** Встроенная модель (та, что по умолчанию): к ней можно вернуться в один шаг. */
    val isDefaultModel: Boolean get() = model.file == FILE

    private fun readActive(): Active = Active(
        file = prefs.getString(KEY_FILE, null) ?: FILE,
        url = prefs.getString(KEY_URL, null) ?: MODEL_URL,
        sha256 = prefs.getString(KEY_SHA, null) ?: SHA256,
        size = prefs.getLong(KEY_SIZE, SIZE),
        label = prefs.getString(KEY_LABEL, null) ?: DEFAULT_LABEL,
    )

    private fun writeActive(m: Active) {
        prefs.edit().putString(KEY_FILE, m.file).putString(KEY_URL, m.url)
            .putString(KEY_SHA, m.sha256.orEmpty()).putLong(KEY_SIZE, m.size).putString(KEY_LABEL, m.label).apply()
        model = m
    }

    /** Скачать и включить другую модель (.gguf с Hugging Face). */
    suspend fun download(other: ModelFile): Boolean {
        // Уже скачана — просто переключаемся на неё.
        val target = Active(other.fileName, other.url, other.sha256, other.size, LocalModels.label(other) + " · " + LocalModels.sizeText(other.size))
        if (File(dir, other.fileName).isFile && other.size > 0 && File(dir, other.fileName).length() == other.size) {
            writeActive(target)
            prefs.edit().putBoolean(KEY_VERIFIED, true).putBoolean(KEY_ENABLED, true).apply()
            // Старая модель больше не нужна — освобождаем место.
            cleanup(keep = target.file)
            unloadAsync()
            _state.value = State.Ready
            return true
        }
        return downloadLock.withLock {
            // Другая модель уже была активной — начинаем её загрузку с нуля.
            if (model.file != target.file) File(dir, model.file + ".part").delete()
            writeActive(target)
            prefs.edit().putBoolean(KEY_VERIFIED, false).apply()
            _state.value = State.Downloading(0f)
            fetch(target)
        }
    }

    /**
     * Обновление модели в одно нажатие: подбираем модель под этот телефон и скачиваем её.
     * Если каталог недоступен (нет сети, Hugging Face заблокирован) — берём встроенную модель,
     * чтобы кнопка всегда доводила дело до рабочей офлайн-модели.
     */
    suspend fun update(): Boolean {
        LlamaNative.unsupportedReason?.let { _state.value = State.Unsupported(it); return false }
        val current = _state.value
        if (current is State.Downloading || current is State.Verifying || current is State.Resolving) return false
        _state.value = State.Resolving
        _notice.value = null
        val target = runCatching { catalog.recommend(totalRam()) }
            .onFailure { Logger.w(TAG, "Каталог моделей недоступен", it) }
            .getOrNull()
        // Уже стоит самая свежая — не тратим гигабайты трафика.
        if (target != null && target.fileName == model.file && file.isFile && file.length() == target.size) {
            prefs.edit().putBoolean(KEY_VERIFIED, true).putBoolean(KEY_ENABLED, true).apply()
            _notice.value = "Уже самая свежая модель"
            _state.value = State.Ready
            return true
        }
        if (target != null) {
            if (download(target)) { _notice.value = "Модель обновлена"; return true }
            // Не получилось с новой — вернёмся к встроенной и попробуем её.
            Logger.w(TAG, "Свежая модель не скачалась — пробую встроенную")
        }
        writeActive(Active(FILE, MODEL_URL, SHA256, SIZE, DEFAULT_LABEL))
        prefs.edit().putBoolean(KEY_VERIFIED, file.isFile).apply()
        if (file.isFile) {
            unloadAsync()
            _state.value = State.Ready
            return true
        }
        return download()
    }

    /** Сколько всего памяти у телефона: по нему выбираем, 4B или 2B. */
    private fun totalRam(): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return@runCatching 0L
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /** Вернуться к встроенной модели Qwen 2.5 1.5B (если её файл ещё на телефоне — сразу готово). */
    fun useDefault() {
        if (isDefaultModel) return
        writeActive(Active(FILE, MODEL_URL, SHA256, SIZE, DEFAULT_LABEL))
        prefs.edit().putBoolean(KEY_VERIFIED, file.isFile).apply()
        unloadAsync()
        _state.value = initialState()
    }

    suspend fun download(): Boolean = downloadLock.withLock {
        if (_state.value is State.Unsupported) return@withLock false
        if (_state.value == State.Ready) return@withLock true
        fetch(model)
    }

    /** Загрузка активной модели. Вызывать только под [downloadLock]: Mutex не реентрантный. */
    private suspend fun fetch(current: Active): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                streamToPart(current)
                _state.value = State.Verifying
                val expected = current.sha256?.takeIf { it.length == 64 }
                if (expected != null && sha256(part) != expected) {
                    part.delete()
                    error("файл повреждён — попробуйте ещё раз")
                }
                if (current.size > 0 && part.length() != current.size) {
                    part.delete()
                    error("файл скачался не целиком — попробуйте ещё раз")
                }
                file.delete()
                if (!part.renameTo(file)) error("не удалось сохранить модель")
                prefs.edit().putBoolean(KEY_VERIFIED, true).putBoolean(KEY_ENABLED, true).apply()
                cleanup(keep = current.file)
                _state.value = State.Ready
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Модель не скачана", e)
                val need = current.size.coerceAtLeast(SIZE)
                _state.value = State.Failed(
                    when (e) {
                        is java.net.UnknownHostException -> "нет интернета"
                        is java.net.SocketTimeoutException -> "сервер не отвечает — нажмите ещё раз, загрузка продолжится"
                        is java.io.IOException -> if (dir.usableSpace < need) "не хватает места (нужно ~${LocalModels.sizeText(need)})" else "связь прервалась — нажмите ещё раз, загрузка продолжится"
                        else -> e.message ?: "ошибка загрузки"
                    },
                )
                false
            }
        }
    }

    private fun unloadAsync() { scope.launch { unload() } }

    /** Докачка: если связь оборвалась, продолжаем с того же места (Range). */
    private fun streamToPart(m: Active) {
        val size = m.size.coerceAtLeast(1)
        if (dir.usableSpace + part.length() < size + 50_000_000) error("не хватает места (нужно ~${LocalModels.sizeText(size)})")
        var url = m.url
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NovaAssistant")
                if (part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            if (conn.responseCode in 300..399 && redirects++ < 6) {
                url = java.net.URL(URL(url), conn.getHeaderField("Location") ?: error("редирект без адреса")).toString()
                conn.disconnect()
                continue
            }
            break
        }
        val resume = conn.responseCode == 206
        if (conn.responseCode == 416) { conn.disconnect(); return } // уже скачано целиком
        if (conn.responseCode !in 200..299) error("сервер ответил ${conn.responseCode}")
        if (!resume) part.delete()
        var done = part.length()
        var last = 0f
        conn.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = (done.toFloat() / size).coerceIn(0f, 1f)
                    if (p - last >= 0.005f) { last = p; _state.value = State.Downloading(p) }
                }
            }
        }
        conn.disconnect()
    }

    /** На телефоне держим только активную модель: иначе обновления копят по гигабайту. */
    private fun cleanup(keep: String) {
        dir.listFiles()?.forEach { f ->
            if (f.name != keep && f.name != "$keep.part") runCatching { f.delete() }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete() {
        _notice.value = null
        scope.launch {
            unload()
            file.delete(); part.delete()
            prefs.edit().putBoolean(KEY_VERIFIED, false).apply()
            // Удалили выбранную с Hugging Face — возвращаемся к встроенной модели.
            if (!isDefaultModel) writeActive(Active(FILE, MODEL_URL, SHA256, SIZE, DEFAULT_LABEL))
            _state.value = initialState()
        }
    }

    override suspend fun reply(
        system: String,
        history: List<ChatMessage>,
        maxTokens: Int,
        onChunk: ((String) -> Unit)?,
    ): String? {
        if (!available || LlamaNative.unsupportedReason != null) return null
        // Второй телефон в фоне съел память — не рискуем закрытием приложения.
        if (handle == 0L && !memoryOk()) { Logger.w(TAG, "мало памяти — отвечаю без модели"); return null }
        cancelled = false
        answered = true
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val h = ensureLoaded() ?: return@withContext null
                if (cancelled) return@withContext null
                // Недавний разговор, но не длиннее контекста (2048 токенов): старые реплики отбрасываем,
                // а последний вопрос пользователя берём всегда (при необходимости — укороченным).
                val msgs = ArrayList<String>()
                msgs += "system"; msgs += clean(system).take(1500)
                val last = history.lastOrNull()
                var budget = HISTORY_CHARS - (last?.content?.length ?: 0).coerceAtMost(LAST_CHARS)
                val earlier = history.dropLast(1).takeLast(8).reversed().takeWhile { m -> budget -= m.content.length; budget > 0 }.reversed()
                for (m in earlier + listOfNotNull(last)) {
                    msgs += if (m.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"
                    msgs += clean(m.content).let { if (m === last) it.take(LAST_CHARS) else it }
                }
                // Отмена (пользователь ушёл, сработал общий таймаут) должна останавливать генерацию:
                // иначе модель держит память и очередь следующих вопросов.
                val job = kotlinx.coroutines.currentCoroutineContext()
                val listener = LlamaNative.TokenListener { piece ->
                    val go = !cancelled && job[kotlinx.coroutines.Job]?.isActive != false
                    // Куски уходят в озвучку сразу: она начинает говорить, пока модель пишет дальше.
                    if (go) runCatching { onChunk?.invoke(piece) }
                    go
                }
                // Модель может «задуматься» надолго: по истечении времени останавливаем её, чтобы
                // разговор не замирал и следующая фраза не ждала бесконечно.
                val watchdog = scope.launch {
                    delay(REPLY_TIMEOUT_MS)
                    Logger.w(TAG, "Ответ не уложился в ${REPLY_TIMEOUT_MS / 1000} с — останавливаю модель")
                    cancelled = true
                    synchronized(nativeLock) { LlamaNative.stop(h) }
                }
                val out = try {
                    runCatching { LlamaNative.generate(h, msgs.toTypedArray(), maxTokens, 0.6f, listener) }
                        .onFailure { Logger.w(TAG, "Генерация не удалась", it) }.getOrNull()
                } finally {
                    watchdog.cancel()
                }
                scheduleUnload()
                out?.let { LocalModels.stripThinking(it) }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Остановить ответ («стоп»). */
    fun stop() {
        cancelled = true
        synchronized(nativeLock) { handle.takeIf { it != 0L }?.let { LlamaNative.stop(it) } }
    }

    /**
     * Модель ~1 ГБ плюс контекст: если свободной памяти мало, телефон может «убить» приложение
     * (OOM-killer) — тогда лучше ответить без модели, чем закрыться.
     */
    private fun memoryOk(): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return@runCatching true
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // Нужен сам файл модели плюс запас на контекст и работу приложения.
        info.availMem >= model.size + MEMORY_RESERVE
    }.getOrDefault(true)

    private fun ensureLoaded(): Long? {
        if (handle != 0L) return handle
        if (!memoryOk()) { Logger.w(TAG, "мало свободной памяти для офлайн-модели"); return null }
        val threads = Runtime.getRuntime().availableProcessors().let { (it - 2).coerceIn(2, 6) }
        val h = runCatching { LlamaNative.load(file.absolutePath, CONTEXT, threads) }.getOrDefault(0L)
        if (h == 0L) { Logger.w(TAG, "Модель не загрузилась"); return null }
        handle = h
        return h
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(IDLE_UNLOAD_MS); unload() }
    }

    private suspend fun unload() = lock.withLock {
        val h = synchronized(nativeLock) { handle.also { handle = 0L } }
        if (h != 0L) withContext(Dispatchers.Default) { runCatching { LlamaNative.free(h) } }
    }

    /** Эмодзи и прочие символы вне BMP модели не нужны, а в JNI передаются неудобно. */
    // «<|im_start|>» в тексте (сообщение, страница) не должно стать служебной меткой модели.
    private fun clean(s: String) = buildString { s.forEach { ch -> if (!Character.isSurrogate(ch)) append(ch) } }
        .replace("<|", "< |").replace("|>", "| >").take(2000)

    companion object {
        private const val TAG = "OfflineLlm"
        private const val FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MODEL_URL = "https://github.com/Lucky2356/loli_ai/releases/download/models-v1/loli-llm-qwen2.5-1.5b-q4_k_m.gguf"
        private const val SHA256 = "1adf0b11065d8ad2e8123ea110d1ec956dab4ab038eab665614adba04b6c3370"
        const val SIZE = 986_048_768L
        private const val CONTEXT = 2048
        /** Сколько ждём ответ: на телефоне это секунды, но «задумавшуюся» модель пора останавливать. */
        private const val REPLY_TIMEOUT_MS = 40_000L
        /** Загружаем модель заранее, но не в первую секунду запуска: пусть сначала отрисуется экран. */
        private const val WARMUP_DELAY_MS = 1_500L
        /** Столько модель держим в памяти после ответа: следующий вопрос ждать не будет. */
        private const val IDLE_UNLOAD_MS = 10 * 60_000L
        /** Запас памяти сверх файла модели: контекст, граф вычислений, само приложение. */
        private const val MEMORY_RESERVE = 900_000_000L
        /** Русский текст — примерно 2–3 символа на токен: так промпт и ответ помещаются в контекст. */
        private const val HISTORY_CHARS = 2400
        private const val LAST_CHARS = 1200
        private const val KEY_VERIFIED = "verified"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FILE = "file"
        private const val KEY_URL = "url"
        private const val KEY_SHA = "sha"
        private const val KEY_SIZE = "size"
        private const val KEY_LABEL = "label"
        private const val DEFAULT_LABEL = "Qwen 2.5 1.5B · Q4_K_M"
    }
}
