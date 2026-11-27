package ai.nova.core.sync

/**
 * Синхронизация с Google Таблицей без OAuth: приложение шлёт JSON в веб-приложение Apps Script,
 * скрипт раскладывает записи по листу и возвращает объединённый набор обратно.
 *
 * Здесь только чистая логика, которую можно проверить без Android и без сети:
 * разбор ссылки на таблицу, слияние версий и предел размера ячейки.
 */

/** Одна запись в таблице: строка листа «table | id | updated_at | deleted | payload». */
data class SheetsRow(
    val table: String,
    val id: String,
    /** Метка изменения в миллисекундах: по ней решается, чья версия новее. */
    val updatedAt: Long,
    /** Надгробие: запись удалена на телефоне, но должна доехать до таблицы и до других устройств. */
    val deleted: Boolean,
    /** Сама запись в формате сервера (JSON-объект строкой). */
    val payload: String,
)

/** Что получилось после слияния: строки для записи и сколько не влезло в ячейку Google Таблиц. */
data class SheetsMergeResult(val rows: List<SheetsRow>, val tooLong: Int)

object SheetsSync {
    /**
     * В одной ячейке Google Таблиц помещается 50 000 символов. Оставляем запас на экранирование,
     * иначе большая заметка обрежет лист или сломает запись целиком.
     */
    const val MAX_CELL = 45_000

    private val LINK = Regex("""/spreadsheets/d/([A-Za-z0-9_-]{20,})""")
    private val BARE_ID = Regex("""^[A-Za-z0-9_-]{20,}$""")

    /**
     * ID таблицы из ссылки «docs.google.com/spreadsheets/d/…/edit» или из того, что вставил пользователь.
     * Возвращает null, если это не похоже ни на ссылку, ни на ID.
     */
    fun spreadsheetId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        LINK.find(text)?.let { return it.groupValues[1] }
        // Пользователь мог скопировать ID вместе с пробелами или кавычками.
        val bare = text.trim('"', '\'', ' ', '\n', '	')
        return bare.takeIf { BARE_ID.matches(it) }
    }

    /** Запись влезает в ячейку таблицы. */
    fun fits(payload: String): Boolean = payload.length <= MAX_CELL

    /**
     * Слияние по паре «таблица + id»: остаётся версия с более поздней меткой.
     * При равных метках побеждает локальная — на телефоне пользователь видит именно её.
     * Удалённые строки тоже участвуют: иначе удаление на одном устройстве не доедет до другого.
     */
    fun merge(local: List<SheetsRow>, remote: List<SheetsRow>): SheetsMergeResult {
        val merged = LinkedHashMap<Pair<String, String>, SheetsRow>()
        for (row in local) merged[row.table to row.id] = row
        for (row in remote) {
            val key = row.table to row.id
            val have = merged[key]
            if (have == null || row.updatedAt > have.updatedAt) merged[key] = row
        }
        var tooLong = 0
        val rows = merged.values.filter {
            if (fits(it.payload)) true else { tooLong++; false }
        }.sortedWith(compareBy({ it.table }, { it.id }))
        return SheetsMergeResult(rows, tooLong)
    }
}
