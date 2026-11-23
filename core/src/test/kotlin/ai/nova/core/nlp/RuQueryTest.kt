package ai.nova.core.nlp

import ai.nova.core.assistant.AssistantAction
import ai.nova.core.assistant.DeviceCommand
import ai.nova.core.assistant.LocalCommandParser
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuQueryTest {

    @Test fun dictionaryFormOfPlace() {
        assertEquals("вокзал", RuQuery.toSearchForm("вокзала"))
        assertEquals("рыбалка", RuQuery.toSearchForm("рыбалку"))
        assertEquals("площадь", RuQuery.toSearchForm("площади"))
        assertEquals("Москва", RuQuery.toSearchForm("Москву"))
        assertEquals("Казань", RuQuery.toSearchForm("Казани"))
        assertEquals("аптека", RuQuery.toSearchForm("аптеки"))
        assertEquals("станция", RuQuery.toSearchForm("станции"))
        assertEquals("школа", RuQuery.toSearchForm("школы"))
        assertEquals("сауна", RuQuery.toSearchForm("сауну"))
    }

    @Test fun keepsWordsThatNeedNoChange() {
        // Уже словарная форма, короткие слова и несклоняемые названия остаются как есть.
        assertEquals("аэропорт", RuQuery.toSearchForm("аэропорт"))
        assertEquals("дом", RuQuery.toSearchForm("дом"))
        assertEquals("кино", RuQuery.toSearchForm("кино"))
        assertEquals("Сочи", RuQuery.toSearchForm("Сочи"))
        assertEquals("Тбилиси", RuQuery.toSearchForm("Тбилиси"))
    }

    @Test fun keepsMultiWordPhrases() {
        // «рецепт пицца» хуже, чем «рецепт пиццы»: многословные запросы не трогаем.
        assertEquals("рецепт пиццы", RuQuery.toSearchForm("рецепт пиццы"))
        assertEquals("красной площади", RuQuery.toSearchForm("красной площади"))
        assertEquals("кафе в парке", RuQuery.toSearchForm("кафе в парке"))
    }

    @Test fun addsCityFromSettings() {
        assertEquals("вокзал, Казань", RuQuery.withCity("вокзал", "Казань"))
        assertEquals("вокзал", RuQuery.withCity("вокзал", ""))
        assertEquals("вокзал", RuQuery.withCity("вокзал", null))
        // Город уже назван или совпадает по основе («Казани, Баумана 5»).
        assertEquals("вокзал, Казань", RuQuery.withCity("вокзал, Казань", "Казань"))
        assertEquals("Казани, Баумана 5", RuQuery.withCity("Казани, Баумана 5", "Казань"))
    }

    @Test fun phrasesUseDictionaryForm() {
        val route = assertIs<DeviceCommand.Navigate>(parse("построй маршрут до вокзала"))
        assertEquals("вокзал", route.destination)
        val video = assertIs<DeviceCommand.Play>(parse("найди видео про рыбалку"))
        assertEquals("рыбалка", video.query)
        assertTrue(video.youtube)
    }

    private fun parse(phrase: String): DeviceCommand =
        (LocalCommandParser().parse(phrase, Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
            ?.actions?.singleOrNull() as AssistantAction.Device).command
}
