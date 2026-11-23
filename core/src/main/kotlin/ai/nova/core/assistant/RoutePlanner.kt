package ai.nova.core.assistant

import ai.nova.core.nlp.RuQuery
import ai.nova.core.skills.SavedPlace

/**
 * Куда вести маршрут по названию места.
 *
 * «Дом», «работа», «дача» есть только в сохранённых местах («запомни, я дома») — их ищем по координатам:
 * искать слово «дом» на карте бессмысленно. Остальное — обычный запрос плюс город из настроек.
 */
object RoutePlanner {

    /** Итог: запрос для карт и место, если нашли сохранённое. */
    data class Target(val query: String, val place: SavedPlace?)

    /** Места, которые существуют только как сохранённая зона. */
    val ZONE_PLACES = setOf("дом", "работа", "дача", "офис")

    /**
     * Запрос для карт. null — это «дом»/«работа», а место не сохранено: тогда лучше попросить
     * пользователя сказать «запомни, я дома», чем открыть карту со словом «дом».
     */
    fun target(destination: String, city: String?, saved: List<SavedPlace>): Target? {
        val place = saved.firstOrNull { RuQuery.samePlace(it.name, destination) }
        if (place != null) return Target("${place.lat},${place.lon}", place)
        if (destination.lowercase().trim() in ZONE_PLACES) return null
        return Target(RuQuery.withCity(destination, city), null)
    }
}
