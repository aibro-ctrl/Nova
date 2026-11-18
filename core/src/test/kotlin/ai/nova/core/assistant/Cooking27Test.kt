package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 2.18.0: количества в покупках, пересчёт порций и готовка, переживающая перезапуск приложения. */
class Cooking27Test {
    private suspend fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun recalculatesServingsOfShownRecipe() = runTest {
        val e = env()
        val card = e.engine.handle("как приготовить плов")
        assertTrue(card.text.contains("На 4 порции"), card.text)
        assertTrue(card.text.contains("на 6 порций"), card.text)

        val more = e.engine.handle("на 6 порций")
        assertTrue(more.text.contains("Пересчитала на 6 порций"), more.text)
        // 500 г риса на 4 порции → 750 г на 6.
        assertTrue(more.text.contains("750 г"), more.text)
        assertTrue(more.awaitingAnswer)

        e.engine.handle("добавь в покупки")
        val rice = assertNotNull(e.store.shopping.all().firstOrNull { it.text.contains("рис девзира", ignoreCase = true) })
        assertEquals(750.0, rice.quantity)
        assertEquals("г", rice.unit)
    }

    @Test fun addingSameRecipeTwiceSumsQuantities() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        e.engine.handle("добавь в покупки")
        val before = e.store.shopping.all().size

        e.engine.handle("как приготовить плов")
        e.engine.handle("добавь в покупки")
        val after = e.store.shopping.all()
        assertEquals(before, after.size, "второй заход не должен дублировать строки: $after")
        val rice = assertNotNull(after.firstOrNull { it.text.contains("рис девзира", ignoreCase = true) })
        assertEquals(1000.0, rice.quantity)
        assertTrue(rice.text.contains("1000 г"), rice.text)
        assertEquals("плов", rice.source, "видно, из какого рецепта пункт")
    }

    @Test fun shoppingKeepsAmountsForRecipesAskedAgainFromStore() = runTest {
        val e = env()
        e.engine.handle("что нужно для теста на хлеб")
        e.engine.handle("добавь в покупки")
        val flour = assertNotNull(e.store.shopping.all().firstOrNull { it.text.startsWith("мука", ignoreCase = true) })
        assertNotNull(flour.quantity, "у муки должно быть количество: ${flour.text}")
        assertEquals("г", flour.unit)
    }

    @Test fun cookingSurvivesRestart() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        e.engine.handle("да")
        assertTrue(e.engine.handle("дальше").text.startsWith("Шаг 2"), "идём по шагам")

        // Приложение перезапустили: новый движок поверх той же базы.
        val fresh = e.restart()
        val resumed = fresh.handle("продолжим готовить")
        assertTrue(resumed.text.startsWith("Продолжаем: плов"), resumed.text)
        assertTrue(resumed.text.contains("Шаг 2"), resumed.text)
        assertTrue(resumed.expectFollowUp)

        // Дальше ведёт уже восстановленная сессия.
        assertTrue(fresh.handle("дальше").text.startsWith("Шаг 3"), "продолжение после восстановления")
    }

    @Test fun restartRestoresRecipeWithoutSecondSearch() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        e.engine.handle("да")
        e.restart().handle("что нужно взять")
        assertNotNull(e.store.cooking.recipe(ai.nova.core.data.CookingStore.idOf("плов")), "рецепт сохранён в базу")
    }

    @Test fun recalculatesWhileCookingKeepsStep() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        e.engine.handle("да")
        e.engine.handle("дальше")
        val r = e.engine.handle("пересчитай на 2 порции")
        assertTrue(r.text.contains("Пересчитала на 2 порции"), r.text)
        assertTrue(r.text.contains("Шаг 2"), r.text)
        assertTrue(r.text.contains("дальше"), r.text)
        // Дальше идём по пересчитанному рецепту с того же шага.
        assertTrue(e.engine.handle("дальше").text.startsWith("Шаг 3"))
    }

    @Test fun cancelledCookingIsNotResumed() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        e.engine.handle("да")
        e.engine.handle("хватит готовить")

        val fresh = e.restart()
        val r = fresh.handle("продолжим готовить")
        assertTrue(!r.text.startsWith("Продолжаем"), r.text)
    }

    @Test fun findsSavedRecipeWithoutInternet() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        val fresh = e.restart()
        // Рецепт уже в базе: второй заход не ищет статью заново.
        val again = fresh.handle("как приготовить плов")
        assertTrue(again.text.contains("Вам понадобится"), again.text)
        assertTrue(again.text.contains("рис"), again.text)
    }
}
