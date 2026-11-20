package ai.nova.core.nlp

import ai.nova.core.voice.SpeechText
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    @Test fun listsAreReadInFull() {
        val text = "Задачи (8):\n" + (1..8).joinToString("\n") { "• задача $it" }
        val spoken = SpeechText.forSpeech(text)
        assertEquals("Задачи (восемь): задача одна. задача две. задача три. задача четыре. задача пять. " +
            "задача шесть. задача семь. задача восемь", spoken)
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
        assertEquals("Будильник на семь часов.", say("Будильник на 7:00."))
        assertEquals("Встреча в десять тридцать, потом в девять ноль пять.", say("Встреча в 10:30, потом в 9:05."))
        assertEquals("Мама: плюс семь девять один шесть один два три четыре пять шесть семь.", say("Мама: +7 916 123-45-67."))
        assertEquals("Напишите в Телеграм или Вотсап.", say("Напишите в **Telegram** или WhatsApp."))
        assertEquals("Ветер шестьдесят километров в час, до дачи двадцать пять километров.", say("Ветер 60 км/ч, до дачи 25 км."))
        assertEquals("Это было в две тысячи двадцать шестом году, то есть недавно.", say("Это было в 2026 г., т.е. недавно."))
        assertEquals("Готово! Вот ссылка", say("Готово! 🎉\nВот https://example.com/x"))
        assertEquals("Шаги: Первый. Второй", say("## Шаги:\n1. Первый\n2. Второй"))
        assertEquals("Иду в сад. Потом домой.", say("Иду в сад. Потом домой."))
        assertEquals("Дом номер пять, улица Ленина.", say("Дом № 5, ул. Ленина."))
    }

    @Test fun audit22() {
        fun say(t: String) = SpeechText.forSpeech(t)
        assertEquals("Добавьте двести грамм сахара.", say("Добавьте 200 г. сахара."))
        assertEquals("С две тысячи двадцатого года цены выросли.", say("С 2020 г. цены выросли."))
        assertEquals("Счёт 3:00 в пользу хозяев.", say("Счёт 3:00 в пользу хозяев."))
        assertEquals("Счёт 3:0 в пользу хозяев.", say("Счёт 3:0 в пользу хозяев."))
        assertEquals("Два умножить на три = шесть, файл snake_case.", say("2*3 = 6, файл snake_case."))
        assertEquals("До дома около пяти километров.", say("До дома ~5 км."))
        assertEquals("Осталось одна целая и пять десятых километра.", say("Осталось 1,5 км."))
        assertEquals("Осталось два часа. Потом отдых.", say("Осталось 2 ч. Потом отдых."))
        assertEquals("Ночью: минус пять градусов", say("Ночью:\n- 5°"))
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
