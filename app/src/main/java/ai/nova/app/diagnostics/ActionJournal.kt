package ai.nova.app.diagnostics

import android.content.Context
import ai.nova.core.assistant.ActionSummary
import ai.nova.core.assistant.AssistantAction
import ai.nova.core.assistant.AssistantReply
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Журнал действий: что и когда Нова выполнила. Хранится только на телефоне, последние [MAX] записей.
 * Нужен для предсказуемости: человек всегда может посмотреть, что именно произошло, и отменить последнее.
 */
class ActionJournal(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val at: Instant, val what: String, val reply: String) {
        fun label(): String = TIME.format(at.atZone(ZoneId.systemDefault())) + " — " + what
    }

    fun log(actions: List<AssistantAction>, reply: AssistantReply) {
        if (actions.isEmpty()) return
        val what = actions.joinToString(", ") { ActionSummary.describe(it) }
        val line = buildString {
            append(Instant.now().toEpochMilli()).append('\t')
            append(sanitize(what)).append('\t')
            append(sanitize(reply.text.lineSequence().first().take(200)))
        }
        val old = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        val next = (listOf(line) + old).take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    /** Новые записи первыми. */
    fun entries(): List<Entry> = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val at = parts[0].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null
        Entry(at, parts[1], parts[2])
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** Последнее действие — для «отмени последнее действие», если у команды телефона есть обратный ход. */
    fun last(): Entry? = entries().firstOrNull()

    private fun sanitize(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    companion object {
        /** Больше не нужно: журнал — для «что было недавно», а не архив. */
        const val MAX = 40
        private const val PREFS = "nova_action_log"
        private const val KEY = "entries"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
