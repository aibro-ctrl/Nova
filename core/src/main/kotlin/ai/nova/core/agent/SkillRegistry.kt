package ai.nova.core.agent

import ai.nova.core.nlp.RuTokenizer

/** Кто и что может увидеть/изменить: то же деление, что у экрана блокировки. */
enum class SkillPrivacy { PUBLIC, VIEW, CREATE, PRIVATE, DEVICE }

/**
 * Манифест навыка: как называется, что делает, по каким фразам включается.
 * Единая точка правды для разбора составных запросов, подсказок и диагностики.
 */
data class SkillManifest(
    val id: String,
    val title: String,
    val description: String,
    val triggers: List<Regex>,
    val examples: List<String> = emptyList(),
    val privacy: SkillPrivacy = SkillPrivacy.PUBLIC,
    /** Меняет данные (запись, покупки, напоминание) — для идемпотентности и подтверждений. */
    val mutates: Boolean = false,
    /** Навык находит сущность ([AgentEntity]), на которую можно сослаться «его/её/эту». */
    val producesEntity: String? = null,
)

/** Навык и вес совпадения: длиннее совпавший фрагмент — увереннее. */
data class SkillMatch(val manifest: SkillManifest, val score: Int)

/**
 * Реестр навыков Нова. Манифесты описывают уже существующие возможности:
 * сами обработчики остаются в [ai.nova.core.skills.Skills] и движке.
 */
class SkillRegistry(manifests: List<SkillManifest> = defaults()) {
    private val all: List<SkillManifest> = manifests

    val manifests: List<SkillManifest> get() = all

    fun byId(id: String): SkillManifest? = all.firstOrNull { it.id == id }

    fun byEntity(kind: String): SkillManifest? = all.firstOrNull { it.producesEntity == kind }

    /** Совпадения по убыванию уверенности. */
    fun match(text: String): List<SkillMatch> {
        val n = RuTokenizer.normalize(text)
        if (n.isBlank()) return emptyList()
        return all.mapNotNull { m ->
            val best = m.triggers.maxOfOrNull { r -> r.find(n)?.value?.length ?: 0 } ?: 0
            if (best >= MIN_SCORE) SkillMatch(m, best) else null
        }.sortedByDescending { it.score }
    }

    /** Самый уверенный навык или null. */
    fun best(text: String): SkillManifest? = match(text).firstOrNull()?.manifest

    /** Подсказка для справки: «Погода — скажите „какая погода“». */
    fun examples(): List<String> = all.flatMap { it.examples }.distinct()

    companion object {
        /** Идентификатор навыка «рецепт блюда» — по нему движок приводит фразу к вопросу о готовке. */
        const val RECIPE = "cooking.recipe"

        /** Короче 3 символов совпадение («и», «на») ничего не значит. */
        private const val MIN_SCORE = 3

        private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

        fun defaults(): List<SkillManifest> = listOf(
            SkillManifest(
                id = "cooking.recipe",
                title = "Рецепт блюда",
                description = "Как приготовить блюдо: ингредиенты, шаги, пересчёт порций.",
                triggers = listOf(
                    rx("""(?:как\s+)?(?:приготов\p{L}*|сготов\p{L}*|сварить|свари|испеч\p{L}*|пожарить|запеч\p{L}*|сделать|сделай)"""),
                    rx("""рецепт\p{L}*"""),
                    rx("""из\s+чего\s+(?:готов\p{L}*|сделать|приготов\p{L}*)"""),
                    rx("""что\s+(?:нужно|надо)\s+для"""),
                ),
                examples = listOf("как приготовить плов", "из чего делают тесто на хлеб"),
                producesEntity = AgentEntity.RECIPE,
            ),
            SkillManifest(
                id = "cooking.steps",
                title = "Пошаговая готовка",
                description = "Ведёт по шагам рецепта: дальше, повтори, назад, таймер.",
                triggers = listOf(rx("""пошагово|по\s+шагам|следующ\p{L}*\s+шаг|шаг\s+\d|помоги\s+приготовить""")),
                examples = listOf("помоги приготовить хлеб"),
            ),
            SkillManifest(
                id = "shopping.add",
                title = "Список покупок",
                description = "Добавляет продукты и вещи в список покупок.",
                triggers = listOf(
                    rx("""(?:добав\p{L}*|запиш\p{L}*|внеси|купить|куплю)\s+(?:.*\s)?(?:в\s+)?(?:покупк\p{L}*|список\s+покупок|продукт\p{L}*)"""),
                    rx("""список\s+покупок|в\s+магазин"""),
                ),
                examples = listOf("добавь в покупки молоко и хлеб"),
                privacy = SkillPrivacy.CREATE,
                mutates = true,
            ),
            SkillManifest(
                id = "note.save",
                title = "Заметка",
                description = "Сохраняет текст заметкой.",
                triggers = listOf(
                    rx("""(?:запиш\p{L}*|сохрани|созда\p{L}*|напиши|занеси)\s+(?:.*\s)?(?:заметк\p{L}*|идею|в\s+заметки|идеи)"""),
                    rx("""заметку|в\s+заметки"""),
                ),
                examples = listOf("запиши заметку про встречу"),
                privacy = SkillPrivacy.CREATE,
                mutates = true,
            ),
            SkillManifest(
                id = "note.find",
                title = "Поиск в записях",
                description = "Ищет заметки, идеи и записи по смыслу.",
                triggers = listOf(rx("""найди\s+(?:мою\s+)?заметк\p{L}*|поищи\s+(?:в\s+)?заметк\p{L}*|что\s+я\s+писал\p{L}*""")),
                examples = listOf("найди заметку про ремонт"),
            ),
            SkillManifest(
                id = "expense.add",
                title = "Расход или доход",
                description = "Записывает трату или доход, уточняет категорию.",
                triggers = listOf(
                    rx("""потратил\p{L}*|потрачен\p{L}*|израсходовал\p{L}*|заплатил\p{L}*|получил\p{L}*\s+(?:зарплат|доход|аванс)|доход"""),
                    rx("""(?:купил\p{L}*|купила)\s+.*\d"""),
                ),
                examples = listOf("потратила 500 на продукты"),
                privacy = SkillPrivacy.PRIVATE,
                mutates = true,
            ),
            SkillManifest(
                id = "expense.report",
                title = "Статистика трат",
                description = "Сколько потрачено за день, неделю, месяц, по категориям.",
                triggers = listOf(
                    rx("""сколько\s+(?:я\s+)?потратил\p{L}*"""),
                    rx("""(?:траты|расход\w*)\s+(?:за|на|в)\s"""),
                    rx("""статистик\p{L}*\s+(?:расход\p{L}*|трат\p{L}*)"""),
                ),
                examples = listOf("сколько я потратила за неделю"),
                privacy = SkillPrivacy.PRIVATE,
            ),
            SkillManifest(
                id = "music.play",
                title = "Музыка",
                description = "Находит и включает песню во встроенном плеере или в музыкальном сервисе.",
                triggers = listOf(
                    rx("""включи\s+(?:.*\s)?(?:музык\p{L}*|песн\p{L}*|трек|radio|радио)"""),
                    rx("""поставь\s+(?:.*\s)?(?:песн\p{L}*|трек|музык\p{L}*)"""),
                    rx("""найди\s+(?:.*\s)?песн\p{L}*"""),
                ),
                examples = listOf("включи песню Кукушка"),
                privacy = SkillPrivacy.DEVICE,
                producesEntity = AgentEntity.SONG,
            ),
            SkillManifest(
                id = "music.text",
                title = "Текст и аккорды песни",
                description = "Показывает текст песни или аккорды.",
                triggers = listOf(rx("""(?:текст|слова|аккорд\p{L}*)\s+(?:.*\s)?песн\p{L}*|(?:аккорд\p{L}*|текст\s+песни)""")),
                examples = listOf("найди текст песни Кукушка"),
            ),
            SkillManifest(
                id = "music.like",
                title = "Лайк песни",
                description = "Отмечает песню понравившейся.",
                triggers = listOf(rx("""лайк\p{L}*|в\s+избранное|понравилась\s+песн\p{L}*""")),
                examples = listOf("поставь лайк этой песне"),
                privacy = SkillPrivacy.CREATE,
                mutates = true,
            ),
            SkillManifest(
                id = "wiki.answer",
                title = "Справка",
                description = "Рассказывает, что такое предмет или кто такой человек.",
                triggers = listOf(
                    rx("""что\s+такое|кто\s+так\p{L}*|расскажи\s+(?:про|о|об)\s|найди\s+статью"""),
                ),
                examples = listOf("что такое борщ", "кто такой Циолковский"),
                producesEntity = AgentEntity.ARTICLE,
            ),
            SkillManifest(
                id = "weather",
                title = "Погода",
                description = "Погода сейчас, днём, на неделю; в своём городе и в другом.",
                triggers = listOf(rx("""погод\p{L}*|дожд\p{L}*|снег\p{L}*|температур\p{L}*|ветер|на\s+улице""")),
                examples = listOf("какая погода", "будет ли дождь завтра"),
            ),
            SkillManifest(
                id = "rates",
                title = "Курсы валют",
                description = "Курсы валют и криптовалют.",
                triggers = listOf(rx("""курс\p{L}*\s|доллар|евро|юан\p{L}*|биткоин|валют\p{L}*""")),
                examples = listOf("какой курс доллара"),
            ),
            SkillManifest(
                id = "news",
                title = "Новости",
                description = "Главные новости и новости по теме.",
                triggers = listOf(rx("""новост\p{L}*|что\s+нового|что\s+происходит""")),
                examples = listOf("какие новости"),
            ),
            SkillManifest(
                id = "reminder",
                title = "Напоминание",
                description = "Напоминает о деле в нужное время или в месте.",
                triggers = listOf(rx("""напомни|напоминани\p{L}*""")),
                examples = listOf("напомни позвонить маме в 18:00"),
                privacy = SkillPrivacy.CREATE,
                mutates = true,
            ),
            SkillManifest(
                id = "task",
                title = "Задача",
                description = "Добавляет и показывает задачи.",
                triggers = listOf(rx("""задач\p{L}*|добавь\s+(?:в\s+)?дела|что\s+мне\s+сделать""")),
                examples = listOf("добавь задачу позвонить в банк"),
                privacy = SkillPrivacy.CREATE,
                mutates = true,
            ),
            SkillManifest(
                id = "plan.day",
                title = "Планы на день",
                description = "Собирает планы, встречи и напоминания на день.",
                triggers = listOf(rx("""планы\s+на|что\s+у\s+меня\s+(?:сегодня|завтра)|расписани\p{L}*|на\s+сегодня""")),
                examples = listOf("мои планы на сегодня"),
                privacy = SkillPrivacy.PRIVATE,
            ),
            SkillManifest(
                id = "timer",
                title = "Таймер",
                description = "Ставит таймер и обратный отсчёт.",
                triggers = listOf(rx("""таймер|обратный\s+отсч[её]т""")),
                examples = listOf("таймер на 10 минут"),
                privacy = SkillPrivacy.DEVICE,
            ),
            SkillManifest(
                id = "game",
                title = "Игры",
                description = "Играет в города, числа, загадки.",
                triggers = listOf(rx("""сыгра\p{L}*|поигра\p{L}*|загадк\p{L}*|викторин\p{L}*""")),
                examples = listOf("сыграем в города"),
            ),
            SkillManifest(
                id = "tale",
                title = "Сказка",
                description = "Рассказывает сказку или историю.",
                triggers = listOf(rx("""сказк\p{L}*|истори\p{L}*\s+на\s+ночь""")),
                examples = listOf("расскажи сказку"),
            ),
            SkillManifest(
                id = "translate",
                title = "Перевод",
                description = "Переводит фразу на другой язык.",
                triggers = listOf(rx("""переведи|как\s+будет\s+по|перевод""")),
                examples = listOf("переведи на английский доброе утро"),
            ),
        )
    }
}
