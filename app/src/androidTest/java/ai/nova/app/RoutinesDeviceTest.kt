package ai.nova.app

import ai.nova.app.reminders.ReminderReceiver
import ai.nova.app.ui.screens.deleteRoutine
import ai.nova.app.ui.screens.scheduleReminders
import ai.nova.core.model.Recurrence
import ai.nova.core.model.Routine
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Сценарии и их расписание на настоящем устройстве: удалённый сценарий не срабатывает сам,
 * а расписание, оставшееся от удалённого сценария, снимается при первом же срабатывании.
 */
@RunWith(AndroidJUnit4::class)
class RoutinesDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as NovaApp).container

    private fun shown(id: String) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ReminderReceiver.notificationId(id) }

    private suspend fun schedule(c: AppContainer, trigger: String) = run {
        val zone = c.time.zone()
        val rule = Recurrence(Recurrence.Frequency.DAILY, time = LocalTime.of(8, 0))
        c.store.reminders.create(Routine.SCHEDULE_PREFIX + trigger, rule.nextAfter(c.time.now(), zone), rule, zone.id)
    }

    /** Кнопка «×» в настройках удаляет сценарий — вместе с ним должно уйти и его расписание. */
    @Test
    fun deletedScenarioTakesItsScheduleWithIt() = runBlocking {
        val c = container
        c.awaitReady()
        val trigger = "проверка удаления ${System.currentTimeMillis()}"
        val routine = c.store.routines.save(trigger, listOf("какая погода"))
        val reminder = schedule(c, trigger)
        assertTrue("расписание создано", c.scheduleReminders(trigger).isNotEmpty())

        deleteRoutine(c, routine)

        assertTrue("сценарий удалён", c.store.routines.all().none { it.id == routine.id })
        // И сам по себе он больше не выполняется, даже если напоминание о нём где-то осталось.
        assertNull("удалённый сценарий не выполняется", c.engine.runScheduledRoutine(Routine.SCHEDULE_PREFIX + trigger))
        assertTrue("расписание снято: ${c.scheduleReminders(trigger)}", c.scheduleReminders(trigger).isEmpty())
        assertFalse("напоминание больше не активно", c.store.reminders.get(reminder.id)?.active == true)
    }

    /** Расписание, оставшееся от старой версии, не показывает «не удалось выполнить сценарий». */
    @Test
    fun scheduleWithoutScenarioIsDroppedSilently() = runBlocking {
        val c = container
        c.awaitReady()
        val trigger = "удалённый сценарий ${System.currentTimeMillis()}"
        val reminder = schedule(c, trigger)
        // Сценарий уже удалён, а напоминание осталось — как у тех, кто удалил его до исправления.
        c.store.reminders.update(reminder.copy(triggerAt = Instant.now().minusSeconds(30)))

        ReminderReceiver.fire(context, c, reminder.id)
        Thread.sleep(500)

        assertFalse("уведомления быть не должно", shown(reminder.id))
        assertTrue("осиротевшее расписание снято", c.store.reminders.get(reminder.id) == null || c.store.reminders.get(reminder.id)?.active == false)
    }
}
