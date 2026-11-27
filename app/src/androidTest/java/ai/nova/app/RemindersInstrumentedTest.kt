package ai.nova.app

import ai.nova.app.reminders.ReminderReceiver
import ai.nova.core.voice.SpeechText
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Настоящее срабатывание напоминания на эмуляторе: уведомление, разовое гаснет, речь по настройке. */
@RunWith(AndroidJUnit4::class)
class RemindersInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as NovaApp).container

    private fun shown(id: String) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ReminderReceiver.notificationId(id) }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("не дождались за $timeoutMs мс", condition())
    }

    private suspend fun createOverdue(c: AppContainer, text: String) =
        c.store.reminders.create(text, Instant.now().minusSeconds(30), null, ZoneId.systemDefault().id)

    private suspend fun cleanup(c: AppContainer, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(ReminderReceiver.notificationId(id))
        context.stopService(Intent(context, ai.nova.app.media.RingService::class.java))
        ai.nova.app.reminders.AlarmActivity.finishIfShowing(id)
        c.nags.clear(id)
        c.nags.clearCritical(id)
        c.store.reminders.delete(id)
    }

    /** Тест не должен оставлять включённую озвучку или подмену синтезатора следующим проверкам. */
    @After
    fun tearDown() = runBlocking {
        ReminderReceiver.speakOverride = null
        val c = container
        c.awaitReady()
        c.settings.setSpeakReminders(false)
        c.settings.setSpeakRemindersHeadphonesOnly(false)
        c.settings.setSpeakRemindersWhenLocked(false)
        c.settings.setTts(true)
    }

    @Test
    fun overdueReminderFiresOnceAndDeactivates() = runBlocking {
        val c = container
        c.awaitReady()
        val r = c.store.reminders.create("проверка эмулятора", Instant.now().minusSeconds(30), null, ZoneId.systemDefault().id)
        ReminderReceiver.fire(context, c, r.id)
        waitUntil { shown(r.id) } // показ тоже асинхронный
        assertFalse("разовое напоминание больше не активно", c.store.reminders.get(r.id)!!.active)
        context.getSystemService(NotificationManager::class.java).cancel(ReminderReceiver.notificationId(r.id))
        // Система убирает уведомление не мгновенно: ждём, пока оно исчезнет, иначе проверка ниже увидит старое.
        waitUntil { !shown(r.id) }
        // Повторный вызов (страховка рядом с будильником) не должен показать его снова.
        ReminderReceiver.fire(context, c, r.id)
        Thread.sleep(500)
        assertFalse("второй раз уведомления быть не должно", shown(r.id))
        c.store.reminders.delete(r.id)
        assertEquals(null, c.store.reminders.get(r.id)?.takeIf { it.active })
    }

    /** Настройка включена — текст читается вслух уже очищенным от разметки. */
    @Test
    fun reminderSpeaksCleanedTextWhenEnabled() = runBlocking {
        val c = container
        c.awaitReady()
        c.settings.setTts(true)
        c.settings.setSpeakReminders(true)
        c.settings.setSpeakRemindersHeadphonesOnly(false)
        c.settings.setSpeakRemindersWhenLocked(false)
        val spoken = mutableListOf<String>()
        ReminderReceiver.speakOverride = { spoken += it }
        val raw = "**Выпей** 1 таблетку"
        val r = createOverdue(c, raw)
        try {
            ReminderReceiver.fire(context, c, r.id)
            assertEquals("речь вызвана один раз", 1, spoken.size)
            assertEquals("текст очищен для речи", SpeechText.forSpeech(raw), spoken.single())
            assertFalse("разметка не читается вслух", spoken.single().contains("**"))
        } finally {
            cleanup(c, r.id)
        }
    }

    /** Настройка выключена — поведение как раньше: только уведомление. */
    @Test
    fun reminderIsSilentWhenDisabled() = runBlocking {
        val c = container
        c.awaitReady()
        c.settings.setTts(true)
        c.settings.setSpeakReminders(false)
        val spoken = mutableListOf<String>()
        ReminderReceiver.speakOverride = { spoken += it }
        val r = createOverdue(c, "проверка тишины")
        try {
            ReminderReceiver.fire(context, c, r.id)
            assertTrue("речи быть не должно", spoken.isEmpty())
        } finally {
            cleanup(c, r.id)
        }
    }

    /** Критичное напоминание звенит будильником, но голосом не дублируется. */
    @Test
    fun criticalReminderIsNotSpoken() = runBlocking {
        val c = container
        c.awaitReady()
        c.settings.setTts(true)
        c.settings.setSpeakReminders(true)
        c.settings.setSpeakRemindersHeadphonesOnly(false)
        c.settings.setSpeakRemindersWhenLocked(false)
        val spoken = mutableListOf<String>()
        ReminderReceiver.speakOverride = { spoken += it }
        val r = createOverdue(c, "критичная проверка")
        c.nags.markCritical(r.id)
        try {
            ReminderReceiver.fire(context, c, r.id)
            assertTrue("критичное голосом не дублируется", spoken.isEmpty())
        } finally {
            cleanup(c, r.id)
        }
    }
}
