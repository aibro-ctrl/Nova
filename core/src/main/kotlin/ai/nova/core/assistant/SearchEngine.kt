package ai.nova.core.assistant

/**
 * Чем искать в интернете, когда Нова открывает результаты в браузере.
 *
 * Android не даёт узнать, какой поисковик выбран в браузере по умолчанию, поэтому выбор
 * хранится в настройках Нова: иначе «найди в интернете…» открывало главную страницу браузера
 * без запроса. Ссылку собираем сами — она всегда открывает именно результаты.
 */
enum class SearchEngine(val id: String, val title: String, private val base: String) {
    YANDEX("yandex", "Яндекс", "https://ya.ru/search/?text="),
    GOOGLE("google", "Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=");

    /** Страница результатов по запросу. Запрос кодируется целиком — в нём бывают пробелы и кириллица. */
    fun searchUrl(query: String): String = base + java.net.URLEncoder.encode(query.trim(), "UTF-8")

    companion object {
        /** Яндекс по умолчанию: русские запросы он разбирает лучше и работает без сервисов Google. */
        val DEFAULT: SearchEngine = YANDEX

        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
