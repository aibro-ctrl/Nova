package ai.nova.core.nlp

import ai.nova.core.voice.SpeechText
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    @Test fun listsAreReadInFull() {
        val text = "Задачи (8):\n" + (1..8).joinToString("\n") { "• задача $it" }
        val spoken = SpeechText.forSpeech(text)
        assertEquals("Задачи (8): задача 1. задача 2. задача 3. задача 4. задача 5. задача 6. задача 7. задача 8", spoken)
        assertEquals(false, spoken.contains("и ещё"))
    }

    @Test fun longTextIsSlicedBySentences() {
        val text = (1..40).joinToString(" ") { "Это предложение номер $it, оно довольно длинное." }
        val parts = SpeechText.chunks(text, 300)
        assertEquals(true, parts.size > 1)
        assertEquals(true, parts.all { it.length <= 300 })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test fun pronunciation() {
        fun say(t: String) = SpeechText.forSpeech(t)
        assertEquals("Будильник на 7 часов.", say("Будильник на 7:00."))
        assertEquals("Встреча в 10 30, потом в 9 ноль 5.", say("Встреча в 10:30, потом в 9:05."))
        assertEquals("Мама: +7 916 123 45 67.", say("Мама: +7 916 123-45-67."))
        assertEquals("Напишите в Телеграм или Вотсап.", say("Напишите в **Telegram** или WhatsApp."))
        assertEquals("Ветер 60 километров в час, до дачи 25 километров.", say("Ветер 60 км/ч, до дачи 25 км."))
        assertEquals("Это было в 2026 году, то есть недавно.", say("Это было в 2026 г., т.е. недавно."))
        assertEquals("Готово! Вот ссылка", say("Готово! 🎉\nВот https://example.com/x"))
        assertEquals("Шаги: 1. Первый. 2. Второй", say("## Шаги:\n1. Первый\n2. Второй"))
        assertEquals("Иду в сад. Потом домой.", say("Иду в сад. Потом домой."))
        assertEquals("Дом номер 5, улица Ленина.", say("Дом № 5, ул. Ленина."))
    }

    @Test fun audit22() {
        fun say(t: String) = SpeechText.forSpeech(t)
        assertEquals("Добавьте 200 грамм сахара.", say("Добавьте 200 г. сахара."))
        assertEquals("С 2020 года цены выросли.", say("С 2020 г. цены выросли."))
        assertEquals("Счёт 3:00 в пользу хозяев.", say("Счёт 3:00 в пользу хозяев."))
        assertEquals("2 умножить на 3 = 6, файл snake_case.", say("2*3 = 6, файл snake_case."))
        assertEquals("До дома около 5 километров.", say("До дома ~5 км."))
        assertEquals("Осталось 1,5 километра.", say("Осталось 1,5 км."))
        assertEquals("Осталось 2 часа. Потом отдых.", say("Осталось 2 ч. Потом отдых."))
        assertEquals("Ночью: минус 5 градусов", say("Ночью:\n- 5°"))
        assertEquals("Будильник поставлен", say("⏰ Будильник поставлен ➡️"))
        assertEquals("Важно: не забыть.", say("**Важно:** не забыть."))
    }

    @Test fun recognitionFixes() {
        assertEquals("потратил 5000 за дачу", ai.nova.core.voice.SpeechFixes.apply("потратил 5000 за дачу"))
        assertEquals("добавь задачу купить краску", ai.nova.core.voice.SpeechFixes.apply("добавь за дачу купить краску"))
        assertEquals("вы ключи взяли", ai.nova.core.voice.SpeechFixes.apply("вы ключи взяли"))
        assertEquals("выключи свет", ai.nova.core.voice.SpeechFixes.apply("вы ключи свет"))
        assertEquals("какая погода", ai.nova.core.voice.SpeechFixes.apply("что там по погоде"))
        fun fix(t: String) = ai.nova.core.voice.SpeechFixes.apply(t)
        assertEquals("какая погода завтра", fix("какая по года завтра"))
        assertEquals("разбуди меня в 7", fix("раз буди меня в 7"))
        assertEquals("отмени таймер", fix("от мени тай мер"))
        assertEquals("Нова, позвони маме", fix("Ноли, по звони маме"))
    }
}
