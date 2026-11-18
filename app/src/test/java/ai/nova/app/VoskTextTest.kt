package ai.nova.app

import ai.nova.app.voice.voskText
import ai.nova.core.nlp.WakeWordMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VoskTextTest {
    @Test fun parsesHypotheses() {
        assertEquals("нова запиши расход", voskText("""{"text" : "нова запиши расход"}""", "text"))
        assertEquals("нова", voskText("""{"partial" : "нова"}""", "partial"))
        assertEquals("", voskText("not json", "text"))
        assertEquals("", voskText(null, "text"))
    }

    @Test fun wakeWordFromVoskTranscript() {
        val text = voskText("""{"text" : "нава запиши расход пятьсот рублей"}""", "text")
        assertEquals("запиши расход пятьсот рублей", assertNotNull(WakeWordMatcher("Нова").match(text)).command)
    }
}
