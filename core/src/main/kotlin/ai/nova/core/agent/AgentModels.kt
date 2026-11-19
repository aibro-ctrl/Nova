package ai.nova.core.agent

/** Состояние многошаговой задачи агента. */
enum class TaskState {
    NOT_STARTED,
    IN_PROGRESS,
    /** Ждём ответа человека («добавить ингредиенты в покупки?»). */
    WAITING_FOR_USER,
    PAUSED,
    COMPLETED,
    CANCELLED;

    val active: Boolean get() = this == NOT_STARTED || this == IN_PROGRESS || this == WAITING_FOR_USER || this == PAUSED

    companion object {
        fun of(value: String?): TaskState = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

/** Состояние отдельного шага задачи. */
enum class StepState {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED;

    companion object {
        fun of(value: String?): StepState = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

/** Задача агента: «приготовить борщ», «найти песню и включить» — то, что может длиться несколько реплик. */
data class AgentTask(
    val id: String,
    val kind: String,
    val title: String,
    val state: TaskState = TaskState.NOT_STARTED,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    /** Произвольные данные шага (JSON): название блюда, номер шага рецепта, найденная песня. */
    val payload: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Запись о выполненном шаге — для восстановления «на чём остановились». */
data class AgentStep(
    val id: String,
    val taskId: String,
    val index: Int,
    val skill: String,
    val input: String,
    val output: String = "",
    val state: StepState = StepState.DONE,
    val error: String? = null,
    val durationMs: Long = 0,
    val createdAt: Long = 0,
)

/** Сущность, найденная по ходу разговора: рецепт, статья, песня. Нужна для «запиши его в заметки». */
data class AgentEntity(
    val kind: String,
    val title: String,
    /** Подробности: ингредиенты и шаги рецепта, текст статьи. */
    val payload: String = "",
) {
    val label: String get() = KIND_LABELS[kind] ?: "это"

    companion object {
        const val RECIPE = "recipe"
        const val ARTICLE = "article"
        const val SONG = "song"

        private const val FIELD = '\u0001'
        private const val ITEM = '\u0002'

        private val KIND_LABELS = mapOf(RECIPE to "рецепт", ARTICLE to "статья", SONG to "песня")

        fun recipe(title: String, ingredients: List<String>, steps: List<String>): AgentEntity =
            AgentEntity(RECIPE, title, encodeRecipe(ingredients, steps))

        private fun encodeRecipe(ingredients: List<String>, steps: List<String>): String = buildString {
            append("ИНГРЕДИЕНТЫ").append(FIELD).append(ingredients.joinToString(ITEM.toString())).append('\n')
            append("ШАГИ").append(FIELD).append(steps.joinToString(ITEM.toString()))
        }

        /** Обратный разбор [recipe]: ингредиенты и шаги. */
        fun decodeRecipe(payload: String): Pair<List<String>, List<String>> {
            var ingredients = emptyList<String>()
            var steps = emptyList<String>()
            payload.lineSequence().forEach { line ->
                val head = line.substringBefore(FIELD, "")
                val parts = line.substringAfter(FIELD, "").split(ITEM).map { it.trim() }.filter { it.isNotEmpty() }
                when (head) {
                    "ИНГРЕДИЕНТЫ" -> ingredients = parts
                    "ШАГИ" -> steps = parts
                }
            }
            return ingredients to steps
        }
    }
}

/** Одно намерение в составной фразе: «найди рецепт борща» + «запиши его в заметки». */
data class AgentIntent(
    val index: Int,
    val skill: String,
    val text: String,
    /** «запиши его в заметки» — нужна сущность из предыдущего шага. */
    val needsEntity: Boolean = false,
)

/** Разобранная составная фраза. */
data class AgentPlan(val intents: List<AgentIntent>, val raw: String)

/** Результат работы инструмента. */
data class ToolResult(val ok: Boolean, val text: String, val error: String? = null) {
    companion object {
        fun ok(text: String) = ToolResult(true, text)
        fun fail(error: String) = ToolResult(false, "", error)
    }
}
