package ai.nova.app.reminders

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Напоминания, которые уже сработали, но человек их не подтвердил — не нажал «Готово» и ничего не отложил.
 * Только на этом телефоне, без базы и без сети: «Мой день» говорит «Не подтверждено напоминаний: N»,
 * а как только нажали «Готово», «Отложить» или «Завтра» — запись стирается.
 */
class PendingStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mark(id: String, at: Instant = Instant.now()) {
        val edit = prefs.edit().putLong(id, at.toEpochMilli())
        // Старое не копим: запись, о которой забыли неделю назад, сводку за сегодня не портит.
        prefs.all.forEach { (key, value) -> if (value is Long && value < at.minusSeconds(KEEP_SECONDS).toEpochMilli()) edit.remove(key) }
        edit.apply()
    }

    fun clear(id: String) = prefs.edit().remove(id).apply()

    /** Сколько сегодняшних напоминаний осталось без подтверждения. */
    fun countToday(today: LocalDate, zone: ZoneId): Int =
        countToday(prefs.all.values.filterIsInstance<Long>(), today, zone)

    companion object {
        private const val PREFS = "nova_pending_reminders"
        private const val KEEP_SECONDS = 7L * 24 * 3600

        /** Моменты срабатывания считаем по календарю пользователя: вчерашнее в сегодняшнюю сводку не попадает. */
        fun countToday(firedAt: Collection<Long>, today: LocalDate, zone: ZoneId): Int =
            firedAt.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
    }
}
