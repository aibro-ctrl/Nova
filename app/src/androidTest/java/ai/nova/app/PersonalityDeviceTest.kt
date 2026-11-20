package ai.nova.app

import ai.nova.core.assistant.Persona
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сравнение характеров на телефоне: один и тот же вопрос тремя профилями через настоящую офлайн-модель.
 * Проверяет то, что не видно в юнит-тестах, — что профиль реально меняет ответ, а не только промпт.
 */
@RunWith(AndroidJUnit4::class)
class PersonalityDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<NovaApp>()

    @Test
    fun sameQuestionThreeCharacters(): Unit = runBlocking {
        val c = app.container
        assumeTrue("офлайн-модель не скачана", c.offlineLlm.available)
        val question = "Зачем нужен режим «не беспокоить»?"
        val answers = LinkedHashMap<String, String>()
        Persona.entries.forEach { p ->
            c.settings.setPersona(p.wire)
            delay(300)
            val started = System.currentTimeMillis()
            val reply = c.engine.handle(question)
            val spent = System.currentTimeMillis() - started
            Log.i("Personality", "=== ${p.title} · ${spent} мс === ${reply.text.replace('\n', ' ')}")
            answers[p.wire] = reply.text.trim()
        }
        Log.i("Personality", "Похожесть заботливая/деловая: ${similarity(answers["caring"].orEmpty(), answers["business"].orEmpty())}")
        Log.i("Personality", "Похожесть заботливая/шутливая: ${similarity(answers["caring"].orEmpty(), answers["playful"].orEmpty())}")
        assertTrue("все три ответа одинаковые", answers.values.toSet().size > 1)
        // Деловая не должна быть длиннее заботливой.
        assertTrue(
            "деловая длиннее заботливой: ${answers["business"]?.length} > ${answers["caring"]?.length}",
            (answers["business"]?.length ?: 0) <= (answers["caring"]?.length ?: 0) + 10,
        )
        c.settings.setPersona("caring")
    }

    /** Доля общих слов — грубая, но достаточная оценка «звучит одинаково». */
    private fun similarity(a: String, b: String): String {
        val wa = a.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length > 2 }.toSet()
        val wb = b.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return "—"
        return "%.0f%%".format(wa.intersect(wb).size * 100.0 / wa.union(wb).size)
    }
}
