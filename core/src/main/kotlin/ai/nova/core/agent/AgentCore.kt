package ai.nova.core.agent

import ai.nova.core.util.TimeSource

/**
 * Ядро агента: реестр навыков, инструменты, контекст и разбор составных запросов.
 *
 * Движок берёт отсюда «план» фразы (несколько намерений), помнит найденные сущности
 * и сохраняет состояние задачи, чтобы сценарий можно было продолжить после перезапуска.
 */
class AgentCore(
    store: AgentStore? = null,
    time: TimeSource,
    val registry: SkillRegistry = SkillRegistry(),
    val tools: ToolRegistry = ToolRegistry(),
) {
    val context = ContextManager(store, time)
    val toolExecutor = ToolExecutor(time)

    /** Разбор составной фразы; null — фраза обычная, её обрабатывает движок как раньше. */
    fun plan(text: String): AgentPlan? = IntentRecognizer.plan(text, registry)

    /** Задача на составную фразу: один идентификатор, по нему пишутся шаги. */
    suspend fun beginTask(plan: AgentPlan, skills: List<String>): AgentTask = context.begin(
        kind = "compound",
        title = plan.raw,
        payload = skills.joinToString(","),
        totalSteps = plan.intents.size,
    )

    suspend fun remember(entity: AgentEntity) = context.remember(entity)

    suspend fun lastEntity(): AgentEntity? = context.lastEntity()

    /** Выполнить инструмент с таймаутом, повторами и защитой от дублей. */
    suspend fun runTool(id: String, input: String, timeoutMs: Long? = null, attempts: Int? = null): ToolResult {
        val tool = tools.get(id) ?: return ToolResult.fail("инструмент «$id» не зарегистрирован")
        return toolExecutor.run(tool, input, timeoutMs ?: 20_000, attempts ?: if (tool.mutating) 1 else 2)
    }

    /** Уборка старых задач и сущностей — вызывается при старте приложения. */
    suspend fun vacuum() = context.vacuum()
}
