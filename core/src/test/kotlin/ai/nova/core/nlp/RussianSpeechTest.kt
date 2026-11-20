package ai.nova.core.nlp

import ai.nova.core.voice.RuNumbers
import ai.nova.core.voice.SpeechText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RussianSpeechTest {

    @Test fun cardinalAgreesWithGender() {
        assertEquals("ноль", RuNumbers.cardinal(0))
        assertEquals("один рубль".substringBefore(' '), RuNumbers.cardinal(1))
        assertEquals("одна", RuNumbers.cardinal(1, RuNumbers.Gender.FEMININE))
        assertEquals("пяти", RuNumbers.cardinalGenitive(5))
        assertEquals("двадцати пяти", RuNumbers.cardinalGenitive(25))
        assertEquals("одной", RuNumbers.cardinalGenitive(1, RuNumbers.Gender.FEMININE))
        assertEquals("сорока", RuNumbers.cardinalGenitive(40))
        assertEquals("пятисот", RuNumbers.cardinalGenitive(500))
        assertEquals("двух тысяч", RuNumbers.cardinalGenitive(2000))
        assertEquals("два", RuNumbers.cardinal(2))
        assertEquals("две", RuNumbers.cardinal(2, RuNumbers.Gender.FEMININE))
        assertEquals("двадцать один", RuNumbers.cardinal(21))
        assertEquals("двадцать одна", RuNumbers.cardinal(21, RuNumbers.Gender.FEMININE))
        assertEquals("сто", RuNumbers.cardinal(100))
        assertEquals("двести пятьдесят", RuNumbers.cardinal(250))
        assertEquals("одна тысяча", RuNumbers.cardinal(1000, RuNumbers.Gender.FEMININE))
        assertEquals("две тысячи двадцать шесть", RuNumbers.cardinal(2026))
        assertEquals("пять тысяч", RuNumbers.cardinal(5000))
        assertEquals("минус семь", RuNumbers.cardinal(-7))
    }

    @Test fun ordinalForYears() {
        assertEquals("две тысячи двадцать шестом", RuNumbers.ordinal(2026))
        assertEquals("две тысячи двадцать шестого", RuNumbers.ordinal(2026, RuNumbers.Case.GENITIVE))
        assertEquals("тысяча девятьсот девяносто первом", RuNumbers.ordinal(1991))
        assertEquals("двадцатом", RuNumbers.ordinal(20))
        assertEquals("двадцатого", RuNumbers.ordinal(20, RuNumbers.Case.GENITIVE))
        assertEquals("первом", RuNumbers.ordinal(1))
        assertEquals("третьем", RuNumbers.ordinal(3))
        assertEquals("сороковом", RuNumbers.ordinal(40))
        assertEquals("сотом", RuNumbers.ordinal(100))
        assertEquals("двухтысячном", RuNumbers.ordinal(2000))
    }

    @Test fun fractions() {
        assertEquals("одна целая и пять десятых", RuNumbers.fraction(1, "5"))
        assertEquals("ноль целых и пять десятых", RuNumbers.fraction(0, "5"))
        assertEquals("две целых и три десятых", RuNumbers.fraction(2, "3"))
        assertEquals("двадцать пять целых и пять сотых", RuNumbers.fraction(25, "05"))
    }

    @Test fun numbersBecomeWords() {
        fun say(t: String) = SpeechText.forSpeech(t)
        assertEquals("Я потратил пятьсот рублей", say("Я потратил 500 ₽"))
        assertEquals("Встреча в десять тридцать", say("Встреча в 10:30"))
        assertEquals("Купи два килограмма картофеля", say("Купи 2 кг картофеля"))
        assertEquals("Осталась одна минута", say("Осталась 1 мин."))
        assertEquals("Ветер пять метров в секунду", say("Ветер 5 м/с"))
        assertEquals("Это было в две тысячи двадцать шестом году", say("Это было в 2026 г."))
        assertEquals("Двадцатого ноября две тысячи двадцать шестого года встреча", say("20 ноября 2026 года встреча"))
        assertEquals("Первый. Нарежьте лук. Второй. Обжарьте", say("1. Нарежьте лук\n2. Обжарьте"))
        assertEquals("Скидка двадцать пять процентов", say("Скидка 25%"))
    }

    @Test fun phoneIsSpelledByDigits() {
        assertEquals("Мама: плюс семь девять один шесть один два три четыре пять шесть семь.",
            SpeechText.forSpeech("Мама: +7 916 123-45-67."))
    }

    @Test fun versionAndScoreAreNotNumbers() {
        assertTrue(SpeechText.forSpeech("Версия 2.20.2 готова").contains("2.20.2"))
        assertTrue(SpeechText.forSpeech("Счёт 3:0 в пользу хозяев").contains("3:0"))
    }

    /** Знак ударения в текст для синтеза не попадает: espeak-ng его не понимает, а человек увидит мусор. */
    @Test fun stressNeverLeaksIntoSpokenText() {
        val spoken = SpeechText.forSpeech("Она звонит про каталог и договоры")
        assertFalse(spoken.contains('\u0301'), spoken)
        assertFalse(SpeechText.forSpeech("В 2026 году всё будет хорошо").contains('\u0301'))
    }

    /** Голос не спотыкается о символы: в тексте для синтеза их быть не должно. */
    @Test fun spokenTextHasNoMarkup() {
        val text = """
            ## Погода
            • Утром +12°, днём 18°, ветер 5 м/с
            • **Дождь** к вечеру, 70%
            Ссылка: https://example.com/x и файл `note.md`
        """.trimIndent()
        val spoken = SpeechText.forSpeech(text)
        assertFalse(spoken.contains('*'), spoken)
        assertFalse(spoken.contains('#'), spoken)
        assertFalse(spoken.contains('`'), spoken)
        assertFalse(spoken.contains("http"), spoken)
        assertTrue(spoken.contains("плюс двенадцать градусов"), spoken)
        assertTrue(spoken.contains("семьдесят процентов"), spoken)
    }
}
