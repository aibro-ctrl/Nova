package ai.nova.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictationTextTest {

    @Test fun emptyTextStaysEmpty() {
        assertEquals("", DictationText.format(""))
        assertEquals("", DictationText.format("   "))
    }

    @Test fun singlePhraseGetsCapitalAndDot() {
        assertEquals("Сегодня я был на встрече с клиентом.", DictationText.format("сегодня я был на встрече с клиентом"))
    }

    @Test fun existingPunctuationIsKept() {
        assertEquals("Привет! Как дела?", DictationText.format("Привет! Как дела?"))
        assertEquals("Купил хлеб, молоко и сыр.", DictationText.format("купил хлеб ,  молоко и  сыр"))
    }

    @Test fun startsSplitRunOnSpeech() {
        val text = DictationText.format("я купила хлеб и молоко потом поехала домой и приготовила ужин")
        assertEquals("Я купила хлеб и молоко. Потом поехала домой и приготовила ужин.", text)
    }

    @Test fun shortPhraseIsNotSplitOnWeakStart() {
        // «я потом пойду» — это одно предложение, а не «Я. Потом пойду».
        assertEquals("Я потом пойду.", DictationText.format("я потом пойду"))
    }

    @Test fun conjunctionBeforeStartIsNotBroken() {
        assertEquals("Я сделала уроки и потом пошла гулять.", DictationText.format("я сделала уроки и потом пошла гулять"))
    }

    @Test fun strongStartBreaksLongPhrase() {
        assertEquals(
            "Купила молоко и хлеб. Кроме того, нужно зайти в аптеку.",
            DictationText.format("купила молоко и хлеб кроме того нужно зайти в аптеку"),
        )
    }

    @Test fun introWordGetsComma() {
        assertEquals("Кстати, надо позвонить маме.", DictationText.format("кстати надо позвонить маме"))
        assertEquals("Во-первых, это быстро.", DictationText.format("во-первых это быстро"))
        assertEquals("Кроме того, нужно молоко.", DictationText.format("кроме того нужно молоко"))
    }

    @Test fun longTextBecomesParagraphs() {
        val raw = "купить молоко и хлеб на ужин сегодня вечером обязательно нужно ещё зайти в аптеку " +
            "кстати надо позвонить маме и спросить про выходные потом заехать за документами в офис " +
            "и не забыть оплатить интернет до пятницы а ещё купить корм коту"
        val text = DictationText.format(raw)
        assertTrue(text.contains("\n\n"), "должно быть несколько абзацев:\n$text")
        assertTrue(text.lines().filter { it.isNotBlank() }.size >= 2)
        assertEquals(text, DictationText.format(text), "форматирование должно быть идемпотентным")
    }

    @Test fun titleIsFirstSentence() {
        val text = DictationText.format("купить молоко и хлеб. потом заехать в аптеку")
        assertEquals("Купить молоко и хлеб", DictationText.title(text))
        assertEquals("Молоко", DictationText.title("Молоко"))
        assertEquals("Очень длинный заголовок из шести слов", DictationText.title("Очень длинный заголовок из шести слов и ещё пары"))
    }

    @Test fun dontEatMeaningfulColons() {
        assertEquals("Список покупок: молоко, хлеб.", DictationText.format("список покупок: молоко, хлеб"))
    }
}
