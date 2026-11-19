package ai.nova.app

import ai.nova.app.llm.LlamaNative
import ai.nova.core.ai.LocalModels
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Настоящая проверка офлайн-модели на устройстве: загрузка и ответ на русский вопрос.
 * Пропускается, если модель ещё не скачана (на чистом телефоне и в CI её нет).
 */
@RunWith(AndroidJUnit4::class)
class OfflineModelInstrumentedTest {
    @Test
    fun modelAnswersInRussian() {
        assumeTrue("нет 64-битного ARM или библиотека не загрузилась", LlamaNative.unsupportedReason == null)

        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "llm")
        val model = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".gguf") }
        assumeTrue("офлайн-модель не скачана — проверять нечего", model != null)

        val handle = LlamaNative.load(model!!.absolutePath, 2048, 4)
        assertTrue("модель не загрузилась", handle != 0L)
        val started = System.currentTimeMillis()
        try {
            val out = LlamaNative.generate(
                handle,
                arrayOf("system", "Ты — Нова, дружелюбный ассистент. Отвечай по-русски, коротко: 1–3 предложения.", "user", "Что такое борщ?"),
                120,
                0.6f,
                null,
            )
            val elapsed = System.currentTimeMillis() - started
            val answer = LocalModels.stripThinking(out)
            println("Офлайн-модель: $model.name, ${elapsed}мс, ответ: $answer")
            assertTrue("пустой ответ модели", answer.isNotBlank())
            // Модель не должна уходить в рассуждения: иначе пользователь видит «Думаю…» и ничего не происходит.
            assertTrue("в ответе остались размышления: $answer", !answer.contains("<think"))
            assertTrue("ответ не по-русски: $answer", Regex("""[а-яА-Я]""").containsMatchIn(answer))
        } finally {
            LlamaNative.free(handle)
        }
    }
}
