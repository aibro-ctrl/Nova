package ai.nova.core.shopping

/**
 * Карточки товаров внутри сообщения чата.
 *
 * История переписки хранится обычным текстом, поэтому карточки кодируются служебными строками в конце
 * ответа: так они остаются в истории и через день, а ссылка на товар по-прежнему открывается одним касанием.
 * Человеку эти строки не показываются — их вырезает [decode].
 */
object ShoppingCards {

    /** Начало служебной строки. В обычной речи такой последовательности не бывает. */
    private const val PREFIX = "#товар#"

    /** Разделитель полей: ломаная черта не встречается в названиях товаров. */
    private const val SEP = " ¦ "

    fun append(text: String, products: List<ShoppingProduct>): String {
        if (products.isEmpty()) return text
        return (text + "\n" + encode(products)).trim()
    }

    fun encode(products: List<ShoppingProduct>): String = products.joinToString("\n") { p ->
        listOf(
            PREFIX,
            p.marketplace.id,
            p.id,
            p.price?.toString().orEmpty(),
            p.rating?.toString().orEmpty(),
            p.reviews?.toString().orEmpty(),
            // Название и адрес кодируются: в них бывают и разделитель, и перевод строки.
            java.net.URLEncoder.encode(p.title, "UTF-8"),
            java.net.URLEncoder.encode(p.url, "UTF-8"),
            // Фотография — последней: записи без неё (из старых версий) читаются как раньше.
            p.imageUrl?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty(),
        ).joinToString(SEP)
    }

    /** Текст без служебных строк и сами карточки. */
    fun decode(text: String): Decoded {
        if (!text.contains(PREFIX)) return Decoded(text, emptyList())
        val clean = StringBuilder()
        val products = ArrayList<ShoppingProduct>()
        for (line in text.lineSequence()) {
            if (!line.trimStart().startsWith(PREFIX)) {
                if (clean.isNotEmpty()) clean.append('\n')
                clean.append(line)
                continue
            }
            productOf(line)?.let { products += it }
        }
        return Decoded(clean.toString().trim(), products)
    }

    private fun productOf(line: String): ShoppingProduct? {
        val parts = line.trim().split(SEP)
        if (parts.size < 8) return null
        val marketplace = Marketplace.entries.firstOrNull { it.id == parts[1] } ?: return null
        val title = unescape(parts[6].trim()).replace('\n', ' ')
        val url = unescape(parts[7].trim())
        if (title.isEmpty() || url.isEmpty()) return null
        return ShoppingProduct(
            id = parts[2],
            marketplace = marketplace,
            title = title,
            price = parts[3].toIntOrNull(),
            rating = parts[4].toDoubleOrNull(),
            reviews = parts[5].toIntOrNull(),
            url = url,
            imageUrl = parts.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
        )
    }

    private fun unescape(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    data class Decoded(val text: String, val products: List<ShoppingProduct>)
}
