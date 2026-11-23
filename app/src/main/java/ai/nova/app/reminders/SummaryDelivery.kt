package ai.nova.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ai.nova.app.AppContainer
import ai.nova.app.R
import ai.nova.app.ui.MainActivity
import ai.nova.core.util.Logger
import ai.nova.core.voice.SpeechText
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * Единый способ отдать сводку по расписанию: приветствие по имени, полный текст в историю общения,
 * озвучка целиком и уведомление, по нажатию открывающее чат с этой сводкой.
 *
 * Уведомление физически не может показать длинный текст (он обрезается), а история и голос — могут,
 * поэтому сводка всегда сохраняется и зачитывается полностью, а не только первой строкой.
 */
object SummaryDelivery {
    private const val TAG = "SummaryDelivery"

    /** Сколько ждать синтезатор: сводка длинная, обрываем только совсем зависшую озвучку. */
    private const val SPEAK_TIMEOUT_MS = 5 * 60 * 1000L

    /** «Доброе утро, Саня!» — имя из настройки «Как вас зовут». */
    suspend fun greeting(c: AppContainer, hour: Int = LocalTime.now().hour): String {
        val s = c.settings.current()
        return ai.nova.core.assistant.Persona.of(s.persona).briefGreeting(hour, s.userName)
    }

    /** Полный текст сводки: приветствие по имени и всё, что рассказала бы Нова. */
    suspend fun fullText(c: AppContainer, text: String, hour: Int = LocalTime.now().hour): String {
        val body = text.trim()
        if (body.isEmpty()) return body
        return greeting(c, hour) + "\n" + body
    }

    /**
     * Сохраняет сводку в историю, показывает уведомление, по нажатию открывающее чат,
     * и зачитывает её целиком, если голос включён. Возвращает полный текст.
     */
    suspend fun deliver(
        context: Context,
        c: AppContainer,
        notificationId: Int,
        title: String,
        text: String,
        speak: Boolean = true,
    ): String {
        val full = fullText(c, text)
        if (full.isEmpty()) return full
        c.engine.recordScheduledReport(full)
        Notifications.notifySafely(context, notificationId, notification(context, notificationId, title, full))
        // Сначала уведомление и запись в историю — чтение вслух идёт следом и не задерживает их.
        if (speak && c.settings.current().ttsEnabled) {
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                runCatching { c.speech.speak(SpeechText.forSpeech(full)) }
                    .onFailure { Logger.w(TAG, "Не удалось озвучить сводку", it) }
            }
        }
        return full
    }

    /** Уведомление о сводке: заголовок и первая строка снаружи, весь текст — в развёрнутом виде. */
    fun notification(context: Context, notificationId: Int, title: String, fullText: String): android.app.Notification {
        val first = firstLine(fullText)
        return NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            // Сводка — личное: на экране блокировки только заголовок.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
                    .setSmallIcon(R.drawable.ic_stat_nova).setContentTitle(title).build(),
            )
            .setContentIntent(openChat(context, notificationId))
            .setAutoCancel(true)
            .build()
    }

    /** Нажатие на уведомление открывает приложение сразу на чате, где лежит вся сводка. */
    fun openChat(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Первая содержательная строка: приветствие уходит в заголовок, дальше — суть. */
    private fun firstLine(fullText: String): String {
        val lines = fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.getOrElse(1) { lines.firstOrNull().orEmpty() }
    }
}
