package ai.nova.core.ai

/**
 * Абстракция языковой модели. Бизнес-логика ассистента зависит только от этого интерфейса —
 * провайдер (OpenAI, Gemini, Anthropic, локальная модель) выбирается в настройках.
 */
interface AIProvider {
    val type: AIProviderType
    val model: String
    suspend fun complete(request: AIRequest): AIResponse

    /** Модели, доступные с этим ключом (для выбора в настройках). */
    suspend fun listModels(): List<ModelInfo> = emptyList()
}

/** Модель провайдера: [id] передаётся в API, [name] — для показа человеку. */
data class ModelInfo(val id: String, val name: String = id)

/** Отбрасывает модели, которые не умеют вести диалог (эмбеддинги, речь, картинки, модерация). */
internal fun isChatModel(id: String): Boolean {
    val x = id.lowercase()
    return listOf("embed", "whisper", "tts", "dall-e", "moderation", "transcribe", "realtime", "image", "audio", "aqa", "imagen", "veo", "rerank")
        .none { x.contains(it) }
}

/** Векторные представления текста для семантического поиска. Поддерживается не всеми провайдерами. */
interface EmbeddingProvider {
    val embeddingModel: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

data class AIRequest(
    val system: String,
    val messages: List<ChatMessage>,
    /** Просить модель вернуть строго JSON-объект. */
    val jsonMode: Boolean = true,
    val maxTokens: Int = 4096,
)

data class AIResponse(val text: String, val model: String?, val stopReason: String?)

/** Ошибки AI-слоя с понятными сообщениями для пользователя. */
sealed class AIException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : AIException("AI не настроен: укажите провайдера, модель и API-ключ в настройках.")
    class Unauthorized(detail: String) : AIException("API-ключ не принят провайдером ($detail). Проверьте ключ в настройках.")
    class RateLimited : AIException("Превышен лимит запросов к AI. Попробуйте чуть позже.")
    class Network(cause: Throwable?) : AIException("Нет связи с AI-сервисом. Проверьте интернет.", cause)
    class Server(val code: Int, detail: String) : AIException("AI-сервис вернул ошибку $code: $detail")
    class InvalidResponse(detail: String) : AIException("AI вернул ответ в неожиданном формате: $detail")
    class Refused : AIException("Модель отказалась отвечать на этот запрос.")
    class InsecureEndpoint : AIException("Незашифрованный адрес (http://) разрешён только для серверов в локальной сети. Используйте https://.")
}

/**
 * Каталог провайдеров. [suggestedModels] — подсказка до первого запроса к провайдеру: список, который
 * реально доступен ключу, приходит из `GET /models` и показывается вместе с этим. Идентификаторы
 * сверены с публичным каталогом моделей (ноябрь 2026); старые id оставлены, чтобы не ломать настройки.
 */
enum class AIProviderType(
    val id: String,
    val title: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val defaultEmbeddingModel: String?,
    val endpointEditable: Boolean,
) {
    OPENAI(
        // По умолчанию — быстрая и недорогая модель поколения 6: голосовому ассистенту нужна скорость.
        "openai", "OpenAI", "https://api.openai.com/v1", "gpt-6-luna",
        listOf(
            "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-terra", "gpt-6.1-sol", "gpt-6-luna", "gpt-6-sol", "gpt-6-astra",
            "gpt-5.6-sol", "gpt-5.5", "gpt-5.4-mini", "gpt-5-mini", "gpt-4.1-mini",
        ), "text-embedding-3-small", false,
    ),
    GEMINI(
        "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-4-hydrogen",
        listOf(
            "gemini-4-hydrogen", "gemini-4-carbon", "gemini-4-argon", "gemini-4-neon", "gemini-3.8-flash",
            "gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.5-pro",
        ), "gemini-embedding-001", false,
    ),
    ANTHROPIC(
        "anthropic", "Anthropic Claude", "https://api.anthropic.com", "claude-sonnet-5-5",
        listOf(
            "claude-fable-5-2", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5",
            "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5",
        ), null, true,
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-6-luna",
        listOf(
            "openai/gpt-6.2-sol", "openai/gpt-6.2-astra", "openai/gpt-6.1-sol", "openai/gpt-6-luna",
            "anthropic/claude-opus-5.5", "anthropic/claude-sonnet-5.5", "anthropic/claude-haiku-5.5",
            "google/gemini-4-hydrogen", "google/gemini-3.8-flash", "x-ai/grok-4.7",
            "deepseek/deepseek-v4.1-flash", "meta-llama/llama-3.3-70b-instruct", "mistralai/mistral-small-2603",
        ), null, false,
    ),
    DEEPSEEK(
        // deepseek-chat и deepseek-reasoner — «плавающие» имена: сам сервис переводит их на новую модель.
        "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v4.1-flash", "deepseek-v4-pro", "deepseek-v4-flash"), null, false,
    ),
    MISTRAL(
        // «-latest» — тоже плавающие имена: всегда указывают на самую свежую модель этого класса.
        "mistral", "Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest",
        listOf(
            "mistral-small-latest", "mistral-medium-latest", "mistral-large-latest",
            "mistral-large-4-0", "mistral-small-2603", "ministral-8b-latest",
        ), null, false,
    ),
    GROQ(
        "groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
        listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant", "meta-llama/llama-4-maverick"), null, false,
    ),
    XAI(
        "xai", "xAI Grok", "https://api.x.ai/v1", "grok-4.7",
        listOf("grok-4.7", "grok-4.6", "grok-4.5", "grok-4.3", "grok-4"), null, false,
    ),
    CUSTOM(
        "custom", "Свой сервер (Ollama, LM Studio, vLLM)", "http://192.168.1.10:11434/v1", "",
        emptyList(), null, true,
    ),
    /** «Облако Нова»: сервер-посредник разработчика (Supabase Edge Function). Ключ — вход в аккаунт Нова. */
    NOVA_CLOUD(
        "nova", "Облако Нова", "", "auto",
        emptyList(), null, false,
    );

    val isOpenAiCompatible: Boolean get() = this != ANTHROPIC

    /** Встроенный провайдер: не показывается в списке для ручной настройки. */
    val builtIn: Boolean get() = this == NOVA_CLOUD

    companion object {
        fun fromId(id: String?): AIProviderType = entries.firstOrNull { it.id == id } ?: OPENAI
        fun fromIdOrNull(id: String?): AIProviderType? = entries.firstOrNull { it.id == id }
    }
}

/** Настройки подключения. [apiKey] никогда не попадает в toString()/логи. */
class AIConfig(
    val type: AIProviderType,
    endpoint: String?,
    model: String?,
    apiKey: String,
    val embeddingsEnabled: Boolean = true,
) {
    /** Ключ без мусора, который часто попадает при копировании: пробелы, переносы строк, кавычки, «Bearer ». */
    val apiKey: String = cleanApiKey(apiKey)

    val endpoint: String = (endpoint?.takeIf { it.isNotBlank() } ?: type.defaultEndpoint).trim().trimEnd('/')
    val model: String = (model?.takeIf { it.isNotBlank() } ?: type.defaultModel).trim()

    /** http:// допускается только для локальной сети (свой сервер Ollama/LM Studio); в интернет — только https://. */
    val isSecureEndpoint: Boolean get() {
        val uri = runCatching { java.net.URI(endpoint.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https") return !uri.host.isNullOrBlank()
        if (scheme != "http") return false
        // Хост берём разбором адреса: «http://10.evil.com» и «http://10.0.0.1@evil.com» — это интернет, а не локальная сеть.
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (host == "localhost" || host == "::1" || (host.endsWith(".local") && host.length > 6)) return true
        val octets = host.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    val isComplete: Boolean get() = model.isNotBlank() && endpoint.isNotBlank() && (apiKey.isNotBlank() || type == AIProviderType.CUSTOM)

    companion object {
        fun cleanApiKey(raw: String): String {
            var k = raw.trim().trim('"', '\'', '«', '»', '`').trim()
            k = k.removePrefix("Bearer ").removePrefix("bearer ").trim()
            k = k.removePrefix("Authorization:").trim().removePrefix("Bearer ").trim()
            return k.filterNot { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' }
        }
    }

    override fun toString(): String = "AIConfig(type=${type.id}, endpoint=$endpoint, model=$model, apiKey=${if (apiKey.isBlank()) "<empty>" else "***"})"
}
