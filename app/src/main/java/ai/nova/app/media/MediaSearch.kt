package ai.nova.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.service.media.MediaBrowserService
import android.media.session.MediaController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import ai.nova.core.util.Logger
import kotlin.coroutines.resume

/**
 * «Включи песню в чужом плеере» через медиа-службу приложения — так же, как это делают часы и Android Auto.
 *
 * Открыть приложение и нажать «играть» недостаточно: у «Звука» на странице поиска ещё нет
 * воспроизведения, и клавиша уходит в никуда. Служба же умеет `playFromSearch` — она сама находит
 * трек по названию и включает его.
 */
class MediaSearch(private val context: Context) {

    /** Служба плеера для `playFromSearch`: объявлена почти у всех музыкальных приложений. */
    fun service(pkg: String): ComponentName? = runCatching {
        val services = context.packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE),
            PackageManager.GET_RESOLVED_FILTER,
        )
        services.firstOrNull { it.serviceInfo.packageName == pkg }?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /**
     * Просит приложение включить трек. false — службы нет или она не ответила за [CONNECT_TIMEOUT_MS]:
     * тогда остаётся открыть приложение и дожать клавишей.
     */
    suspend fun play(pkg: String, query: String): Boolean {
        if (query.isBlank()) return false
        val service = service(pkg) ?: return false
        // MediaBrowser требует потока с Looper: подключаемся с главного, ждём ответа в корутине.
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var browser: MediaBrowser? = null
                    val callback = object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            val token = browser?.sessionToken
                            if (token == null) {
                                if (cont.isActive) cont.resume(false)
                                return
                            }
                            runCatching { MediaController(context, token).transportControls.playFromSearch(query, null) }
                                .onFailure { Logger.w(TAG, "плеер не принял поиск «$query»", it) }
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onConnectionFailed() {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    browser = MediaBrowser(context, service, callback, null)
                    cont.invokeOnCancellation { runCatching { browser?.disconnect() } }
                    runCatching { browser?.connect() }.onFailure {
                        Logger.w(TAG, "не подключилась к службе ${service.flattenToShortString()}", it)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } ?: false.also { Logger.w(TAG, "служба $pkg не ответила за $CONNECT_TIMEOUT_MS мс") }
        }
    }

    private companion object {
        const val TAG = "MediaSearch"
        const val CONNECT_TIMEOUT_MS = 4_000L
    }
}
