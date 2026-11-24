package ai.nova.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
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

    /**
     * Что играет: «Кино — Группа крови». null — прочитать нельзя (нет доступа к уведомлениям)
     * или плеер молчит. Нужен, чтобы отличить новую песню от прошлой: иначе старый трек
     * выдаётся за включённый, и Нова говорит «включаю», пока играет совсем другое.
     */
    fun track(context: Context, pkg: String): String? {
        val fromSession = runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val listener = ComponentName(context, NovaNotificationListener::class.java)
            manager.getActiveSessions(listener).firstOrNull { it.packageName == pkg }
                ?.metadata
                ?.let { m ->
                    val title = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                    val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
                    listOf(artist, title).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" — ")
                }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        // «Звук» название через сессию не отдаёт вовсе — остаётся его медиа-уведомление.
        return fromSession ?: NovaNotificationListener.playingTitle(pkg)
    }

    /**
     * Заиграла ли названная песня. true — да; false — плеер играет что-то другое;
     * null — проверить не можем, тогда Нове остаётся верить приложению.
     */
    suspend fun waitTrack(
        context: Context,
        pkg: String,
        before: String?,
        query: String,
        attempts: Int = 5,
        pauseMs: Long = 800,
    ): Boolean? {
        val want = query.lowercase().split(' ', ',', '—', '-')
            .map { it.trim().trim('.', '!', '?') }
            .filter { it.length >= 3 }
        repeat(attempts) { step ->
            val now = track(context, pkg) ?: return null
            if (now != before && (want.isEmpty() || want.any { now.lowercase().contains(it) })) return true
            if (step < attempts - 1) kotlinx.coroutines.delay(pauseMs)
        }
        return false
    }

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
