package ai.nova.app

import ai.nova.app.media.OtherPlayers
import ai.nova.core.assistant.MediaAction
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Чужим плеером («Звук», Яндекс Музыка) Нова управляет через его медиа-сессию, а не только клавишей. */
@RunWith(AndroidJUnit4::class)
class OtherPlayersTest {
    @Test
    fun playGoesToLiveSession(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NovaApp>()
        val players = OtherPlayers(app)
        assumeTrue("нет доступа к уведомлениям — сессии не видны", players.granted())
        val instr = InstrumentationRegistry.getInstrumentation()
        var played = false
        var session: MediaSession? = null
        // Медиа-сессию можно создать только на потоке с Looper.
        instr.runOnMainSync {
            session = MediaSession(app, "probe").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        played = true
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build(),
                )
                isActive = true
            }
        }
        delay(1_000)
        val sent = players.control(MediaAction.PLAY)
        delay(1_000)
        instr.runOnMainSync {
            session?.isActive = false
            session?.release()
        }
        assertTrue("команда не ушла в сессию", sent)
        assertTrue("плеер не получил play", played)
    }
}
