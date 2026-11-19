package ai.nova.app.media

import android.content.Context
import android.media.AudioManager

/**
 * Приглушение музыки, пока Нова говорит или слушает: иначе её не слышно, а микрофон ловит песню
 * вместо голоса и приходится почти кричать. Работает и со своей музыкой, и с чужими плеерами
 * (Spotify, Звук, Яндекс Музыка) — громкость системного потока общая для всех.
 */
object MusicDucker {
    private const val PREFS = "nova_ducker"
    private const val KEY_SAVED = "saved"
    private const val KEY_DUCKED = "ducked"

    /** Кто попросил тишины: «speech» (Нова говорит) и «listen» (Нова слушает). */
    private val holders = mutableSetOf<String>()

    @Volatile private var savedVolume: Int = -1
    @Volatile private var duckedVolume: Int = -1

    fun request(context: Context, key: String) = change(context, key, on = true)

    fun release(context: Context, key: String) = change(context, key, on = false)

    private fun change(context: Context, key: String, on: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        synchronized(this) {
            val wasEmpty = holders.isEmpty()
            if (on) holders.add(key) else holders.remove(key)
            if (holders.isEmpty() == wasEmpty) return
            if (holders.isNotEmpty()) duck(context, am) else restore(context, am)
        }
    }

    /**
     * Если приложение закрыли в момент приглушения, громкость осталась низкой.
     * При следующем запуске возвращаем её (если человек не менял сам).
     */
    fun restoreStale(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_SAVED, -1)
        val ducked = prefs.getInt(KEY_DUCKED, -1)
        if (saved < 0) return
        val now = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (now == ducked) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        prefs.edit().remove(KEY_SAVED).remove(KEY_DUCKED).apply()
    }

    private fun duck(context: Context, am: AudioManager) {
        if (savedVolume >= 0) return
        // Музыка не играет — трогать громкость незачем, иначе следующий трек заиграет тихо.
        if (!am.isMusicActive) return
        val current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        if (current <= 0) return
        val target = (current / 4).coerceAtLeast(1)
        savedVolume = current
        duckedVolume = target
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SAVED, current).putInt(KEY_DUCKED, target).apply()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    private fun restore(context: Context, am: AudioManager) {
        val saved = savedVolume
        savedVolume = -1
        if (saved < 0) return
        // Человек сам успел поменять громкость — его выбор важнее, не возвращаем.
        val now = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        if (now != duckedVolume) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        duckedVolume = -1
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SAVED).remove(KEY_DUCKED).apply()
    }
}
