package ai.nova.core.assistant

import ai.nova.core.TestEnv
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** 2.16.0: рецепт по вопросу, ингредиенты в покупки, пошаговая готовка с продолжением. */
class Cooking26Test {
    private suspend fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun tellsIngredientsAndOffersShopping() = runTest {
        val e = env()
        val r = e.engine.handle("как приготовить плов")
        assertTrue(r.text.contains("Вам понадобится"), r.text)
        assertTrue(r.text.contains("рис"), r.text)
        assertTrue(r.text.contains("список покупок"), r.text)
        assertTrue(r.awaitingAnswer)
    }

    @Test fun yesAddsEverythingToShoppingAndStartsSteps() = runTest {
        val e = env()
        e.engine.handle("как приготовить плов")
        val yes = e.engine.handle("да")
        val items = e.store.shopping.all()
        assertTrue(items.any { it.text.startsWith("рис девзира", ignoreCase = true) }, items.toString())
        assertTrue(items.any { it.text.contains("500 г") }, items.toString())
        assertTrue(yes.text.contains("Добавила в покупки"), yes.text)
        assertTrue(yes.text.contains("Шаг 1."), yes.text)
        assertTrue(yes.expectFollowUp)
        // Продолжение по контексту: «продолжай» после шага ведёт дальше.
        val g = e.engine.handle("готово").text
        println("GOTOVO → " + g.take(120))
        assertTrue(g.startsWith("Шаг 2"))
        assertTrue(e.engine.handle("продолжай").text.startsWith("Шаг 3"))
        assertTrue(e.engine.handle("назад").text.startsWith("Шаг 2"))
    }

    @Test fun shoppingOnlyThenStepsLater() = runTest {
        val e = env()
        e.engine.handle("что нужно для теста на хлеб")
        val shop = e.engine.handle("добавь в покупки")
        assertTrue(e.store.shopping.all().any { it.text.startsWith("мука пшеничная", ignoreCase = true) }, e.store.shopping.all().toString())
        assertTrue(shop.text.contains("Добавила в покупки"), shop.text)
        assertTrue(shop.text.contains("помоги приготовить"), shop.text)
        val steps = e.engine.handle("помоги приготовить тесто на хлеб")
        assertTrue(steps.text.contains("Растворите дрожжи"), steps.text)
        assertTrue(steps.text.contains("дальше"), steps.text)
    }

    @Test fun saysWhenRecipeIsUnknown() = runTest {
        val e = env()
        val r = e.engine.handle("как приготовить харчо")
        assertTrue(r.text.contains("нет ни в заметках"), r.text)
        assertTrue(r.text.contains("найди рецепт харчо"), r.text)
    }

    @Test fun doesNotHijackOtherQuestions() = runTest {
        val e = env()
        val r = e.engine.handle("что нужно для счастья")
        assertTrue(!r.text.contains("Вам понадобится"), r.text)
    }

    @Test fun recipeFromNotesStillWins() = runTest {
        val e = env()
        e.store.notes.create(ai.nova.core.model.NoteKind.NOTE, "Рецепт борща", "Ингредиенты: свёкла, капуста\n1. Сварить бульон.\n2. Добавить овощи.")
        val r = e.engine.handle("как приготовить борщ")
        assertTrue(r.text.contains("свёкла"), r.text)
        assertTrue(r.text.contains("Шагов: 2"), r.text)
    }
}
