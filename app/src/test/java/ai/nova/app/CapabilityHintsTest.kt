package ai.nova.app

import ai.nova.app.ui.screens.capabilityExamples
import ai.nova.app.ui.screens.notHints
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подсказки главного экрана — самостоятельные команды: хвосты диалога и голосовые команды в них не попадают. */
class CapabilityHintsTest {
    @Test fun hintsAreNotEmptyAndStandalone() {
        assertTrue(capabilityExamples.size >= 10, "подсказок мало: ${capabilityExamples.size}")
        for (f in notHints) {
            assertFalse(capabilityExamples.any { it.equals(f, true) }, "«$f» — не самостоятельная команда, в подсказках ей не место")
        }
    }

    @Test fun knownHintsSurvive() {
        for (h in listOf("Какая погода", "таймер на 5 минут", "Курс доллара")) {
            assertTrue(capabilityExamples.any { it.equals(h, true) }, "пропала полезная подсказка «$h»")
        }
    }
}
