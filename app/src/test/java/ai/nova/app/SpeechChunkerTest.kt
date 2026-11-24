package ai.nova.app

import ai.nova.app.voice.SpeechChunker
import kotlin.test.Test
import kotlin.test.assertEquals

/** Дробление ответа на фразы для потоковой озвучки: числа и время не должны рваться. */
class SpeechChunkerTest {
    @Test fun sentencesComeAsSoonAsTheyAreFinished() {
        val buffer = StringBuilder("Небо голубое из-за рассеяния. Оно")
        val out = ArrayList<String>()
        SpeechChunker.flush(buffer) { out += it }
        assertEquals(listOf("Небо голубое из-за рассеяния."), out)
        assertEquals("Оно", buffer.toString())
    }

    @Test fun decimalsAndTimesStayWhole() {
        val buffer = StringBuilder("Встреча в 18:30, цена 3.5 рубля. Потом")
        val out = ArrayList<String>()
        SpeechChunker.flush(buffer) { out += it }
        assertEquals(listOf("Встреча в 18:30, цена 3.5 рубля."), out)
    }

    @Test fun tailIsSpokenAtTheEnd() {
        val buffer = StringBuilder("Последняя фраза без точки")
        val out = ArrayList<String>()
        SpeechChunker.flush(buffer, last = true) { out += it }
        assertEquals(listOf("Последняя фраза без точки"), out)
        assertEquals("", buffer.toString())
    }

    /** Модель печатает ответ, а готовый ответ потом чистят: хвост не должен теряться. */
    @Test fun remainderAfterCleanedAnswerStartsLater() {
        val said = "Привет! Борщ — это суп."
        val answer = "Борщ — это суп. Его варят из свёклы."
        assertEquals("Его варят из свёклы.", SpeechChunker.remainder(answer, said))
    }

    @Test fun remainderIsEmptyWhenEverythingWasSpoken() {
        val said = "Сейчас пять минут. Будильник на утро."
        assertEquals("", SpeechChunker.remainder("Сейчас пять минут. Будильник на утро.", said))
    }

    @Test fun remainderSpeaksWholeAnswerWhenNothingMatched() {
        // Прозвучала только служебная преамбула модели — её убрали, ответ ещё не звучал.
        val said = "Okay, the user asks about borscht."
        assertEquals("Борщ — это суп из свёклы.", SpeechChunker.remainder("Борщ — это суп из свёклы.", said))
    }

    @Test fun remainderIsEmptyForNonStreamedAnswer() {
        assertEquals("Сейчас пять минут.", SpeechChunker.remainder("Сейчас пять минут.", ""))
        assertEquals("", SpeechChunker.remainder("", "Сейчас пять минут."))
    }

    @Test fun remainderIgnoresCaseAndPunctuation() {
        val said = "сейчас пять минут!"
        val answer = "Сейчас пять минут. И ещё пара слов."
        assertEquals("И ещё пара слов.", SpeechChunker.remainder(answer, said))
    }
}
