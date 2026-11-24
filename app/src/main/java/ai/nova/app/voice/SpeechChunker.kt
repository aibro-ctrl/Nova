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

    /**
     * Что из готового ответа [full] ещё не прозвучало, если поток [spoken] уже озвучен.
     *
     * Пока модель печатает, озвучка идёт по кускам, а готовый ответ перед показом чистят: убирают
     * служебную преамбулу, приветствие, обрыв по таймауту. Тогда готовый текст не начинается с
     * прозвучавшего, и сравнивать строки напрямую нельзя — иначе хвост ответа теряется.
     * Поэтому ищем в готовом тексте конец прозвучавшего (по словам) и озвучиваем остаток.
     * Если совпадений нет вовсе (прозвучала только преамбула), возвращаем весь ответ.
     */
    fun remainder(full: String, spoken: String): String {
        val answer = full.trim()
        if (answer.isEmpty()) return ""
        val said = spoken.trim()
        if (said.isEmpty()) return answer
        val answerWords = words(answer)
        val saidWords = words(said)
        if (answerWords.isEmpty()) return ""
        if (saidWords.isEmpty()) return answer
        for (k in minOf(saidWords.size, 12) downTo 1) {
            val tail = saidWords.takeLast(k).map(::word)
            for (i in 0..answerWords.size - k) {
                if ((0 until k).all { word(answerWords[i + it]) == tail[it] }) {
                    return answerWords.drop(i + k).joinToString(" ")
                }
            }
        }
        return answer
    }

    private fun words(text: String) = text.split(Regex("""\s+""")).filter { it.isNotBlank() }

    /** Слово без регистра, знаков и «ё»: «Привет!» и «привет» — одно и то же слово. */
    private fun word(text: String) = text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }
}
