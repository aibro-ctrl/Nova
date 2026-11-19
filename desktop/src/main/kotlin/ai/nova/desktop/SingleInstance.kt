package ai.nova.desktop

import ai.nova.core.util.Logger
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Нова запускается один раз: две копии писали бы в одну базу и дважды показывали напоминания.
 * Первая копия держит блокировку файла и слушает локальный порт; вторая (повторный клик по ярлыку)
 * просит первую показать окно и сразу завершается.
 */
class SingleInstance private constructor(private val channel: FileChannel, private val lock: FileLock, private val server: ServerSocket?) {

    fun release() {
        runCatching { server?.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val SHOW = "show"

        /**
         * null — Нова уже запущена (ей отправлена просьба показать окно), эту копию нужно закрыть.
         * [onShow] вызывается, когда кто-то ещё раз запустил программу.
         */
        fun acquire(dataDir: File, onShow: () -> Unit): SingleInstance? {
            dataDir.mkdirs()
            val portFile = File(dataDir, "nova.port")
            val channel = RandomAccessFile(File(dataDir, "nova.lock"), "rw").channel
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { channel.close() }
                signal(portFile)
                return null
            }
            val server = runCatching { ServerSocket(0, 5, InetAddress.getLoopbackAddress()) }
                .onFailure { Logger.w("App", "Порт для второго запуска не открылся", it) }.getOrNull()
            if (server != null) {
                portFile.writeText(server.localPort.toString())
                thread(name = "nova-instance", isDaemon = true) {
                    while (!server.isClosed) {
                        val s = runCatching { server.accept() }.getOrNull() ?: break
                        runCatching {
                            s.use {
                                it.soTimeout = 2000
                                val cmd = it.getInputStream().bufferedReader().readLine()
                                if (cmd == SHOW) onShow()
                            }
                        }
                    }
                }
            }
            return SingleInstance(channel, lock, server)
        }

        private fun signal(portFile: File) {
            val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.getOutputStream().write("$SHOW\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
    }
}
