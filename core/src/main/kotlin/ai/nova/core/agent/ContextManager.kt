package ai.nova.core.agent

import ai.nova.core.util.Ids
import ai.nova.core.util.Logger
import ai.nova.core.util.TimeSource
import java.time.Duration

/**
 * Контекст агента: активная задача, история шагов и последняя найденная сущность
 * («запиши его в заметки» → рецепт из предыдущего шага).
 *
 * Всё, что должно пережить перезапуск, лежит в [AgentStore]; память — только быстрый кэш.
 * Хранилище может быть null (настольная версия, тесты) — тогда контекст живёт в памяти.
 */
class ContextManager(
    private val store: AgentStore? = null,
    private val time: TimeSource,
) {
    private var cachedTask: AgentTask? = null
    private var cachedEntity: AgentEntity? = null
    private var entityChecked = false

    // ---------------------------------------------------------------- Задачи

    suspend fun begin(kind: String, title: String, payload: String = "", totalSteps: Int = 0): AgentTask {
        val now = time.now().toEpochMilli()
        val task = AgentTask(
            id = Ids.newId(), kind = kind, title = title, state = TaskState.IN_PROGRESS,
            stepIndex = 0, totalSteps = totalSteps, payload = payload, createdAt = now, updatedAt = now,
        )
        cachedTask = task
        save(task)
        return task
    }

    suspend fun update(task: AgentTask): AgentTask = task.copy(updatedAt = time.now().toEpochMilli()).also {
        cachedTask = it
        save(it)
    }

    suspend fun transition(task: AgentTask, state: TaskState, stepIndex: Int = task.stepIndex): AgentTask =
        update(task.copy(state = state, stepIndex = stepIndex))

    suspend fun finish(task: AgentTask, state: TaskState = TaskState.COMPLETED): AgentTask = transition(task, state)

    /** Записать шаг задачи: что просили, что вышло, сколько заняло. */
    suspend fun recordStep(task: AgentTask, index: Int, skill: String, input: String, result: ToolResult, durationMs: Long) {
        val step = AgentStep(
            id = Ids.newId(), taskId = task.id, index = index, skill = skill, input = input,
            output = if (result.ok) result.text else result.error.orEmpty(),
            state = if (result.ok) StepState.DONE else StepState.FAILED,
            error = result.error, durationMs = durationMs, createdAt = time.now().toEpochMilli(),
        )
        runCatching { store?.addStep(step) }.onFailure { Logger.w(TAG, "Шаг агента не сохранён", it) }
    }

    /** Активная задача: сначала память, потом хранилище (после перезапуска приложения). */
    suspend fun active(): AgentTask? {
        cachedTask?.takeIf { it.state.active }?.let { return it }
        val fromStore = runCatching { store?.active() }.getOrNull() ?: return null
        cachedTask = fromStore
        return fromStore
    }

    /** Что делали в прошлый раз — для «продолжим готовить». */
    suspend fun steps(taskId: String): List<AgentStep> =
        runCatching { store?.steps(taskId) }.getOrNull().orEmpty()

    suspend fun close(state: TaskState = TaskState.COMPLETED) {
        cachedTask?.let { runCatching { save(it.copy(state = state, updatedAt = time.now().toEpochMilli())) } }
        cachedTask = null
    }

    // ---------------------------------------------------------------- Сущности

    /** Запомнить найденное: рецепт, статью, песню. */
    suspend fun remember(entity: AgentEntity) {
        cachedEntity = entity
        entityChecked = true
        runCatching {
            store?.putContext(KEY_ENTITY, encode(entity), time.now().toEpochMilli())
        }.onFailure { Logger.w(TAG, "Сущность не сохранена", it) }
    }

    /** Последняя сущность, если она ещё свежая (полчаса). */
    suspend fun lastEntity(): AgentEntity? {
        if (!entityChecked) {
            entityChecked = true
            cachedEntity = runCatching {
                val raw = store?.context(KEY_ENTITY) ?: return@runCatching null
                val at = store.contextUpdatedAt(KEY_ENTITY) ?: 0
                if (time.now().toEpochMilli() - at > ENTITY_TTL.toMillis()) null else decode(raw)
            }.getOrNull()
        }
        return cachedEntity
    }

    suspend fun forgetEntity() {
        cachedEntity = null
        runCatching { store?.deleteContext(KEY_ENTITY) }
    }

    /** Уборка старых записей: задачи старше суток и просроченные сущности. */
    suspend fun vacuum() {
        val now = time.now().toEpochMilli()
        runCatching {
            store?.pruneTasks(now - TASK_TTL.toMillis())
            store?.pruneContext(now - ENTITY_TTL.toMillis())
        }.onFailure { Logger.w(TAG, "Уборка контекста не удалась", it) }
    }

    private suspend fun save(task: AgentTask) {
        runCatching { store?.saveTask(task) }.onFailure { Logger.w(TAG, "Задача агента не сохранена", it) }
    }

    private fun encode(e: AgentEntity) = e.kind + '\u0001' + e.title + '\u0001' + e.payload

    private fun decode(raw: String): AgentEntity? {
        val parts = raw.split('\u0001')
        if (parts.size < 2) return null
        return AgentEntity(parts[0], parts[1], parts.getOrElse(2) { "" })
    }

    companion object {
        private const val TAG = "AgentContext"
        private const val KEY_ENTITY = "last_entity"
        private val ENTITY_TTL: Duration = Duration.ofMinutes(30)
        private val TASK_TTL: Duration = Duration.ofDays(1)
    }
}
