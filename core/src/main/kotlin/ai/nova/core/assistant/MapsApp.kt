package ai.nova.core.assistant

/**
 * Карты, в которых Нова строит маршрут. Выбор хранится в настройках; если не выбран —
 * открывается то приложение, которое человек назначил системным для ссылок `geo:`.
 */
enum class MapsApp(val id: String, val title: String, val pkg: String, val web: String) {
    GOOGLE("google", "Google Карты", "com.google.android.apps.maps", "https://www.google.com/maps/search/?api=1&query="),
    YANDEX("yandex", "Яндекс Карты", "ru.yandex.yandexmaps", "https://yandex.ru/maps/?text="),
    TWOGIS("2gis", "2ГИС", "ru.dublgis.dgismobile", "https://2gis.ru/search/");

    /**
     * Ссылка «построй маршрут»: приложение карт строит путь от текущего места до точки.
     *
     * Что проверено на телефоне:
     *  • Google Карты понимают и координаты, и адрес словом: `google.navigation:q=…` сразу включает навигацию;
     *  • 2ГИС по слову открывает список результатов, а маршрут строит только по координатам —
     *    поэтому без координат у него остаётся поиск;
     *  • у Яндекс Карт маршрут задаётся ссылкой с точками и типом поездки.
     * Без координат (место не нашли) маршрут не построить — открываем поиск, чтобы человек увидел список.
     */
    fun routeTo(lat: Double?, lon: Double?, text: String): String = when {
        lat != null && lon != null -> when (this) {
            GOOGLE -> "google.navigation:q=$lat,$lon"
            TWOGIS -> "google.navigation:q=$lat,$lon"
            YANDEX -> "https://yandex.ru/maps/?rtext=~$lat,$lon&rtt=auto"
        }
        // Google Карты строят маршрут и по названию — проверено: «навигация до аптеки» сразу включает путь.
        this == GOOGLE -> "google.navigation:q=" + search(text)
        this == TWOGIS -> "dgis://2gis.ru/search/" + search(text)
        else -> web + search(text)
    }

    /** Маршрут в браузере — если приложения карт на телефоне нет. */
    fun routeWeb(lat: Double?, lon: Double?, text: String): String = when (this) {
        GOOGLE -> if (lat != null && lon != null) "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving" else web + search(text)
        YANDEX -> if (lat != null && lon != null) "https://yandex.ru/maps/?rtext=~$lat,$lon&rtt=auto" else web + search(text)
        // В браузере 2ГИС маршрут ссылкой не задаётся: открываем карточку точки, там кнопка «Маршрут».
        TWOGIS -> web + search(if (lat != null && lon != null) "$lat,$lon" else text)
    }

    private fun search(text: String): String = java.net.URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

    companion object {
        fun fromId(id: String?): MapsApp? = entries.firstOrNull { it.id == id }

        /** «в 2ГИС», «в яндекс картах», «в гугл картах» — явный выбор в самой фразе. */
        fun parse(text: String): MapsApp? {
            val t = " " + text.lowercase().replace('ё', 'е').replace(Regex("""[^a-zа-я0-9\s]"""), " ") + " "
            fun has(vararg words: String) = words.any { w -> t.contains(" $w") }
            return when {
                has("2гис", "2 гис", "двагис", "дубльгис", "2gis") -> TWOGIS
                has("яндекс", "яндексе", "яндексом", "yandex", "яндекс-картах") -> YANDEX
                has("гугл", "гугле", "google", "гугл-картах", "гуглкартах") -> GOOGLE
                else -> null
            }
        }
    }
}
