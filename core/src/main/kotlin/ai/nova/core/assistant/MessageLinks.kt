package ai.nova.core.assistant

import ai.nova.core.nlp.Rx

/**
 * Ссылка, которую Нова оставила в истории разговора: `[Открыть снова](https://…)`.
 *
 * Хранится вместе с сообщением, чтобы найденное в интернете (поиск, маршрут, рецепт) можно было
 * открыть ещё раз в любой момент. В озвучку и в запрос к модели разметка не попадает.
 */
object MessageLinks {
    private val MARKUP = Rx.of("""\[([^\]]+)]\((https?://[^\s)]+)\)""")

    /** Адрес из разметки; null — ссылки в тексте нет. */
    fun url(text: String): String? = MARKUP.find(text)?.groupValues?.get(2)

    /** Текст без служебной разметки — для озвучки, модели и списков. */
    fun strip(text: String): String = MARKUP.replace(text, "").trim()
}
