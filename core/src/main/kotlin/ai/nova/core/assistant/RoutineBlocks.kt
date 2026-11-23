package ai.nova.core.assistant

import ai.nova.core.model.Routine

/**
 * Функции Новы, из которых собирается сценарий: галочка в конструкторе = одна команда.
 *
 * [command] — обычная фраза, которую Нова и так понимает (та же, что человек сказал бы голосом),
 * поэтому конструктор не требует отдельного «внутреннего API» и любую галочку можно проверить руками.
 * Порядок списка — порядок блоков в конструкторе; в сценарий команды попадают в порядке выбора.
 */
object RoutineBlocks {

    data class Block(val id: String, val title: String, val hint: String, val command: String)

    val ALL: List<Block> = listOf(
        Block("greeting", "Приветствие", "Поздоровается по имени и назовёт время суток", "привет"),
        Block("weather", "Погода", "Погода на сегодня и подсказка, брать ли зонт", "какая погода"),
        Block("news", "Новости", "Короткая сводка новостей", "новости"),
        Block("agenda", "План на день", "Дела из календаря и задачи на сегодня", "что у меня на сегодня"),
        Block("tasks", "Задачи", "Список задач на сегодня", "какие задачи на сегодня"),
        Block("reminders", "Напоминания", "Что напомнить в ближайшее время", "мои напоминания"),
        Block("habits", "Привычки и здоровье", "Вода, лекарства, привычки: что уже отмечено", "мои привычки"),
        Block("birthdays", "Дни рождения", "Кого скоро поздравлять", "покажи все дни рождения"),
        Block("ideas", "Идеи", "Последние записанные идеи", "какие у меня идеи"),
        Block("notes", "Заметки", "Последние заметки", "мои заметки"),
        Block("expenses-today", "Траты за сегодня", "Сколько потрачено за день", "сколько я потратил сегодня"),
        Block("expenses-week", "Траты за неделю", "Сколько потрачено за неделю", "мои траты за неделю"),
        Block("currency", "Курс валют", "Курс доллара и евро", "курс доллара"),
        Block("dnd", "Не беспокоить", "Включит режим «не беспокоить»", "включи не беспокоить"),
        Block("music", "Музыка", "Включит музыку", "включи музыку"),
    )

    fun byId(id: String): Block? = ALL.firstOrNull { it.id == id }

    private fun normalize(s: String) = Routine.normalize(s)

    /** Какие блоки уже входят в сценарий: блок отмечен, если все его команды в списке. */
    fun selectedIds(commands: List<String>): Set<String> {
        val have = commands.map { normalize(it) }.toSet()
        return ALL.filter { normalize(it.command) in have }.map { it.id }.toSet()
    }

    /** Команды сценария, которые не входят ни в один блок: их конструктор показывает как «свои». */
    fun extras(commands: List<String>): List<String> {
        val blockCommands = ALL.map { normalize(it.command) }.toSet()
        return commands.filter { normalize(it) !in blockCommands }.distinctBy { normalize(it) }
    }

    /**
     * Итоговый список команд: сначала оставшиеся команды сценария (устоявшийся порядок), затем команды
     * новых галочек в порядке каталога, затем свои команды. Команда снятой галочки уходит,
     * а повторов нет — одна фраза = одна команда.
     */
    fun compose(original: List<String>, selected: Set<String>, extras: List<String>): List<String> {
        val extraNorms = extras.map { normalize(it) }.toSet()
        val own = original.filter { it.isNotBlank() }.filter { cmd ->
            val n = normalize(cmd)
            n in extraNorms || ALL.any { it.id in selected && normalize(it.command) == n }
        }
        val chosen = ALL.filter { it.id in selected }.map { it.command }
        val seen = HashSet<String>()
        return (own + chosen + extras.map { it.trim() }).filter { it.isNotBlank() }.filter { seen.add(normalize(it)) }
    }
}
