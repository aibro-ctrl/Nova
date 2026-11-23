package ai.nova.app.voice

import android.content.Context
import ai.nova.core.nlp.Gender
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Встроенные русские голоса Нова (Piper VITS для sherpa-onnx). Скачиваются один раз из служебного
 * релиза voices-v1 этого же репозитория (оттуда же приходят обновления) и проверяются по sha256 —
 * подменённый или повреждённый файл не установится.
 */
class NovaVoiceModels(context: Context) {
    data class Voice(val id: String, val title: String, val subtitle: String, val sha256: String, val bytes: Long) {
        /** Род голоса берётся из подписи каталога («Мужской», «Женский, мягкий»), чтобы не дублировать данные. */
        val gender: Gender get() = if (subtitle.startsWith("Мужской", ignoreCase = true)) Gender.MASCULINE else Gender.FEMININE

        val url get() = "https://github.com/$REPO/releases/download/$RELEASE/loli-voice-ru-$id.zip"
        val folder get() = "vits-piper-ru_RU-$id-medium"
        val modelFile get() = "ru_RU-$id-medium.onnx"
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Installing : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "nova-voice")
    private val mutex = Mutex()
    private val states = VOICES.associate { it.id to MutableStateFlow<State>(if (isReady(it.id)) State.Ready else State.Missing) }

    fun state(id: String): StateFlow<State> = (states[id] ?: states.getValue(DEFAULT)).asStateFlow()

    fun voice(id: String): Voice = VOICES.firstOrNull { it.id == id } ?: VOICES.first { it.id == DEFAULT }

    fun dir(id: String): File = File(File(root, id), voice(id).folder)

    fun isReady(id: String): Boolean {
        val d = dir(id)
        return File(d, voice(id).modelFile).isFile && File(d, "tokens.txt").isFile && File(d, "espeak-ng-data").isDirectory &&
            !File(root, "$id/$INCOMPLETE").exists()
    }

    fun anyReady(): Boolean = VOICES.any { isReady(it.id) }

    /** Выбранный голос, а если он не скачан — любой уже скачанный (чтобы смена голоса по умолчанию не оставила без голоса). */
    fun effective(id: String): String = if (isReady(id)) id else VOICES.firstOrNull { isReady(it.id) }?.id ?: id

    /**
     * Голоса, подходящие к роду имени ассистента: мужское имя — только мужские, женское — только женские.
     * Род не определился — показываем все, чтобы не отнимать выбор.
     */
    fun forGender(gender: Gender): List<Voice> = when {
        gender.isMasculine -> VOICES.filter { it.gender.isMasculine }
        gender.isFeminine -> VOICES.filter { it.gender.isFeminine }
        else -> VOICES
    }

    /** Голос соответствует роду имени? Если род не определён — подходит любой. */
    fun matches(id: String, gender: Gender): Boolean {
        if (gender == Gender.NEUTRAL) return true
        return voice(id).gender == gender
    }

    /**
     * Чем реально озвучивать: выбранный голос главнее рода (человек мог скачать его раньше),
     * иначе — скачанный голос нужного рода, иначе любой скачанный (лучше голос другого рода, чем молчание).
     */
    fun effectiveFor(id: String, gender: Gender): String {
        if (isReady(id)) return id
        if (gender != Gender.NEUTRAL) forGender(gender).firstOrNull { isReady(it.id) }?.let { return it.id }
        return VOICES.firstOrNull { isReady(it.id) }?.id ?: id
    }

    /** Голос, который логично поставить при смене рода имени: скачанный подходящий, иначе первый подходящий. */
    fun defaultFor(gender: Gender): Voice {
        val list = forGender(gender)
        return list.firstOrNull { isReady(it.id) } ?: list.firstOrNull() ?: voice(DEFAULT)
    }

    suspend fun download(id: String): Boolean = mutex.withLock {
        val v = voice(id)
        val state = states.getValue(v.id)
        if (isReady(v.id)) { state.value = State.Ready; return@withLock true }
        withContext(Dispatchers.IO) {
            val target = File(root, v.id)
            val zip = File(root, "${v.id}.zip.part")
            try {
                root.mkdirs()
                // Нужно место и на архив, и на распакованный голос (примерно столько же).
                val need = (v.bytes - zip.length()).coerceAtLeast(0) + v.bytes
                if (root.usableSpace in 1..need) error("не хватает места на телефоне: нужно ${need / 1_000_000} МБ")
                state.value = State.Downloading(if (zip.isFile) (zip.length().toFloat() / v.bytes).coerceIn(0f, 1f) else 0f)
                fetch(v, zip) { state.value = State.Downloading(it) }
                state.value = State.Installing
                if (sha256(zip) != v.sha256) throw Corrupt("файл повреждён — скачаю заново")
                target.deleteRecursively(); target.mkdirs()
                File(target, INCOMPLETE).createNewFile()
                unzip(zip, target)
                File(target, INCOMPLETE).delete()
                zip.delete()
                if (!isReady(v.id)) error("голос не распаковался")
                state.value = State.Ready
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Голос ${v.id} не скачан", e)
                // Скачанное не выбрасываем: при повторной попытке докачаем с того же места.
                if (e is Corrupt) zip.delete()
                target.deleteRecursively()
                state.value = State.Failed(
                    when (e) {
                        is java.net.UnknownHostException -> "нет интернета"
                        is java.net.SocketTimeoutException -> "сервер не отвечает"
                        else -> e.message ?: "ошибка загрузки"
                    },
                )
                false
            }
        }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        states[id]?.value = State.Missing
    }

    /** Обрыв при загрузке: качать заново, а не докачивать битое. */
    private class Corrupt(message: String) : Exception(message)

    /**
     * Качает архив голоса, продолжая с места обрыва: сервер отдаёт остаток по Range,
     * поэтому прерванная загрузка не начинается с нуля.
     */
    private fun fetch(v: Voice, out: File, progress: (Float) -> Unit) {
        var done = if (out.isFile) out.length() else 0L
        if (done > v.bytes) { out.delete(); done = 0 }
        if (done == v.bytes && done > 0) return
        var conn = open(v.url, done)
        repeat(5) {
            if (conn.responseCode in 300..399) {
                val next = conn.getHeaderField("Location") ?: error("редирект без адреса")
                conn.disconnect()
                conn = open(URL(URL(v.url), next).toString(), done)
            }
        }
        if (conn.responseCode == 416) { out.delete(); done = 0; conn.disconnect(); conn = open(v.url) }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        // Сервер проигнорировал Range — начинаем архив заново.
        val append = done > 0 && conn.responseCode == 206
        if (!append) done = 0
        val total = (conn.contentLengthLong.takeIf { it > 0 } ?: (v.bytes - done)) + done
        var read = done
        var last = if (total > 0) (read.toFloat() / total) else 0f
        conn.inputStream.use { input ->
            FileOutputStream(out, append).use { output ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                    if (p - last >= 0.01f) { last = p; progress(p) }
                }
            }
        }
        conn.disconnect()
    }

    private fun open(url: String, from: Long = 0): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "NovaAssistant")
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
        connect()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, target: File) {
        val base = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered(BUFFER)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val out = File(target, e.name)
                // Защита от zip slip: только внутри каталога голоса.
                if (!out.canonicalPath.startsWith(base)) error("некорректный архив")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { z.copyTo(it, BUFFER) }
                }
                e = z.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "NovaVoice"
        private const val REPO = "Lucky2356/loli_ai"
        private const val RELEASE = "voices-v1"
        private const val INCOMPLETE = ".incomplete"
        private const val BUFFER = 64 * 1024
        /**
         * Голос по умолчанию — женский: при первом запуске ассистента зовут «Нова», и он говорит о себе
         * в женском роде. Для мужского имени [effectiveFor] подберёт мужской голос.
         */
        const val DEFAULT = "vera"

        val VOICES = listOf(
            Voice("denis", "Денис", "Мужской", "8bcfc5cea11b0d943d03f6b4d4da0eacf8b5ec2af5c35ff021a03c65b16c2138", 67_424_164),
            Voice("dmitri", "Дмитрий", "Мужской", "bc5dedfdd158fed88391db3645fe13a4e93eebfb6bb2ab238b13ce9bd52bc52d", 67_424_225),
            Voice("irina", "Ирина", "Женский", "7f8b6410559edad2dcfab7fa4813f0c4a09b12748edbe584397b6b5fc62a9782", 67_404_557),
            Voice("ruslan", "Руслан", "Мужской", "ac37cb0ce13b7ad0d4f11075262d51c312d9b0956f0f9014adad078deff84120", 67_425_668),
            // Женские голоса сообщества Piper (rraaww/ru_piper, Apache-2.0).
            Voice("vera", "Вера", "Женский, мягкий", "0377eed609a475c7e7bdc3bb5e2385afface9f11faed13f66b808424eb271d4c", 67_434_356),
            Voice("sonya", "Соня", "Женский, звонкий", "c2af3dd88acdc4a94132c7b7059126e6f35cd84a00efdb91334964764b90c8f1", 67_432_916),
            Voice("asya", "Ася", "Женский, высокий", "39506a1c70135a87345bcc574ee64b270f7f4bd58fd1aeb60dfc9c04e634fca3", 67_429_932),
        )
    }
}
