package ai.nova.app.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import ai.nova.core.skills.IncomingMessage
import ai.nova.core.util.Logger
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * «Прочитай сообщения» и «ответь Маше…»: Нова видит уведомления мессенджеров.
 * Сообщения хранятся только в памяти, пока уведомление висит в шторке, и никуда не отправляются.
 * Ответ уходит через кнопку «Ответить» самого уведомления — в тот же чат.
 *
 * Системные обратные вызовы ничего не разбирают сами: только ставят задачу в очередь и сразу возвращаются.
 * После установки, перезагрузки или обновления банковского приложения система отдаёт десятки уведомлений
 * подряд, и разбор на главном потоке вешал интерфейс на секунды (ANR «executing service»).
 */
class NovaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        service = this
        // После переподключения старые записи могли устареть (уведомления смахнули, пока нас не было).
        synchronized(entries) { entries.clear() }
        // Список действующих уведомлений — отдельный запрос к системе, он тоже бывает небыстрым.
        submit {
            runCatching { activeNotifications?.forEach { add(it) } }
                .onFailure { Logger.w(TAG, "Уведомления не прочитаны", it) }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        service = null
        // Пока нас не слушает система, актуальность сообщений неизвестна: старые не отдаём.
        synchronized(entries) { entries.clear() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        submit { add(sbn, fresh = true) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        submit { synchronized(entries) { entries.removeAll { it.message.key == key } } }
    }

    private fun add(sbn: StatusBarNotification, fresh: Boolean = false) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        // Пуш банка: «Покупка 850 ₽ · ПЯТЁРОЧКА» → расход в Нова (если включено в настройках расходов).
        // Смотрим все уведомления: банк может быть не в списке — тогда Нова спросит подтверждение.
        if (fresh) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            runCatching { ai.nova.app.finance.BankPushIngest.onNotification(this, sbn.packageName, title, body) }
                .onFailure { Logger.w("BankPush", "Пуш банка не разобран", it) }
            if (ai.nova.core.finance.BankPushParser.bank(sbn.packageName) != null) return
        }
        // Только известные мессенджеры и приложение SMS по умолчанию: иначе любое приложение могло бы выдать себя
        // за «Машу» и получить ответ, продиктованный Нова.
        val trusted = sbn.packageName in MESSENGERS ||
            sbn.packageName == runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        if (!trusted) return
        val (sender, text, latest) = parse(extras) ?: return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val msg = IncomingMessage(sbn.key, app, sender, text, Instant.ofEpochMilli(sbn.postTime), canReply = reply != null)
        val isNew = synchronized(entries) {
            val before = entries.firstOrNull { it.message.key == sbn.key }?.message?.text
            entries.removeAll { it.message.key == sbn.key }
            entries.addFirst(Entry(msg, reply))
            while (entries.size > 40) entries.removeLast()
            before != text
        }
        // Режим «за рулём»: новое сообщение (а не повтор того же уведомления) — читаем вслух.
        // Читаем только последнее сообщение чата: в тексте уведомления (MessagingStyle) висят и предыдущие, они уже прозвучали.
        if (fresh && isNew) runCatching { onNewMessage?.invoke(msg.copy(text = latest)) }
    }

    /** Отправитель и текст: из MessagingStyle (последние сообщения чата) или из заголовка/текста. */
    private fun parse(extras: Bundle): Triple<String, String, String>? {
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null
        val messages = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
        }.getOrNull().orEmpty()
        val latest = messages.lastOrNull { it.isNotBlank() }?.trim()
        val text = messages.takeLast(3).joinToString(" · ").ifBlank {
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        }.trim()
        if (text.isBlank()) return null
        // «2 новых сообщения» — сводка без текста, не читаем.
        if (Regex("""^\d+\s+(?:новых?|new)\s""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return Triple(title.trim(), text, latest ?: text)
    }

    private data class Entry(val message: IncomingMessage, val reply: Notification.Action?)

    companion object {
        private const val TAG = "Messages"
        private val entries = ArrayDeque<Entry>()
        @Volatile private var connected = false
        /** Последний подключённый экземпляр: через него читаем медиа-уведомления («что играет»). */
        @Volatile private var service: NovaNotificationListener? = null
        /** Новое сообщение пришло (для режима «за рулём»). */
        @Volatile var onNewMessage: ((IncomingMessage) -> Unit)? = null

        /**
         * Очередь разбора: один поток сохраняет порядок уведомлений, размер ограничен — при шторме
         * уведомлений новые пропускаем, а не копим в памяти. Поток живёт только пока есть работа.
         */
        private val work = ThreadPoolExecutor(
            1, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue(64),
            { r -> Thread(r, "nova-notifications").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
            ThreadPoolExecutor.DiscardPolicy(),
        )

        private fun submit(block: () -> Unit) {
            runCatching { work.execute(block) }
        }

        private val MESSENGERS = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "com.whatsapp", "com.whatsapp.w4b",
            "com.vkontakte.android", "com.viber.voip", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "ru.oneme.app", "com.discord", "com.android.messaging", "com.miui.mms", "com.vivo.message", "com.oneplus.mms",
            "com.huawei.message", "com.coloros.mms", "org.thoughtcrime.securesms", "im.vector.app", "com.icq.mobile.client", "com.facebook.orca", "com.instagram.android", "com.skype.raider", "ru.mail.mailapp",
        )

        fun enabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun messages(): List<IncomingMessage> = synchronized(entries) { entries.map { it.message } }

        /**
         * Что играет приложение — из его медиа-уведомления: «25/17 — Чернотроп».
         * Плееры вроде «Звука» не отдают название через медиа-сессию, а в уведомлении оно есть.
         * null — уведомления нет или доступ к ним выключен.
         */
        fun playingTitle(pkg: String): String? = runCatching {
            val list = service?.activeNotifications ?: return null
            val sbn = list.firstOrNull {
                it.packageName == pkg &&
                    (it.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0
            } ?: return null
            val extras = sbn.notification?.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
            listOf(title, text).map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" — ").takeIf { it.isNotEmpty() }
        }.getOrNull()

        /** Ответ в чат через «Ответить» уведомления. */
        fun reply(context: Context, key: String, text: String): Boolean {
            val action = synchronized(entries) { entries.firstOrNull { it.message.key == key }?.reply } ?: return false
            val inputs = action.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                RemoteInput.addResultsToIntent(inputs, intent, results)
                action.actionIntent.send(context, 0, intent)
                synchronized(entries) { entries.removeAll { it.message.key == key } }
                true
            } catch (e: Exception) {
                Logger.w(TAG, "Ответ не отправлен", e)
                false
            }
        }

        /** Экран «Доступ к уведомлениям», сразу на пункте Нова (Android 11+). */
        fun settingsIntent(context: Context): Intent {
            val cn = ComponentName(context, NovaNotificationListener::class.java)
            return if (android.os.Build.VERSION.SDK_INT >= 30) {
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
