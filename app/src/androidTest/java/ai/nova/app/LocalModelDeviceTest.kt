package ai.nova.app

import ai.nova.app.llm.LlamaNative
import ai.nova.core.assistant.InputSource
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Ответ офлайн-модели на нестандартный вопрос — на выбранной модели, как в чате.
 * Раньше в release-сборке минификатор переименовывал колбэк JNI, и приложение падало
 * (SIGABRT, «no non-static method …onToken»), не дав ответа.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<NovaApp>()

    /**
     * Без 64-битного ARM и без скачанной модели проверять нечего: сборка llama.cpp есть только под arm64,
     * а на x86_64-эмуляторе модели нет — раньше тест падал «модель не готова» и красил CI.
     */
    @Before
    fun requireLocalModel() {
        assumeTrue("нет 64-битного ARM или библиотека не загрузилась", LlamaNative.unsupportedReason == null)
        val models = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "llm")
        assumeTrue("офлайн-модель не скачана — проверять нечего", models.listFiles()?.any { it.isFile && it.name.endsWith(".gguf") } == true)
    }

    @Test
    fun offlineModelAnswersFreeQuestion(): Unit = runBlocking {
        val c = app.container
        Log.i("LocalModel", "state=${c.offlineLlm.state.value} available=${c.offlineLlm.available}")
        assertTrue("модель не готова", c.offlineLlm.available)
        val reply = c.engine.handle("кто твой создатель?", InputSource.TEXT)
        Log.i("LocalModel", "ответ: ${reply.text}")
        assertTrue("пустой ответ — модель промолчала", reply.text.isNotBlank())
        assertTrue("о создателе Нова должна сказать сама", reply.text.contains("Новосибирск", ignoreCase = true))
    }

    /** Вопрос, который разбор команд не узнаёт: отвечает именно офлайн-модель (JNI + колбэк по токенам). */
    @Test
    fun offlineModelGeneratesText(): Unit = runBlocking {
        val c = app.container
        val reply = c.engine.handle("почему небо голубое?", InputSource.TEXT)
        Log.i("LocalModel", "ответ модели: ${reply.text}")
        assertTrue("пустой ответ — модель промолчала", reply.text.isNotBlank())
        assertTrue("ответ слишком короткий: ${reply.text}", reply.text.length > 20)
    }
}
