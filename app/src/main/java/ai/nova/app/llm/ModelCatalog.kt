package ai.nova.app.llm

import ai.nova.core.ai.LocalModels
import ai.nova.core.ai.ModelFile
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Каталог офлайн-моделей Hugging Face: коллекция Qwen (в ней свежие выпуски) и файлы конкретного репозитория.
 * Нужен, чтобы обновлять модель без выпуска новой версии приложения.
 */
class ModelCatalog {
    /** Репозитории моделей из коллекции; при недоступности сети — пустой список (UI предложит свой репозиторий). */
    suspend fun collection(): List<String> = withContext(Dispatchers.IO) {
        runCatching { LocalModels.parseCollection(get("$API/collections/$COLLECTION")) }
            .onFailure { Logger.w(TAG, "Каталог моделей недоступен", it) }
            .getOrDefault(emptyList())
    }

    /** Файлы .gguf репозитория (только целые, без проекторов и шардов). */
    suspend fun files(repo: String): List<ModelFile> = withContext(Dispatchers.IO) {
        runCatching { LocalModels.parseRepoFiles(repo, get("$API/models/$repo?blobs=true")) }
            .onFailure { Logger.w(TAG, "Файлы модели недоступны", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Готовая модель для телефона: первая из [LocalModels.recommended], у которой есть файл, влезающий в память.
     * Именно её скачивает кнопка «Обновить» — пользователю не нужно знать репозитории.
     */
    suspend fun recommend(totalRamBytes: Long): ModelFile? = withContext(Dispatchers.IO) {
        for (repo in LocalModels.recommended(totalRamBytes)) {
            // Нужен разговорный ассистент: кодовые и мультимодальные сборки не берём.
            val name = repo.lowercase()
            if (listOf("/coder", "code-", "vision", "-vl", "base").any { name.contains(it) }) continue
            val file = LocalModels.pick(files(repo), LocalModels.PHONE_LIMIT) ?: continue
            // Совсем маленькие кванты (2–3 бита) на телефоне заметно глупее: такие пропускаем.
            if (file.size < 300_000_000L) continue
            return@withContext file
        }
        null
    }

    private fun get(url: String): String {
        var target = url
        var redirects = 0
        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NovaAssistant")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 300..399 && redirects++ < 5) {
                target = URL(URL(target), conn.getHeaderField("Location") ?: error("редирект без адреса")).toString()
                conn.disconnect()
                continue
            }
            try {
                if (conn.responseCode !in 200..299) error("сервер ответил ${conn.responseCode}")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "ModelCatalog"
        private const val API = "https://huggingface.co/api"

        /** Коллекция Qwen с актуальными моделями. */
        const val COLLECTION = "Qwen/qwen35"
    }
}
