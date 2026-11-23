package ai.nova.app

import ai.nova.app.voice.NovaVoiceModels
import ai.nova.core.nlp.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Каталог встроенных голосов: род берётся из подписи, и под каждый род есть что предложить. */
class VoiceGenderTest {

    @Test
    fun `род голоса совпадает с подписью`() {
        for (v in NovaVoiceModels.VOICES) {
            val expected = if (v.subtitle.startsWith("Мужской")) Gender.MASCULINE else Gender.FEMININE
            assertEquals(expected, v.gender, "голос ${v.id}: подпись «${v.subtitle}»")
        }
    }

    @Test
    fun `под каждый род есть хотя бы два голоса`() {
        assertTrue(NovaVoiceModels.VOICES.count { it.gender.isFeminine } >= 2, "мало женских голосов")
        assertTrue(NovaVoiceModels.VOICES.count { it.gender.isMasculine } >= 2, "мало мужских голосов")
    }

    @Test
    fun `голос по умолчанию женский`() {
        val def = NovaVoiceModels.VOICES.first { it.id == NovaVoiceModels.DEFAULT }
        assertEquals(Gender.FEMININE, def.gender, "по умолчанию ассистента зовут Нова — и голос женский")
    }
}
