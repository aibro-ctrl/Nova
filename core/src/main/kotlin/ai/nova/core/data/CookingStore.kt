package ai.nova.core.data

import ai.nova.core.db.NovaDatabase
import ai.nova.core.skills.KitchenRecipe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Состояние готовки: то же, что у задач агента, но для рецепта. */
enum class CookingState {
    NOT_STARTED,
    IN_PROGRESS,
    /** Шаг показан — ждём «готово»/«дальше». */
    WAITING_FOR_USER,
    PAUSED,
    COMPLETED,
    CANCELLED;

    val active: Boolean get() = this == NOT_STARTED || this == IN_PROGRESS || this == WAITING_FOR_USER || this == PAUSED

    companion object {
        fun of(value: String?): CookingState = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

/** Идущая готовка: рецепт, шаг и состояние. */
data class CookingSessionRecord(
    val id: String,
    val recipeId: String,
    val title: String,
    val stepIndex: Int,
    val state: CookingState,
    val updatedAt: Long,
)

/**
 * Сохранённые рецепты и идущая готовка.
 * Рецепт хранится вместе с количествами (JSON), поэтому пересчёт порций и восстановление
 * сессии после перезапуска не зависят от интернета и книги рецептов в коде.
 */
class CookingStore(
    private val db: NovaDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun saveRecipe(recipe: KitchenRecipe): String = withContext(dispatcher) {
        val id = idOf(recipe.title)
        db.recipeQueries.upsertRecipe(
            id = id,
            dish_key = dishKey(recipe.title),
            title = recipe.title,
            servings = recipe.servings.toLong(),
            source = recipe.source,
            payload = NovaJson.encodeToString(recipe),
            updated_at = System.currentTimeMillis(),
        )
        id
    }

    suspend fun recipe(id: String): KitchenRecipe? = withContext(dispatcher) {
        db.recipeQueries.selectRecipe(id).executeAsOneOrNull()?.let { row ->
            runCatching { NovaJson.decodeFromString<KitchenRecipe>(row.payload) }.getOrNull()?.copy(
                servings = row.servings.toInt().takeIf { it > 0 } ?: KitchenRecipe.DEFAULT_SERVINGS,
                source = row.source,
            )
        }
    }

    suspend fun recipeByTitle(title: String): KitchenRecipe? = withContext(dispatcher) {
        db.recipeQueries.selectRecipeByDish(dishKey(title)).executeAsOneOrNull()?.let { row ->
            runCatching { NovaJson.decodeFromString<KitchenRecipe>(row.payload) }.getOrNull()
        }
    }

    suspend fun saveSession(record: CookingSessionRecord) = withContext(dispatcher) {
        db.recipeQueries.upsertSession(
            id = record.id,
            recipe_id = record.recipeId,
            title = record.title,
            step_index = record.stepIndex.toLong(),
            state = record.state.name,
            updated_at = record.updatedAt,
        )
        Unit
    }

    suspend fun activeSession(): CookingSessionRecord? = withContext(dispatcher) {
        db.recipeQueries.selectActiveSession().executeAsOneOrNull()?.toRecord()
    }

    suspend fun closeSession(id: String, state: CookingState) = withContext(dispatcher) {
        db.recipeQueries.selectSession(id).executeAsOneOrNull()?.let { row ->
            db.recipeQueries.upsertSession(row.id, row.recipe_id, row.title, row.step_index, state.name, System.currentTimeMillis())
        }
        Unit
    }

    /** Уборка: старые рецепты и брошенные сессии. */
    suspend fun vacuum(now: Long = System.currentTimeMillis()) = withContext(dispatcher) {
        db.recipeQueries.deleteRecipesOlderThan(now - RECIPE_TTL_MS)
        db.recipeQueries.deleteSessionsOlderThan(now - SESSION_TTL_MS)
        Unit
    }

    suspend fun wipe() = withContext(dispatcher) {
        db.recipeQueries.deleteAllRecipes()
        db.recipeQueries.deleteAllSessions()
        Unit
    }

    private fun ai.nova.core.db.Cooking_session.toRecord() = CookingSessionRecord(
        id = id,
        recipeId = recipe_id,
        title = title,
        stepIndex = step_index.toInt(),
        state = CookingState.of(state),
        updatedAt = updated_at,
    )

    companion object {
        private const val RECIPE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val SESSION_TTL_MS = 24L * 60 * 60 * 1000

        /** Ключ блюда: «Борщ с мясом» и «борщ» — один рецепт. */
        fun dishKey(title: String): String =
            title.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\d ]"""), " ").replace(Regex("""\s+"""), " ").trim()

        fun idOf(title: String): String = "recipe:" + dishKey(title).replace(' ', '-')
    }
}
