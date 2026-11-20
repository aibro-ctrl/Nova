package ai.nova.app.voice

/**
 * Делит поток ответа на фразы, чтобы озвучка начиналась с первой законченной.
 * Фраза заканчивается на «.», «!», «?» или «…» с пробелом или концом текста; дробные числа и время не рвутся.
 */
object SpeechChunker {
    fun flush(buffer: StringBuilder, last: Boolean = false, emit: (String) -> Unit) {
        var start = 0
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c != '.' && c != '!' && c != '?' && c != '…') continue
            val isDecimal = c == '.' && i > 0 && buffer[i - 1].isDigit() && i + 1 < buffer.length && buffer[i + 1].isDigit()
            if (isDecimal) continue
            val end = i + 1
            if (end < buffer.length && !buffer[end].isWhitespace()) continue
            val sentence = buffer.substring(start, end).trim()
            start = end
            if (sentence.any(Char::isLetterOrDigit)) emit(sentence)
        }
        buffer.delete(0, start)
        while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        if (last) {
            val tail = buffer.toString().trim()
            buffer.setLength(0)
            if (tail.isNotEmpty()) emit(tail)
        }
    }
}
