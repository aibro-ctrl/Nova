package ai.nova.core.agent

import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.util.Logger
import ai.nova.core.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Инструмент агента: типизированный вход (текст запроса) и результат [ToolResult].
 * Инструменты не бросают исключения наружу — за это отвечает [ToolExecutor].
 */
interface AgentTool {
    val id: String
    val title: String

    /** Меняет данные: повторный вызов с тем же входом не должен создавать дубль. */
    val mutating: Boolean get() = false

    suspend fun run(input: String): ToolResult
}

/** Набор доступных инструментов. */
class ToolRegistry(initial: List<AgentTool> = emptyList()) {
    private val tools = LinkedHashMap<String, AgentTool>()

    init { initial.forEach { register(it) } }

    fun register(tool: AgentTool) { tools[tool.id] = tool }

    fun get(id: String): AgentTool? = tools[id]

    val all: List<AgentTool> get() = tools.values.toList()

    val ids: List<String> get() = tools.keys.toList()
}

/**
 * Исполнение инструментов: таймаут, отмена, повторы для читающих вызовов,
 * защита от дублей для изменяющих и запись в лог.
 */
class ToolExecutor(
    private val time: TimeSource,
    private val defaultTimeoutMs: Long = 20_000,
    private val defaultAttempts: Int = 2,
) {
    /** Успешные изменяющие вызовы за последние [DEDUP_TTL] — повтор не выполняется второй раз. */
    private val recent = HashMap<String, Pair<Long, ToolResult>>()

    suspend fun run(
        tool: AgentTool,
        input: String,
        timeoutMs: Long = defaultTimeoutMs,
        attempts: Int = if (tool.mutating) 1 else defaultAttempts,
    ): ToolResult {
        prune()
        val key = tool.id + "|" + RuTokenizer.normalize(input).trim()
        if (tool.mutating) recent[key]?.let { (at, result) ->
            if (result.ok && time.now().toEpochMilli() - at <= DEDUP_TTL.toMillis()) {
                Logger.i(TAG, "Повтор «${tool.id}» не выполняю: уже сделано")
                return result
            }
        }
        var last: ToolResult = ToolResult.fail("не выполнено")
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            val started = time.now().toEpochMilli()
            val result = try {
                withTimeoutOrNull(timeoutMs) { tool.run(input) } ?: ToolResult.fail("истекло время ожидания (${timeoutMs / 1000} с)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "Инструмент «${tool.id}» упал", e)
                ToolResult.fail(e.message ?: e::class.simpleName.orEmpty())
            }
            val spent = time.now().toEpochMilli() - started
            Logger.d(TAG, "«${tool.id}» за $spent мс: ${if (result.ok) "ок" else "ошибка ${result.error}"}")
            last = result
            if (result.ok) break
            if (attempt < attempts) Logger.w(TAG, "Повтор «${tool.id}» (попытка ${attempt + 1})")
        }
        if (tool.mutating && last.ok) recent[key] = time.now().toEpochMilli() to last
        return last
    }

    private fun prune() {
        val now = time.now().toEpochMilli()
        recent.entries.removeAll { now - it.value.first > DEDUP_TTL.toMillis() }
    }

    fun forget() = recent.clear()

    companion object {
        private const val TAG = "AgentTool"
        private val DEDUP_TTL: Duration = Duration.ofMinutes(1)
    }
}
