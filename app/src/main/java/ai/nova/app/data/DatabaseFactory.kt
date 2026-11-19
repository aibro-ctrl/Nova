package ai.nova.app.data

import android.content.Context
import ai.nova.app.security.KeystoreSecretStore
import ai.nova.core.db.NovaDatabase
import ai.nova.core.util.Logger
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Локальная БД зашифрована SQLCipher. Пароль — случайные 256 бит, хранятся зашифрованными ключом Android Keystore.
 *
 * Обновление приложения не должно стирать записи, поэтому файл БД никогда не удаляется:
 * если база не открылась или оказалась пустой, сначала пробуем вернуть данные из отложенной копии
 * (`nova.db.bak-*`, `nova.db.orphan-*`), и только потом создаём новую базу.
 */
object DatabaseFactory {
    private const val TAG = "Database"
    const val NAME = "nova.db"

    /** Из какой копии восстановили данные при последнем запуске (для сообщения на главной). */
    val recovered: MutableStateFlow<String?> = MutableStateFlow(null)

    fun create(context: Context, secrets: KeystoreSecretStore): SqlDriver {
        System.loadLibrary("sqlcipher")
        val main = context.getDatabasePath(NAME)
        // Пароль берём ДО любых действий с файлом. Если Keystore временно недоступен, исключение уходит наружу:
        // базу мы не трогаем (раньше её в этот момент откладывали и создавали пустую — данные «пропадали»).
        val passphrase = secrets.databasePassphrase()

        val current = if (main.exists()) tryOpen(context, main, passphrase) else null
        if (current != null && records(current) != false) return current

        // База пуста, не открылась или пропала: рядом могли остаться копии — возвращаем самую свежую с данными.
        val restored = restoreFromBackup(context, main, secrets, passphrase)
        if (restored != null) {
            current?.close()
            return restored
        }
        // Основного файла нет вовсе — обычная новая установка.
        if (!main.exists()) return open(context, passphrase)
        if (current != null) return current

        // Файл есть, но не открывается: откладываем (не удаляем) и начинаем с чистой БД.
        Logger.e(TAG, "Зашифрованная БД не открылась — сохраняю копию и создаю новую")
        val suffix = System.currentTimeMillis().toString()
        main.renameTo(File(main.parentFile, "$NAME.bak-$suffix"))
        sidecars(main).forEach { it.delete() }
        secrets.moveDatabasePassphraseAside(suffix)
        return open(context, secrets.databasePassphrase())
    }

    /**
     * Пытается вернуть на место копию БД с записями.
     * @return открытая основная БД или null, если подходящей копии нет.
     */
    private fun restoreFromBackup(context: Context, main: File, secrets: KeystoreSecretStore, passphrase: ByteArray): SqlDriver? {
        val dir = main.parentFile ?: return null
        val backups = dir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.startsWith("$NAME.bak-") || it.name.startsWith("$NAME.orphan-")) }
            .sortedByDescending { it.lastModified() }
        for (file in backups) {
            val suffix = file.name.substringAfterLast('-')
            val passphrases = buildList {
                add(passphrase)
                secrets.databasePassphraseBackup(suffix)?.let { add(it.toByteArray(Charsets.UTF_8)) }
            }
            for (candidate in passphrases) {
                val driver = tryOpen(context, file, candidate) ?: continue
                if (records(driver) != true) {
                    driver.close()
                    continue
                }
                driver.close()
                val stamp = file.lastModified()
                if (!candidate.contentEquals(passphrase)) secrets.promoteDatabasePassphrase(suffix)
                if (!promote(file, main)) continue
                val restored = tryOpen(context, main, candidate)
                if (restored != null) {
                    Logger.w(TAG, "Данные восстановлены из копии ${file.name}")
                    recovered.value = stamp.toString()
                    return restored
                }
            }
        }
        return null
    }

    /** Ставит копию на место основной БД. Пустая основная БД откладывается, а не удаляется. */
    private fun promote(backup: File, main: File): Boolean = runCatching {
        val dir = main.parentFile ?: return false
        if (main.exists()) {
            main.renameTo(File(dir, "$NAME.empty-${System.currentTimeMillis()}"))
            sidecars(main).forEach { it.delete() }
        }
        if (!backup.renameTo(main)) return false
        // Журналы копии переезжают вместе с ней, иначе SQLite не увидит последние транзакции.
        sidecars(backup).forEach { f ->
            f.renameTo(File(dir, main.name + f.name.removePrefix(backup.name)))
        }
        true
    }.getOrDefault(false)

    private fun sidecars(file: File): List<File> =
        listOf("-journal", "-wal", "-shm").map { File(file.parentFile, file.name + it) }.filter { it.exists() }

    /** Есть ли в базе хоть одна запись. null — база не отвечает (считаем повреждённой). */
    private fun records(driver: SqlDriver): Boolean? = runCatching {
        val query = driver.executeQuery(
            null,
            "SELECT (SELECT count(*) FROM note) + (SELECT count(*) FROM task) + (SELECT count(*) FROM expense) + " +
                "(SELECT count(*) FROM reminder) + (SELECT count(*) FROM conversation_message)",
            { cursor ->
                val has = (cursor.next() as QueryResult.Value<Boolean>).value && (cursor.getLong(0) ?: 0L) > 0L
                QueryResult.Value(has)
            },
            0,
        )
        // Драйвер синхронный: результат всегда готов сразу.
        (query as QueryResult.Value<Boolean>).value
    }.getOrNull()

    private fun tryOpen(context: Context, file: File, passphrase: ByteArray): SqlDriver? {
        if (!file.exists()) return null
        return try {
            AndroidSqliteDriver(
                schema = NovaDatabase.Schema,
                context = context,
                name = file.name,
                factory = SupportOpenHelperFactory(passphrase),
            ).also { verify(it) }
        } catch (e: Exception) {
            Logger.w(TAG, "Не удалось открыть ${file.name}", e)
            null
        }
    }

    private fun open(context: Context, passphrase: ByteArray): SqlDriver =
        AndroidSqliteDriver(
            schema = NovaDatabase.Schema,
            context = context,
            name = NAME,
            factory = SupportOpenHelperFactory(passphrase),
        )

    private fun verify(driver: SqlDriver) {
        driver.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
            cursor.next()
            QueryResult.Value(Unit)
        }, 0)
    }
}
