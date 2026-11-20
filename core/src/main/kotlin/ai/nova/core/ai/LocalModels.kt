package ai.nova.core.ai

import ai.nova.core.data.NovaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Файл модели (.gguf) в репозитории Hugging Face. */
data class ModelFile(val repo: String, val path: String, val size: Long, val sha256: String?) {
    /** Прямая ссылка на файл: Hugging Face отдаёт его по этому адресу без ключа. */
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$path"

    /** Имя без пути — по нему файл лежит на телефоне. */
    val fileName: String get() = path.substringAfterLast('/')

    /** Квантование («Q4_K_M»): чем ниже, тем меньше файл и скромнее требования к памяти. */
    val quant: String get() = QUANT.find(fileName)?.groupValues?.get(1)?.uppercase().orEmpty()

    val title: String get() = fileName.removeSuffix(".gguf").replace('-', ' ')
}

private val QUANT = Regex("""(?i)((?:i?q\d(?:_[a-z0-9]+)*|f16|bf16))""")

/**
 * Разбор ответов Hugging Face: коллекция (список репозиториев) и файлы репозитория.
 * Только чистая логика — сеть и загрузка остаются в приложении, поэтому её легко проверять тестами.
 */
object LocalModels {
    /** Порядок предпочтения квантов: сначала те, что влезают в память телефона и почти не теряют качество. */
    private val PREFERRED = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q4_0", "Q5_0", "Q6_K", "Q8_0")

    /** Репозитории моделей из ответа `/api/collections/{owner}/{slug}`. */
    fun parseCollection(json: String): List<String> {
        val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o.primitive("type")
            if (type != null && type != "model") return@mapNotNull null
            // API отдаёт репозиторий то как itemId, то как id — берём любой.
            (o.primitive("itemId") ?: o.primitive("id"))?.takeIf { it.contains('/') }
        }.distinct()
    }

    /** Файлы модели из ответа `/api/models/{repo}?blobs=true`: только одиночные .gguf без проектора. */
    fun parseRepoFiles(repo: String, json: String): List<ModelFile> {
        val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val siblings = root["siblings"] as? JsonArray ?: return emptyList()
        return siblings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = o.primitive("rfilename") ?: return@mapNotNull null
            val lower = path.lowercase()
            if (!lower.endsWith(".gguf")) return@mapNotNull null
            // Проектор нужен только мультимодальным моделям — для разговора он не используется.
            if (lower.contains("mmproj")) return@mapNotNull null
            // Разрезанные на части файлы не поддерживаем: их нужно склеивать.
            if (Regex("""-\d{5}-of-\d{5}""").containsMatchIn(lower)) return@mapNotNull null
            val lfs = o["lfs"] as? JsonObject
            val size = lfs?.get("size")?.jsonPrimitive?.longOrNull ?: o["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val sha = lfs?.get("oid")?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 64 }
            ModelFile(repo, path, size, sha)
        }.sortedBy { it.size }
    }

    /**
     * Что предложить на телефоне: файл, который влезает в разумные ~3 ГБ, с лучшим из доступных квантов.
     * Если таких нет — самый маленький.
     */
    fun pick(files: List<ModelFile>, maxBytes: Long = PHONE_LIMIT): ModelFile? {
        if (files.isEmpty()) return null
        val fits = files.filter { it.size in 1..maxBytes }
        if (fits.isNotEmpty()) {
            for (q in PREFERRED) fits.firstOrNull { it.quant == q }?.let { return it }
            return fits.first()
        }
        // Ничего не влезает — предлагаем самый маленький файл.
        return files.filter { it.size > 0 }.minByOrNull { it.size } ?: files.first()
    }

    /**
     * Готовые GGUF-репозитории для обновления модели в одно нажатие, от крупной к мелкой.
     * Qwen3-Instruct-2507 не «думает» вслух и заметно точнее Qwen3.5: те выпуски на телефоне
     * уходили в рассуждения вместо ответа.
     */
    val RECOMMENDED = listOf(
        "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        "Qwen/Qwen2.5-3B-Instruct-GGUF",
        "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
    )

    /** 4B (~2,5 ГБ): столько памяти нужно, чтобы ответ не ждать десятки секунд. */
    private const val BIG_RAM = 12_000_000_000L

    /** 3B (~2,1 ГБ): на телефоне 8 ГБ он вдвое медленнее 1.5B — начинаем с него только с 10 ГБ. */
    private const val MID_RAM = 10_000_000_000L

    /** Что пробовать на этом телефоне: сначала самое крупное, что он вытянет. */
    fun recommended(totalRamBytes: Long): List<String> = when {
        totalRamBytes >= BIG_RAM -> RECOMMENDED
        totalRamBytes >= MID_RAM -> RECOMMENDED.drop(1)
        else -> RECOMMENDED.drop(2)
    }

    /** Сколько максимум влезет в телефон: крупнее 3 ГБ на телефоне не нужно. */
    const val PHONE_LIMIT = 3_000_000_000L

    /**
     * Ответ модели без размышлений: рассуждающие Qwen пишут их в «<think>…</think>» (иногда без закрывающего тега).
     * Пользователю нужен только ответ.
     */
    fun stripThinking(text: String): String {
        var out = text.replace(Regex("""(?is)<think\b[^>]*>.*?</think\s*>"""), "")
        // Размышления без закрывающего тега: всё до последнего «</think>» — это ещё не ответ.
        val close = out.indexOf("</think")
        if (close >= 0) out = out.substring(close).substringAfter('>')
        out = out.replace(Regex("""(?is)</?think\b[^>]*>"""), "")
        // Рассуждения без тегов: заголовок «Thinking Process:» и абзац под ним.
        out = out.replace(
            Regex("""(?is)^[ \t]*(?:thinking(?:\s+process)?|reasoning|анализ|рассуждени[ея]|размышлени[ея])[ \t]*[:：].*?(?=\n[ \t]*\n|\z)"""),
            "",
        )
        return dropForeignPreamble(out).trim()
    }

    /** Строки, с которых «думающие» модели начинают рассуждения в служебной форме. */
    private val THINKING_LINE = Regex(
        """(?i)^(?:okay|ok|hmm|well|let me|let's|i (?:need|should|will|must)|we (?:need|are|should)|the user|user asks|firstly|first,|so,|thinking|draft|note:|the question|question:|answer:)""",
    )

    /**
     * Перед русским ответом такие модели оставляют английские рассуждения («Okay, the user asks about borscht…»).
     * Ответ произносится вслух, поэтому служебную преамбулу без кириллицы отрезаем.
     */
    private fun dropForeignPreamble(text: String): String {
        val cyr = text.indexOfFirst { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        if (cyr <= 0) return text
        val head = text.substring(0, cyr).trim()
        if (head.length < 25 || !THINKING_LINE.containsMatchIn(head)) return text
        return text.substring(cyr)
    }

    /** «1,2 ГБ» — для экрана настроек. */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "размер неизвестен"
        bytes >= 1_000_000_000L -> "%.1f ГБ".format(java.util.Locale.US, bytes / 1_000_000_000.0).replace('.', ',')
        else -> "${(bytes + 500_000L) / 1_000_000L} МБ"
    }

    /** Короткое имя модели для списка: репозиторий без владельца и квант. */
    fun label(file: ModelFile): String {
        val repo = file.repo.substringAfterLast('/')
        return (repo + " · " + file.quant).trim(' ', '·')
    }

    /** Человеческое имя модели: «unsloth/Qwen3.5-2B-GGUF» → «Qwen 3.5 2B». */
    fun friendlyName(repo: String): String {
        val name = repo.substringAfterLast('/')
            .removeSuffix(".gguf")
            .replace(Regex("""(?i)-?gguf$"""), "")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
        return name.replace(Regex("""(?i)qwen\s*(\d+(?:\.\d+)?)"""), "Qwen $1").trim()
    }

    /** Что значит квант — простыми словами, без «Q4_K_M» в качестве единственного объяснения. */
    fun quantNote(quant: String): String = when (quant.uppercase()) {
        "Q4_K_M", "Q4_K_S", "Q4_0", "Q4_1" -> "баланс размера и качества — то, что нужно телефону"
        "Q5_K_M", "Q5_K_S", "Q5_0", "Q5_1" -> "чуть точнее, но и памяти больше"
        "Q6_K" -> "почти как оригинал, но тяжёлая"
        "Q8_0" -> "максимальное качество, нужен запас памяти"
        "F16", "BF16" -> "полное качество, очень тяжёлая — для телефона избыточна"
        else -> if (quant.uppercase().startsWith("IQ")) "очень компактная, качество чуть ниже" else ""
    }
}

private fun JsonObject.primitive(key: String): String? = (this[key])?.jsonPrimitive?.contentOrNull
