package ai.nova.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuGenderTest {

    @Test
    fun `женское имя даёт женский род`() {
        assertEquals(Gender.FEMININE, Gender.of("Нова"))
        assertEquals(Gender.FEMININE, Gender.of("Кира"))
        assertEquals(Gender.FEMININE, Gender.of("Алиса"))
        assertEquals(Gender.FEMININE, Gender.of("Любовь"))
        assertEquals(Gender.FEMININE, Gender.of("анна-мария"))
    }

    @Test
    fun `мужское имя даёт мужской род`() {
        assertEquals(Gender.MASCULINE, Gender.of("Джарвис"))
        assertEquals(Gender.MASCULINE, Gender.of("Максим"))
        assertEquals(Gender.MASCULINE, Gender.of("Игорь"))
        assertEquals(Gender.MASCULINE, Gender.of("Андрей"))
        // Мужские имена на «а»/«я» не должны становиться женскими.
        assertEquals(Gender.MASCULINE, Gender.of("Никита"))
        assertEquals(Gender.MASCULINE, Gender.of("Илья"))
        assertEquals(Gender.MASCULINE, Gender.of("Дима"))
    }

    @Test
    fun `неоднозначные имена род не угадывают`() {
        assertEquals(Gender.NEUTRAL, Gender.of("Саша"))
        assertEquals(Gender.NEUTRAL, Gender.of("Женя"))
        assertEquals(Gender.NEUTRAL, Gender.of("Валя"))
        assertEquals(Gender.NEUTRAL, Gender.of(""))
        assertEquals(Gender.NEUTRAL, Gender.of(null))
    }

    @Test
    fun `род ассистента по умолчанию женский`() {
        assertEquals(Gender.FEMININE, Gender.ofAssistant(null))
        assertEquals(Gender.FEMININE, Gender.ofAssistant(""))
        assertEquals(Gender.FEMININE, Gender.ofAssistant("Саша"))
        assertEquals(Gender.MASCULINE, Gender.ofAssistant("Джарвис"))
    }

    @Test
    fun `прошедшее время переводится в мужской род`() {
        assertEquals("Записал заметку", RuGender.masculine("Записала заметку"))
        assertEquals("Нашёл адрес", RuGender.masculine("Нашла адрес"))
        assertEquals("Понял", RuGender.masculine("Поняла"))
        assertEquals("Ушёл в магазин", RuGender.masculine("Ушла в магазин"))
        assertEquals("Перевёл текст", RuGender.masculine("Перевела текст"))
    }

    @Test
    fun `краткие прилагательные и возвратные глаголы меняются`() {
        assertEquals("Готов помочь", RuGender.masculine("Готова помочь"))
        assertEquals("Должен предупредить", RuGender.masculine("Должна предупредить"))
        assertEquals("Проснулся в семь", RuGender.masculine("Проснулась в семь"))
        assertEquals("Ошибся", RuGender.masculine("Ошиблась"))
    }

    @Test
    fun `посторонние слова не меняются`() {
        assertEquals("Какая погода в Москве?", RuGender.masculine("Какая погода в Москве?"))
        assertEquals("Задача: помыть посуду", RuGender.masculine("Задача: помыть посуду"))
        // Существительные женского рода не трогаем.
        assertEquals("Сказал про погоду и музыку", RuGender.masculine("Сказала про погоду и музыку"))
    }

    @Test
    fun `чужие слова в кавычках не меняются`() {
        assertEquals("Записал заметку «Была в парке»", RuGender.masculine("Записала заметку «Была в парке»"))
        assertEquals("Нашёл «Записала» в тексте", RuGender.masculine("Нашла «Записала» в тексте"))
        assertEquals("Сказал \"Была не была\"", RuGender.masculine("Сказала \"Была не была\""))
    }

    @Test
    fun `женский и неопределённый род не меняют текст`() {
        val text = "Записала заметку"
        assertEquals(text, RuGender.agree(text, Gender.FEMININE))
        assertEquals(text, RuGender.agree(text, Gender.NEUTRAL))
        assertEquals("Записал заметку", RuGender.agree(text, Gender.MASCULINE))
        assertTrue(RuGender.agree("", Gender.MASCULINE).isEmpty())
    }
}
