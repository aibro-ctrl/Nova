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
}
