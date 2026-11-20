package ai.nova.app.media

import ai.nova.app.notify.NovaNotificationListener
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import ai.nova.core.assistant.MediaAction
import ai.nova.core.util.Logger

/**
 * Управление чужими плеерами (Яндекс Музыка, «Звук», Spotify) через их медиа-сессии.
 *
 * Медиа-клавиша ненадёжна: у части плееров она уходит в никуда, если приложение ещё не подняло сессию,
 * а на Samsung её иногда перехватывает система. С доступом к уведомлениям (он у Новы уже есть ради
 * пушей банков) видно живую сессию плеера, и команда идёт прямо ему.
 */
class OtherPlayers(private val context: Context) {
    private val listener: ComponentName get() = ComponentName(context, NovaNotificationListener::class.java)

    /** Доступ к уведомлениям выдан — без него список сессий недоступен. */
    fun granted(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    /** Сессии всех плееров: чужих и нашей собственной (радио Новы тоже умеет ставиться на паузу). */
    fun sessions(): List<MediaController> = runCatching {
        if (!granted()) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        manager.getActiveSessions(listener)
    }.getOrDefault(emptyList())

    /** Плеер, который прямо сейчас играет. */
    fun playing(): MediaController? =
        sessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    /**
     * Отправить команду чужому плееру. false — управлять нечем, остаётся медиа-клавиша.
     * [packageName] — если известно приложение, командой не заденем чужой плеер.
     */
    fun control(action: MediaAction, packageName: String? = null): Boolean {
        val list = sessions().filter { packageName == null || it.packageName == packageName }
        // Играющий плеер важнее приостановленного: команду получает тот, кого слышно.
        val target = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.playbackState?.state != PlaybackState.STATE_NONE && it.packageName != context.packageName }
            ?: list.firstOrNull { it.playbackState?.state != PlaybackState.STATE_NONE }
            ?: return false
        val controls = target.transportControls
        val sent = runCatching {
            when (action) {
                MediaAction.PLAY -> controls.play()
                MediaAction.PAUSE -> controls.pause()
                MediaAction.NEXT -> controls.skipToNext()
                MediaAction.PREVIOUS -> controls.skipToPrevious()
            }
            true
        }.getOrElse {
            Logger.w(TAG, "плеер ${target.packageName} не принял команду", it)
            false
        }
        return sent
    }

    private companion object { const val TAG = "OtherPlayers" }
}
