package ai.nova.core.skills

import ai.nova.core.data.NovaJson
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Песня из открытого каталога: [previewUrl] — фрагмент, который Нова умеет проиграть сама. */
data class MusicTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val previewUrl: String,
    val artworkUrl: String? = null,
    val pageUrl: String? = null,
) {
    /** Как песня называется в ответе и в уведомлении плеера. */
    val label: String get() = if (artist.isBlank()) title else "$artist — $title"
}

/**
 * Музыкальные приложения, в которых Нова включает песню по названию.
 * [packages] — имена пакетов (у части приложений бывает несколько сборок), [webUrl] — запасной путь,
 * если приложения на телефоне нет: откроем его сайт с тем же поиском.
 */
enum class MusicApp(
    val title: String,
    val spoken: String,
    val packages: List<String>,
    val webUrl: (String) -> String,
    /**
     * Приложение не включает песню ни по клавише «играть», ни по запросу «включи по поиску»:
     * нужно открыть страницу поиска и нажать первую строку результата.
     */
    val tapsFirstResult: Boolean = false,
) {
    YANDEX("Яндекс Музыка", "яндекс музыке", listOf("ru.yandex.music"), { "https://music.yandex.ru/search?text=$it" }),
    SPOTIFY("Spotify", "спотифае", listOf("com.spotify.music"), { "https://open.spotify.com/search/$it" }),
    // Настоящий пакет «Звука» — com.zvooq.openplay (бывший Zvooq): по com.zvuk.* приложение не находится,
    // и Нова отвечала «на телефоне нет Звука».
    ZVUK(
        "Звук", "звуке",
        listOf("com.zvooq.openplay", "com.zvuk.zvukapp", "com.zvuk.android"),
        { "https://zvuk.com/search?query=$it" },
        tapsFirstResult = true,
    ),
    VK("VK Музыка", "вк музыке", listOf("com.vk.music", "com.vkontakte.android"), { "https://vk.com/audio?q=$it" }),
    YOUTUBE("YouTube Music", "ютуб музыке", listOf("com.google.android.apps.youtube.music"), { "https://music.youtube.com/search?q=$it" }),
    ;

    companion object {
        /** Порядок, в котором Нова предлагает приложения: сначала те, что реально включают песню по названию. */
        val PREFERRED: List<MusicApp> get() = listOf(YANDEX, SPOTIFY, ZVUK, VK, YOUTUBE)
    }
}

/**
 * Поиск песни по названию. Сначала iTunes Search — он отдаёт 30-секундные превью и хорошо знает
 * русскую музыку; если пусто, Deezer. Плееру отдаём прямую ссылку на файл, страницу — для «открыть целиком».
 */
class MusicSearch(private val http: HttpClient?) {

    suspend fun find(query: String): MusicTrack? {
        val q = query.trim()
        val client = http ?: return null
        if (q.isEmpty()) return null
        val term = "?media=music&entity=song&limit=8&term=${q.encodeURLParameter()}"
        // Витрина /ru: на части адресов общий /search отдаёт пустой каталог.
        return attempt { parseItunes(client.fetchText("https://itunes.apple.com/ru/search$term")) }
            ?: attempt { parseItunes(client.fetchText("https://itunes.apple.com/search$term")) }
            ?: attempt { parseDeezer(client.fetchText("https://api.deezer.com/search?limit=8&q=${q.encodeURLParameter()}")) }
    }

    private inline fun attempt(block: () -> MusicTrack?): MusicTrack? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Играем только защищённые ссылки: незашифрованный звук приложение не пропустит. */
        fun playable(url: String): Boolean = url.startsWith("https://")

        fun parseItunes(json: String): MusicTrack? {
            val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val results = root["results"] as? JsonArray ?: return null
            return results.asSequence().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                // Витрина отвечает и фильмами: у них тоже есть фрагмент, но это не песня.
                if (o.text("kind")?.let { it != "song" } == true) return@mapNotNull null
                if (o.text("wrapperType")?.let { it != "track" } == true) return@mapNotNull null
                val preview = o.text("previewUrl")?.trim()?.takeIf { playable(it) } ?: return@mapNotNull null
                val title = o.text("trackName")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                MusicTrack(
                    title = title,
                    artist = o.text("artistName")?.trim().orEmpty(),
                    album = o.text("collectionName")?.trim()?.takeIf { it.isNotEmpty() },
                    previewUrl = preview,
                    artworkUrl = o.text("artworkUrl100")?.trim()?.takeIf { it.isNotEmpty() },
                    pageUrl = o.text("trackViewUrl")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.firstOrNull()
        }

        fun parseDeezer(json: String): MusicTrack? {
            val root = runCatching { NovaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val data = root["data"] as? JsonArray ?: return null
            return data.asSequence().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                if ((o["readable"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "false") return@mapNotNull null
                val preview = o.text("preview")?.trim()?.takeIf { playable(it) } ?: return@mapNotNull null
                val title = o.text("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                MusicTrack(
                    title = title,
                    artist = (o["artist"] as? JsonObject)?.text("name")?.trim().orEmpty(),
                    album = (o["album"] as? JsonObject)?.text("title")?.trim()?.takeIf { it.isNotEmpty() },
                    previewUrl = preview,
                    artworkUrl = (o["album"] as? JsonObject)?.text("cover_medium")?.trim()?.takeIf { it.isNotEmpty() },
                    pageUrl = o.text("link")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.firstOrNull()
        }
    }
}
