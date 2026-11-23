package ai.nova.core.assistant

/**
 * Карты, в которых Нова строит маршрут. Выбор хранится в настройках; если не выбран —
 * открывается то приложение, которое человек назначил системным для ссылок `geo:`.
 */
enum class MapsApp(val id: String, val title: String, val pkg: String, val web: String) {
    GOOGLE("google", "Google Карты", "com.google.android.apps.maps", "https://www.google.com/maps/search/?api=1&query="),
    YANDEX("yandex", "Яндекс Карты", "ru.yandex.yandexmaps", "https://yandex.ru/maps/?text="),
    TWOGIS("2gis", "2ГИС", "ru.dublgis.dgismobile", "https://2gis.ru/search/");

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
