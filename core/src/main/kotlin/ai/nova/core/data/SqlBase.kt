package ai.nova.core.data

import ai.nova.core.db.NovaDatabase
import ai.nova.core.sync.SyncableTable
import ai.nova.core.util.UpdateStamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Общая инфраструктура SQL-репозиториев: диспетчер ввода-вывода, метки времени, уведомления. */
abstract class SqlTableBase(
    protected val db: NovaDatabase,
    protected val stamp: UpdateStamp,
    private val bus: LocalChangeBus,
    protected val dispatcher: CoroutineDispatcher,
) : SyncableTable {
    protected suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }
    protected fun changed() = bus.notifyChanged(remoteTable)
    protected fun nowMs(): Long = stamp.next().toEpochMilli()
}

internal fun Boolean.toLong(): Long = if (this) 1L else 0L
