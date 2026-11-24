package ai.nova.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Миниатюры товаров к карточкам в чате.
 *
 * Своими руками, без библиотек: фотографии мелкие, и тянуть ради 56 dp целый загрузчик изображений
 * приложению не нужно. Кэш живёт в памяти — после выхода из чата картинки не нужны и место на
 * телефоне не занимают.
 */
object ProductImages {

    private var client: HttpClient? = null

    /** Ссылка → уменьшенное изображение (null — не открылась, второй раз не пытаемся). */
    private val cache = object : LinkedHashMap<String, Bitmap?>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) = size > 48
    }

    /** Что уже грузится: одна и та же картинка не должна качаться дважды. */
    private val loading = HashSet<String>()

    private const val TAG = "Images"
    private const val MAX_SIDE = 180
    private const val WAIT_MS = 8_000L

    fun init(client: HttpClient) {
        this.client = client
    }

    fun cached(url: String): Bitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): Bitmap? {
        synchronized(cache) { if (url in cache) return cache[url] }
        val mine = synchronized(cache) { loading.add(url) }
        if (!mine) {
            // Параллельный запрос уже идёт — ждём его результат вместо второй загрузки.
            var waited = 0L
            while (waited < WAIT_MS) {
                delay(100)
                waited += 100
                synchronized(cache) { if (url in cache) return cache[url] }
            }
            return null
        }
        val bitmap = try {
            fetch(url)
        } finally {
            synchronized(cache) { loading.remove(url) }
        }
        synchronized(cache) { cache[url] = bitmap }
        return bitmap
    }

    private suspend fun fetch(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val http = client ?: return@withContext null
        runCatching {
            val bytes = http.get(url).bodyAsBytes()
            // Сначала размеры, потом чтение с уменьшением: полный кадр в память не тянем.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var scale = 1
            while (bounds.outWidth / (scale * 2) >= MAX_SIDE && bounds.outHeight / (scale * 2) >= MAX_SIDE) scale *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (decoded == null) ai.nova.core.util.Logger.w(TAG, "не разобрала картинку: $url")
            decoded
        }.onFailure { ai.nova.core.util.Logger.w(TAG, "картинка не загрузилась: $url", it) }.getOrNull()
    }
}

/** Фотография товара: уменьшенная, со скруглением и заглушкой, пока грузится. */
@Composable
fun ProductThumb(url: String?, size: Int = 56, contentDescription: String? = null) {
    val shape = remember { RoundedCornerShape(10.dp) }
    Box(Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNullOrBlank()) return@Box
        var bitmap by remember(url) { mutableStateOf(ProductImages.cached(url)) }
        LaunchedEffect(url) {
            if (bitmap == null) bitmap = ProductImages.load(url)
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
