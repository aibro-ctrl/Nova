package ai.nova.core.assistant

/**
 * Где искать видео. Уточняем один раз — дальше Нова ищет там, где человек выбрал,
 * пока он сам не скажет «ищи видео в рутубе».
 */
enum class VideoPlatform(val id: String, val title: String) {
    YOUTUBE("youtube", "YouTube"),
    VK("vk", "VK Видео"),
    RUTUBE("rutube", "Rutube");

    companion object {
        fun fromId(id: String?): VideoPlatform? = entries.firstOrNull { it.id == id }
    }
}

object VideoChoice {
    const val QUESTION: String = "Где искать видео: YouTube, VK Видео или Rutube?"

    /** Ответ на уточнение: «ютуб», «youtube», «вк», «в контакте», «рутуб». */
    fun parse(text: String): VideoPlatform? {
        // Границы слов в регулярных выражениях Kotlin считаются по ASCII, поэтому русские слова ищем как есть.
        val t = " " + text.lowercase().replace('ё', 'е').replace(Regex("""[^a-zа-я\s]"""), " ") + " "
        fun has(vararg words: String) = words.any { w -> t.contains(" $w") }
        return when {
            has("youtube", "ютуб", "ютюб", "ю тубе", "ютубе") -> VideoPlatform.YOUTUBE
            has("rutube", "рутуб", "рутубе") -> VideoPlatform.RUTUBE
            has("vk", "вк", "вконтакте", "контакте") -> VideoPlatform.VK
            else -> null
        }
    }

    /** Явная команда искать именно здесь: «найди видео в рутубе про рыбалку». */
    fun forced(text: String): VideoPlatform? = parse(text)

    fun question(query: String): String = "Где искать «$query»: YouTube, VK Видео или Rutube? Скажите «ютуб», «вк» или «рутуб» — запомню выбор."
}
