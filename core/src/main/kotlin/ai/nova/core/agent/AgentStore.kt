package ai.nova.core.agent

import ai.nova.core.db.NovaDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Постоянное состояние агента в SQLDelight: задачи, шаги и контекст.
 * Нужно, чтобы «продолжай готовить» и «запиши его в заметки» работали после перезапуска.
 */
class AgentStore(
    private val db: NovaDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun saveTask(task: AgentTask) = withContext(dispatcher) {
        db.agentQueries.upsertTask(
            id = task.id,
            kind = task.kind,
            title = task.title,
            state = task.state.name,
            step_index = task.stepIndex.toLong(),
            total_steps = task.totalSteps.toLong(),
            payload = task.payload,
            created_at = task.createdAt,
            updated_at = task.updatedAt,
        )
        Unit
    }

    suspend fun task(id: String): AgentTask? = withContext(dispatcher) {
        db.agentQueries.selectTask(id).executeAsOneOrNull()?.toTask()
    }

    suspend fun active(): AgentTask? = withContext(dispatcher) {
        db.agentQueries.selectActive().executeAsOneOrNull()?.toTask()
    }

    suspend fun recent(limit: Long = 20): List<AgentTask> = withContext(dispatcher) {
        db.agentQueries.selectRecent(limit).executeAsList().map { it.toTask() }
    }

    suspend fun deleteTask(id: String) = withContext(dispatcher) {
        db.agentQueries.deleteTask(id)
        db.agentQueries.deleteSteps(id)
        Unit
    }

    suspend fun pruneTasks(before: Long) = withContext(dispatcher) {
        db.agentQueries.deleteTasksOlderThan(before)
        Unit
    }

    suspend fun addStep(step: AgentStep) = withContext(dispatcher) {
        db.agentQueries.upsertStep(
            id = step.id,
            task_id = step.taskId,
            step_index = step.index.toLong(),
            skill = step.skill,
            input = step.input,
            output = step.output,
            state = step.state.name,
            error = step.error,
            duration_ms = step.durationMs,
            created_at = step.createdAt,
        )
        Unit
    }

    suspend fun steps(taskId: String): List<AgentStep> = withContext(dispatcher) {
        db.agentQueries.selectSteps(taskId).executeAsList().map { row ->
            AgentStep(
                id = row.id,
                taskId = row.task_id,
                index = row.step_index.toInt(),
                skill = row.skill,
                input = row.input,
                output = row.output,
                state = StepState.of(row.state),
                error = row.error,
                durationMs = row.duration_ms,
                createdAt = row.created_at,
            )
        }
    }

    suspend fun context(key: String): String? = withContext(dispatcher) {
        db.agentQueries.selectContext(key).executeAsOneOrNull()?.content
    }

    suspend fun contextUpdatedAt(key: String): Long? = withContext(dispatcher) {
        db.agentQueries.selectContext(key).executeAsOneOrNull()?.updated_at
    }

    suspend fun putContext(key: String, value: String, at: Long) = withContext(dispatcher) {
        db.agentQueries.upsertContext(key, value, at)
        Unit
    }

    suspend fun deleteContext(key: String) = withContext(dispatcher) {
        db.agentQueries.deleteContext(key)
        Unit
    }

    suspend fun pruneContext(before: Long) = withContext(dispatcher) {
        db.agentQueries.deleteExpiredContext(before)
        Unit
    }

    suspend fun wipe() = withContext(dispatcher) {
        db.agentQueries.deleteAllTasks()
        db.agentQueries.deleteAllSteps()
        db.agentQueries.deleteAllContext()
        Unit
    }

    private fun ai.nova.core.db.Agent_task.toTask() = AgentTask(
        id = id,
        kind = kind,
        title = title,
        state = TaskState.of(state),
        stepIndex = step_index.toInt(),
        totalSteps = total_steps.toInt(),
        payload = payload,
        createdAt = created_at,
        updatedAt = updated_at,
    )
}
