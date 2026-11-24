package ai.nova.app.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import ai.nova.app.notify.NovaNotificationListener

/**
 * Играет ли сейчас чужое приложение — чтобы Нова не говорила «включаю», когда плеер молчит.
 *
 * Состояние видно только при доступе к уведомлениям (он же нужен для «прочитай сообщения»),
 * поэтому ответ бывает и «не знаю»: тогда Нова не меняет обычный ответ.
 */
object PlaybackCheck {

    /** true — играет, false — молчит, null — узнать не можем (нет доступа к уведомлениям). */
    fun playing(context: Context, pkg: String): Boolean? = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val listener = ComponentName(context, NovaNotificationListener::class.java)
        manager.getActiveSessions(listener)
            .filter { it.packageName == pkg }
            .any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrNull()

    /** Ждём, пока плеер начнёт играть: после запуска приложению нужно время. */
    suspend fun waitPlaying(context: Context, pkg: String, attempts: Int = 3, pauseMs: Long = 800): Boolean? {
        var answer: Boolean? = null
        repeat(attempts) { step ->
            answer = playing(context, pkg) ?: return null
            if (answer == true) return true
            if (step < attempts - 1) kotlinx.coroutines.delay(pauseMs)
        }
        return answer
    }
}
