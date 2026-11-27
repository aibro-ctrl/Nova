package ai.nova.app.sync

import ai.nova.app.security.KeystoreSecretStore
import ai.nova.app.settings.SettingsRepository
import ai.nova.core.data.LocalStore
import ai.nova.core.data.NovaJson
import ai.nova.core.sync.SheetsRow
import ai.nova.core.sync.SheetsSync
import ai.nova.core.sync.SyncableTable
import ai.nova.core.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Двусторонняя синхронизация с Google Таблицей.
 *
 * Прямого доступа к Sheets API нет: он требует OAuth-клиента, которого в открытом проекте быть не может.
 * Вместо этого приложение шлёт JSON в веб-приложение Apps Script, опубликованное владельцем таблицы.
 * Скрипт хранит строки листа «Нова», сливает их с присланными по updated_at и возвращает итог.
 *
 * Это тот же принцип, что у резервной копии: last-write-wins по метке изменения, удаления едут
 * надгробиями. Поэтому переустановка приложения не теряет данные: после ввода тех же трёх значений
 * таблица отдаёт всё, что в ней накопилось.
 */
class SheetsSyncEngine(
    private val store: LocalStore,
    private val settings: SettingsRepository,
    private val secrets: KeystoreSecretStore,
    private val http: HttpClient,
    private val clock: () -> Instant = Instant::now,
) {
    /** Итог одного прохода: сколько отправили, сколько забрали и что пошло не так. */
    data class Report(
        val sent: Int = 0,
        val pulled: Int = 0,
        val tooLong: Int = 0,
        val error: String? = null,
        val finishedAt: Instant? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private data class Config(val spreadsheetId: String, val url: String, val token: String)

    private data class Reply(val rows: List<SheetsRow>, val tooLong: Int, val error: String?)

    /** Один проход синхронизации за раз — кнопка и Worker не должны идти одновременно. */
    private val mutex = Mutex()

    /** Всё, что нужно для работы, задано. Экран показывает это как «настроено / не настроено». */
    suspend fun isConfigured(): Boolean = config() != null

    /** Проверка связи без отправки записей: скрипт отвечает «ok». */
    suspend fun test(): Report {
        val cfg = config() ?: return Report(error = NOT_CONFIGURED)
        return try {
            val reply = request(cfg, ACTION_PING, emptyList())
            Report(error = reply.error, finishedAt = clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Проверка связи с Google Таблицей не удалась", e)
            Report(error = reason(e), finishedAt = clock())
        }
    }

    /**
     * Один проход: отправляем все локальные строки, получаем объединённый набор и применяем то,
     * что новее локального. Возвращает отчёт; исключения наружу не выходят, чтобы Worker не падал.
     *
     * Проходы не пересекаются: одну и ту же таблицу могут одновременно дёрнуть кнопка и Worker
     * (автосинхронизация), а два полных набора строк вперемешку дали бы лишние конфликты.
     */
    suspend fun sync(): Report = mutex.withLock {
        val cfg = config() ?: return@withLock Report(error = NOT_CONFIGURED)
        val s = settings.current()
        val tables = store.syncTables.filter { enabled(it, s.syncChat) }
        val local = tables.flatMap { table -> table.allRows().map { it.toSheets(table.remoteTable) } }
        try {
            val reply = request(cfg, ACTION_SYNC, local)
            if (reply.error != null) return@withLock Report(sent = local.size, error = reply.error, finishedAt = clock())
            val pulled = apply(tables, local, reply.rows)
            settings.setSheetsLastSyncAt(clock().toEpochMilli())
            Report(sent = local.size, pulled = pulled, tooLong = reply.tooLong, finishedAt = clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Синхронизация с Google Таблицей не удалась", e)
            Report(sent = local.size, error = reason(e), finishedAt = clock())
        }
    }

    /** Записывает в базу строки, которые в таблице новее локальных. Возвращает число изменённых. */
    private suspend fun apply(tables: List<SyncableTable>, local: List<SheetsRow>, remote: List<SheetsRow>): Int {
        val byKey = local.associateBy { it.table to it.id }
        var pulled = 0
        for (row in remote) {
            val table = tables.firstOrNull { it.remoteTable == row.table } ?: continue
            val have = byKey[row.table to row.id]
            if (have != null && have.updatedAt >= row.updatedAt) continue
            val payload = runCatching { NovaJson.parseToJsonElement(row.payload).jsonObject }.getOrNull() ?: continue
            // dirty = false: запись пришла из таблицы, отправлять её обратно не нужно.
            // guard: если запись изменили, пока шёл запрос, правку пользователя не затираем.
            table.write(payload, dirty = false, syncedUpdatedAt = null, expectedLocalUpdatedAt = have?.updatedAt, guard = true)
            pulled++
        }
        return pulled
    }

    private suspend fun config(): Config? {
        val s = settings.current()
        val id = SheetsSync.spreadsheetId(s.sheetsId) ?: return null
        val url = s.sheetsWebAppUrl.trim()
        val token = secrets.get(KeystoreSecretStore.SHEETS_TOKEN)?.trim().orEmpty()
        if (url.isEmpty() || token.isEmpty()) return null
        return Config(id, url, token)
    }

    private suspend fun request(cfg: Config, action: String, rows: List<SheetsRow>): Reply {
        val body = buildJsonObject {
            put("token", cfg.token)
            put("spreadsheetId", cfg.spreadsheetId)
            put("action", action)
            put("rows", JsonArray(rows.map { it.toJson() }))
        }
        val response = http.post(cfg.url) {
            // Таблица с историей может отвечать дольше обычного запроса.
            timeout {
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        val root = runCatching { NovaJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Reply(emptyList(), 0, "Веб-приложение ответило не по-нашему (${response.status.value}). Проверьте, что развёртывание открыто «для всех».")
        if (root["ok"]?.jsonPrimitive?.booleanOrNull == false) {
            return Reply(emptyList(), 0, root["error"]?.jsonPrimitive?.contentOrNull ?: "Скрипт вернул ошибку")
        }
        val list = (root["rows"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val table = o.str("table") ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            SheetsRow(
                table = table,
                id = id,
                updatedAt = o.long("updatedAt") ?: o.long("updated_at") ?: 0,
                deleted = o.bool("deleted") ?: false,
                payload = o.str("payload").orEmpty(),
            )
        }.orEmpty()
        val tooLong = root["tooLong"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return Reply(list, tooLong, null)
    }

    private fun enabled(table: SyncableTable, syncChat: Boolean): Boolean =
        table.remoteTable != CHAT_TABLE || syncChat

    private fun ai.nova.core.sync.SyncRow.toSheets(table: String) =
        SheetsRow(table = table, id = id, updatedAt = updatedAt, deleted = deleted, payload = payload.toString())

    private fun SheetsRow.toJson() = buildJsonObject {
        put("table", table)
        put("id", id)
        put("updatedAt", updatedAt)
        put("deleted", deleted)
        put("payload", payload)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Нет интернета."
        is java.net.SocketTimeoutException -> "Веб-приложение не ответило вовремя."
        else -> e.message ?: e::class.simpleName.orEmpty()
    }

    companion object {
        private const val TAG = "Sheets"
        private const val TIMEOUT_MS = 120_000L
        private const val ACTION_SYNC = "sync"
        private const val ACTION_PING = "ping"
        private const val CHAT_TABLE = "conversation_messages"
        const val NOT_CONFIGURED = "Укажите ссылку на таблицу, адрес веб-приложения и код доступа"
    }
}
