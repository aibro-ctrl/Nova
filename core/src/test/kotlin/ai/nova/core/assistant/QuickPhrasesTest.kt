package ai.nova.core.assistant

import ai.nova.core.TestEnv
import ai.nova.core.finance.ReportMode
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Подсказки главного экрана (список `quickPhrases` в HomeScreen) должны пониматься дословно:
 * касание подсказки отправляет её как обычную команду, и «не поняла» в ответ — брак.
 */
class QuickPhrasesTest {
    private val phrases = listOf(
        "Какая погода?",
        "Мои планы на сегодня",
        "Мои расходы",
        "Курс доллара",
        "Что у меня в календаре завтра?",
        "Напомни через час выпить воды",
        "Добавь в покупки молоко и хлеб",
        "Таймер на 5 минут",
        "Включи радио",
        "Что ты умеешь?",
    )

    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun everyHintIsUnderstood() = runTest {
        for (p in phrases) {
            val reply = env().engine.handle(p).text
            assertFalse(reply.startsWith("Не совсем поняла"), "подсказка «$p» не распознана: $reply")
            assertTrue(reply.isNotBlank(), "подсказка «$p» осталась без ответа")
        }
    }

    @Test fun myExpensesIsReportNotFallback() = runTest {
        val e = env()
        e.engine.handle("расход 850 на продукты")
        val reply = e.engine.handle("Мои расходы").text
        assertTrue(reply.contains("850"), "«Мои расходы» должны показать траты за месяц: $reply")
    }

    @Test fun bareExpensesVariants() {
        val p = LocalCommandParser()
        val now = Instant.parse("2026-09-25T09:00:00Z")
        val zone = ZoneId.of("Europe/Moscow")
        for (phrase in listOf("мои расходы", "мои траты", "расходы", "траты", "все расходы")) {
            val action = p.parse(phrase, now, zone)?.actions?.singleOrNull() as? AssistantAction.QueryExpenses
            assertEquals(ReportMode.TOTAL, action?.mode, "«$phrase» — итог за месяц")
        }
    }
}
