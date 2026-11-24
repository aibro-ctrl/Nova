package ai.nova.app.debug

import ai.nova.app.NovaApp
import ai.nova.core.assistant.InputSource
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Только debug-сборка: прогон текстовой фразы через движок без микрофона.
 * `adb shell input text` не умеет кириллицу, поэтому проверить команды на устройстве иначе неудобно.
 *
 * adb shell am broadcast -a ai.nova.app.DEBUG_COMMAND -p ai.nova.app --es text "какая погода"
 * Вопрос и ответ печатаются в logcat с тегом NovaProbe.
 */
class CommandProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val spoken = intent.getStringExtra("say")?.trim().orEmpty()
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        if (spoken.isEmpty() && text.isEmpty()) return
        val app = context.applicationContext as? NovaApp ?: return
        val pending = goAsync()
        app.container.appScope.launch(Dispatchers.IO) {
            try {
                app.container.awaitReady()
                if (spoken.isNotEmpty()) {
                    // Полный путь как из чата: разбор, ответ и озвучка с перебиванием.
                    Log.i(TAG, "SAY: $spoken")
                    app.container.voice.submitText(spoken)
                    return@launch
                }
                val started = android.os.SystemClock.elapsedRealtime()
                val reply = withTimeoutOrNull(90_000) { app.container.engine.handle(text, InputSource.TEXT) }
                val ms = android.os.SystemClock.elapsedRealtime() - started
                Log.i(TAG, "Q: $text")
                Log.i(TAG, "A: ${reply?.text ?: "<таймаут>"} (${ms} мс)")
            } catch (e: Exception) {
                Log.e(TAG, "FAIL", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NovaProbe"
    }
}
