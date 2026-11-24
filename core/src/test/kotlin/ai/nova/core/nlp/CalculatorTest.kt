package ai.nova.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalculatorTest {
    private fun calc(text: String) = Calculator.evaluate(text)

    @Test
    fun `обычная арифметика`() {
        assertEquals(4.0, calc("2+2"))
        assertEquals(182.0, calc("7 умножить на 26"))
        assertEquals(3.0, calc("7 минус 4"))
        assertEquals(25.0, calc("(1200+800)/2/40"))
    }

    @Test
    fun `числительные словами`() {
        assertEquals(1000.0, calc("сколько будет 500 на 2"))
        assertEquals(9.0, calc("три умножить на три"))
    }

    @Test
    fun `проценты от числа`() {
        assertEquals(300.0, calc("сколько будет 15 процентов от 2000"))
        assertEquals(300.0, calc("сколько будет 15% от 2000"))
        // Числительное словом: «тысячи» → 1000, иначе выражение не распознавалось.
        assertEquals(200.0, calc("сколько будет 20% от тысячи"))
        // Без знака % — так тоже говорят: «сколько будет 20 от тысячи».
        assertEquals(200.0, calc("сколько будет 20 от тысячи"))
    }

    @Test
    fun `скидка и наценка`() {
        // «минус 20% от тысячи» — это тысяча без двадцати процентов, то есть 800, а не −200.
        assertEquals(800.0, calc("сколько будет минус 20% от тысячи"))
        assertEquals(1200.0, calc("сколько будет плюс 20% от тысячи"))
        assertEquals(1600.0, calc("2000 минус 20%"))
        assertEquals(2400.0, calc("2000 плюс 20%"))
        assertEquals(1600.0, calc("сколько будет 2000 минус 20 процентов"))
        assertEquals(1600.0, calc("2000 - 20%"))
    }

    @Test
    fun `не арифметика`() {
        assertNull(calc("купить хлеб"))
        assertNull(calc("500 на 2"))
        assertNull(calc("сколько будет 20"))
    }

    @Test
    fun `формат ответа`() {
        assertEquals("800", Calculator.format(800.0))
        assertEquals("12,5", Calculator.format(12.5))
        assertEquals("0,3333", Calculator.format(1.0 / 3))
    }
}
