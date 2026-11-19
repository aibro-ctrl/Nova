package ai.nova.core.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Кухня: «как приготовить плов», ингредиенты, книга рецептов, разбор статьи. */
class CuisineTest {
    @Test fun understandsCookingQuestions() {
        assertEquals(Cuisine.Ask("плов", true), Cuisine.ask("как приготовить плов"))
        assertEquals(Cuisine.Ask("тесто на хлеб", true), Cuisine.ask("нова, как приготовить тесто на хлеб?"))
        assertEquals(Cuisine.Ask("борщ", true), Cuisine.ask("как сварить борщ"))
        val need = assertNotNull(Cuisine.ask("что нужно для теста на хлеб"))
        assertTrue(!need.strong)
        assertEquals("тесто на хлеб", assertNotNull(Cuisine.book(need.dish)).title)
        assertEquals(Cuisine.Ask("плов", false), Cuisine.ask("из чего готовят плов"))
        assertEquals("сырники", assertNotNull(Cuisine.book(assertNotNull(Cuisine.ask("рецепт сырников")).dish)).title)
        // Не перехватываем чужие вопросы.
        assertNull(Cuisine.ask("что приготовить из курицы"))
        assertNull(Cuisine.ask("посоветуй что приготовить на ужин"))
        assertNull(Cuisine.ask("сколько времени варить гречку"))
        assertNull(Cuisine.ask("найди рецепт борща и запиши в заметки"))
    }

    @Test fun agreesToOffer() {
        listOf("да", "да, давай", "давай", "конечно", "хорошо").forEach { assertTrue(Cuisine.agrees(it), it) }
        assertTrue(Cuisine.wantsShopping("добавь в покупки"))
        assertTrue(Cuisine.wantsShopping("добавь это в список покупок"))
        assertTrue(Cuisine.wantsSteps("помоги приготовить"))
        assertTrue(Cuisine.wantsSteps("пошагово"))
        assertTrue(!Cuisine.agrees("нет"))
    }

    @Test fun bookKnowsPopularDishes() {
        val plov = assertNotNull(Cuisine.book("плов"))
        assertEquals("плов", plov.title)
        assertTrue(plov.ingredientTexts.any { it.startsWith("рис") }, plov.ingredientTexts.toString())
        assertTrue(plov.ingredientTexts.any { it.contains("500 г") })
        assertEquals(500.0, plov.ingredients.first { it.name.startsWith("рис") }.quantity)
        assertTrue(plov.steps.size >= 5)
        // «Из чего делают плов» и «плов узбекский» — тоже плов.
        assertNotNull(Cuisine.book("плов узбекский"))
        val bread = assertNotNull(Cuisine.book("тесто на хлеб"))
        assertTrue(bread.ingredientTexts.any { it.startsWith("мука") })
        assertEquals("тесто для пиццы", Cuisine.book("тесто для пиццы")?.title)
        assertNull(Cuisine.book("счастье"))
    }

    @Test fun parsesRecipeArticle() {
        val text = """
            Ингредиенты:
            500 г мяса
            2 стакана риса
            соль по вкусу

            Приготовление:
            1. Обжарить мясо до корочки.
            2. Добавить рис и воду, тушить 20 минут.
        """.trimIndent()
        val r = assertNotNull(Cuisine.fromText("Плов", text))
        assertEquals(listOf("500 г мяса", "2 стакана риса", "соль — по вкусу"), r.ingredientTexts)
        assertEquals(2, r.steps.size)
        assertTrue(r.steps.first().contains("Обжарить мясо"))

        // Связный текст без нумерации: шаги — предложения.
        val prose = """
            Технология приготовления:
            Мясо нарезают кусками и обжаривают в казане до золотистой корочки на сильном огне.
            Затем добавляют морковь и лук и жарят ещё десять минут, помешивая.
            Рис промывают и выкладывают ровным слоем поверх зирвака, доливают кипяток.
        """.trimIndent()
        val p = assertNotNull(Cuisine.fromText("Плов", prose))
        assertTrue(p.steps.size >= 2, p.steps.toString())
        // Без шагов рецепта нет.
        assertNull(Cuisine.fromText("Плов", "Плов — блюдо восточной кухни."))
    }
}
