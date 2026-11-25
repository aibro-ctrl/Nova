package ai.nova.app.finance

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ai.nova.app.AppContainer
import ai.nova.app.NovaApp
import ai.nova.app.R
import ai.nova.app.reminders.Notifications
import ai.nova.app.settings.BankPushMode
import ai.nova.core.finance.BankPush
import ai.nova.core.finance.BankPushParser
import ai.nova.core.util.Logger
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/**
 * Траты из пушей банковских приложений: «Покупка 850 ₽ · ПЯТЁРОЧКА» → расход в категории «Продукты».
 *
 * Режим задаётся в настройках расходов: выключено, спрашивать (по умолчанию) или записывать сразу.
 * Сумма и магазин берутся из уже пришедшего уведомления — интернет и доступ к банку не нужны.
 */
object BankPushIngest {
    private const val TAG = "BankPush"
    private const val PREFS = "nova_bank_pushes"
    private const val KEY_SEEN = "seen"
    private const val MAX_SEEN = 120

    internal const val EXTRA_AMOUNT = "amount"
    internal const val EXTRA_CURRENCY = "currency"
    internal const val EXTRA_CATEGORY = "category"
    internal const val EXTRA_DESCRIPTION = "description"
    internal const val EXTRA_NOTIFICATION = "notification_id"
    internal const val EXTRA_EXPENSE = "expense_id"

    const val ACTION_ADD = "ai.nova.action.BANK_PUSH_ADD"
    const val ACTION_SKIP = "ai.nova.action.BANK_PUSH_SKIP"
    const val ACTION_UNDO = "ai.nova.action.BANK_PUSH_UNDO"

    /** Разбор уведомления. Вызывается из слушателя уведомлений для каждого банковского пакета. */
    fun onNotification(context: Context, pkg: String, title: String, text: String) {
        val c = container(context) ?: return
        // Слушатель уведомлений не может ждать: берём уже загруженные настройки.
        val mode = runCatching { c.settings.settings.value.bankPushes }.getOrDefault(BankPushMode.ASK)
        if (mode == BankPushMode.OFF) return
        val known = BankPushParser.bank(pkg)
        // Незнакомое приложение спрашиваем только в режиме подтверждения: автоматически пишем лишь известные банки.
        if (known == null && mode != BankPushMode.ASK) return
        val push = runCatching { BankPushParser.parse(title, text, known) }.getOrNull() ?: return
        // Доходы в расходы не пишем: цель — траты.
        if (push.isIncome) return
        if (!firstTime(context, push)) return
        Logger.d(TAG, "Трата из уведомления ${push.bank}: ${push.merchant}")
        // Имя пакета банка может взять себе чужой APK, если настоящий банк не установлен.
        // Без подтверждения пишем только приложения из магазина или системные; остальное — спрашиваем.
        if (mode == BankPushMode.AUTO && trustedInstall(context, pkg)) {
            record(c, context, push.amountMinor, push.currency, push.category, push.description, undoable = true)
        } else {
            ask(context, push)
        }
    }

    /** Магазины приложений, из которых ставятся настоящие банковские клиенты. */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending", "ru.vk.store", "com.huawei.appmarket", "com.xiaomi.mipicks", "com.xiaomi.market",
        "com.sec.android.app.samsungapps", "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.amazon.venezia",
    )

    /** Банк поставлен из магазина или входит в прошивку — а не скачан файлом под чужим именем. */
    internal fun trustedInstall(context: Context, pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        if (info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).let { it.installingPackageName ?: it.initiatingPackageName }
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
        installer in TRUSTED_INSTALLERS
    }.getOrDefault(false)

    /** Запрос подтверждения: тихое уведомление с кнопками «Добавить» и «Не надо». */
    private fun ask(context: Context, push: BankPush) {
        val id = notificationId(push)
        val add = PendingIntent.getBroadcast(
            context, id, intentFor(context, push, ACTION_ADD, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skip = PendingIntent.getBroadcast(
            context, id + 1, intentFor(context, push, ACTION_SKIP, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val merchant = push.merchant.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_BANK)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Трата ${rub(push.amountMinor)}$merchant")
            .setContentText("${push.bank} · записать в расходы?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${push.bank}. ${push.raw}"))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Добавить", add)
            .addAction(0, "Не надо", skip)
            .build()
        Notifications.notifySafely(context, id, n)
    }

    /** Трата записана: короткое уведомление с возможностью отменить. */
    private fun notifyRecorded(context: Context, amountMinor: Long, currency: String, description: String, expenseId: String) {
        val id = Notifications.BANK_DONE_BASE_ID + (abs(description.hashCode()) % 1_000)
        val undo = PendingIntent.getBroadcast(
            context, id,
            Intent(context, BankPushReceiver::class.java)
                .setAction(ACTION_UNDO)
                .putExtra(EXTRA_EXPENSE, expenseId)
                .putExtra(EXTRA_NOTIFICATION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_BANK)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Записала трату ${rub(amountMinor)}")
            .setContentText(description.ifBlank { currency })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .addAction(0, "Отменить", undo)
            .build()
        Notifications.notifySafely(context, id, n)
    }

    /** Запись в расходы — в фоне, чтобы слушатель уведомлений не задерживал системный вызов. */
    fun record(
        c: AppContainer,
        context: Context,
        amountMinor: Long,
        currency: String,
        category: String,
        description: String,
        undoable: Boolean,
    ) {
        if (amountMinor <= 0) return
        c.appScope.launch {
            runCatching {
                c.store.expenses.create(amountMinor, currency, category, description, LocalDate.now())
            }.onSuccess { expense ->
                if (undoable) runCatching { notifyRecorded(context, amountMinor, currency, description, expense.id) }
            }.onFailure { e ->
                Logger.w(TAG, "Трата из уведомления не записана", e)
            }
        }
    }

    /** Тот же пуш банки часто повторяется (уведомление и виджет). Дубликаты отбрасываем. */
    private fun firstTime(context: Context, push: BankPush): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString(KEY_SEEN, "").orEmpty().split('\n').mapNotNull { line ->
            val at = line.substringAfterLast('|').toLongOrNull() ?: return@mapNotNull null
            if (now - at > 6 * 60 * 60_000L) null else line.substringBeforeLast('|')
        }
        if (push.dedupKey in recent) return false
        // Храним ключ и время: старые записи отсекаются по времени, список не растёт.
        val updated = (recent + push.dedupKey).takeLast(MAX_SEEN).joinToString("\n") { "$it|$now" }
        prefs.edit().putString(KEY_SEEN, updated).apply()
        return true
    }

    private fun intentFor(context: Context, push: BankPush, action: String, id: Int): Intent =
        Intent(context, BankPushReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_AMOUNT, push.amountMinor)
            .putExtra(EXTRA_CURRENCY, push.currency)
            .putExtra(EXTRA_CATEGORY, push.category)
            .putExtra(EXTRA_DESCRIPTION, push.description)
            .putExtra(EXTRA_NOTIFICATION, id)

    private fun notificationId(push: BankPush): Int =
        Notifications.BANK_BASE_ID + (abs(push.dedupKey.hashCode()) % 10_000)

    private fun container(context: Context): AppContainer? =
        (context.applicationContext as? NovaApp)?.let { runCatching { it.container }.getOrNull() }

    /** «850 ₽», «1 250,50 ₽» — как в остальном приложении. */
    fun rub(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val value = abs(minor)
        val rub = value / 100
        val kop = value % 100
        val digits = rub.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        return if (kop == 0L) "$sign$digits\u00A0₽" else "$sign$digits,${kop.toString().padStart(2, '0')}\u00A0₽"
    }
}

/** Кнопки в уведомлении о трате: добавить, пропустить, отменить. */
class BankPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as? NovaApp)?.container ?: return
        val notificationId = intent.getIntExtra(BankPushIngest.EXTRA_NOTIFICATION, 0)
        when (intent.action) {
            BankPushIngest.ACTION_ADD -> {
                val amount = intent.getLongExtra(BankPushIngest.EXTRA_AMOUNT, 0)
                val currency = intent.getStringExtra(BankPushIngest.EXTRA_CURRENCY).orEmpty().ifBlank { "RUB" }
                val category = intent.getStringExtra(BankPushIngest.EXTRA_CATEGORY).orEmpty()
                val description = intent.getStringExtra(BankPushIngest.EXTRA_DESCRIPTION).orEmpty()
                BankPushIngest.record(c, context, amount, currency, category, description, undoable = true)
            }
            BankPushIngest.ACTION_SKIP -> Unit
            BankPushIngest.ACTION_UNDO -> {
                val id = intent.getStringExtra(BankPushIngest.EXTRA_EXPENSE).orEmpty()
                if (id.isNotBlank()) {
                    c.appScope.launch {
                        runCatching { c.store.expenses.delete(id) }
                            .onFailure { Logger.w("BankPush", "Отмена записи не удалась", it) }
                    }
                }
            }
            else -> return
        }
        Notifications.cancel(context, notificationId)
    }
}
