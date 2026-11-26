package ai.nova.core.skills

import ai.nova.core.assistant.DevicePhrases
import ai.nova.core.finance.FinanceAsk
import ai.nova.core.nlp.RuNumbers
import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.nlp.Rx
import ai.nova.core.shopping.ShoppingCategory
import ai.nova.core.shopping.ShoppingIntent
import ai.nova.core.shopping.ShoppingQuery
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Разобранная команда навыка. */
sealed interface SkillCommand {
    data class Weather(val query: WeatherQuery) : SkillCommand
    data class Rates(val query: RatesQuery) : SkillCommand
    /**
     * Новости: [topic] — рубрика, названная человеком; [about] — тема своими словами
     * («видеоигры», «недвижимость», «пк»), по ней ищем отдельно и не подменяем выбором из настроек.
     */
    data class News(val topic: NewsTopic? = null, val about: String? = null) : SkillCommand
    /** Подробности: [index] — номер в последней ленте, [about] — слова из заголовка. */
    data class NewsDetails(val index: Int? = null, val about: String? = null) : SkillCommand
    data class Fact(val query: String) : SkillCommand
    /** «Найди рецепт борща и запиши в заметки»: [save] — положить найденное в заметки, [recipe] — раздел о приготовлении. */
    data class FindArticle(val query: String, val save: Boolean = false, val recipe: Boolean = false) : SkillCommand
    data class ReadMessages(val from: String?) : SkillCommand
    /** [explicitTo] — адресат назван через двоеточие («ответь маме: …»): если его нет, не отвечаем другому. */
    data class ReplyMessage(val to: String?, val text: String, val raw: String, val explicitTo: Boolean = false) : SkillCommand
    data class Screen(val mode: ScreenMode) : SkillCommand
    data class ContactNumber(val name: String) : SkillCommand
    data class Calendar(val from: LocalDate, val to: LocalDate, val nextOnly: Boolean = false) : SkillCommand
    data class RadioPlay(val query: String?) : SkillCommand
    data object RadioStop : SkillCommand
    data object RadioNext : SkillCommand
    /** «Включи песню X» — [app] назван явно («в Яндексе», «в спотифае»), иначе играет встроенный плеер Нова. */
    data class MusicPlay(val query: String?, val app: MusicApp? = null) : SkillCommand
    /** «Найди текст песни X», «аккорды к песне X» — [chords] вместо текста ищем аккорды. */
    data class MusicText(val query: String?, val chords: Boolean = false) : SkillCommand
    /** «Поставь лайк на песню X» — лайк ставится в приложении, Нова только открывает песню. */
    data class MusicLike(val query: String?) : SkillCommand
    data object FindPhone : SkillCommand
    /** «Где я?» — адрес по геолокации до улицы и дома. */
    data object WhereAmI : SkillCommand
    data object TimersLeft : SkillCommand
    data class TimersCancel(val label: String?) : SkillCommand
    /** «Продли таймер на 2 минуты»: [seconds] — на сколько продлить. */
    data class TimersExtend(val seconds: Int) : SkillCommand
    data class SavePlace(val name: String) : SkillCommand
    data class PlaceRemind(val text: String, val place: String, val onLeave: Boolean) : SkillCommand
    data object ListPlaces : SkillCommand
    data class SetName(val name: String) : SkillCommand
    data object AskName : SkillCommand
    data class SetCity(val city: String) : SkillCommand
    data class StartGame(val game: Game?) : SkillCommand
    data class Tale(val request: Tales.Request) : SkillCommand
    /** Праздники, именины, приметы — встроенный календарь. */
    data class Almanac(val kind: AlmanacKind, val date: LocalDate, val name: String? = null) : SkillCommand
    /** «Интересный факт», «факт дня». */
    data class FactOfDay(val ofDay: Boolean) : SkillCommand
    /** «Поздравь Машу с днём рождения» — готовое поздравление и сообщение ей. */
    data class Greet(val person: String, val occasion: String) : SkillCommand
    /** «Сколько можно тратить сегодня?», «хватит ли до зарплаты?» — по сводке Финансового помощника. */
    data class Finance(val ask: FinanceAsk, val subject: String? = null) : SkillCommand
    /** Покупка на маркетплейсе: «хочу кеды puma до 10 тысяч». */
    data class Shopping(val query: ShoppingQuery) : SkillCommand
    /** «Положи второй в корзину», «убери из корзины» — без оформления и оплаты. */
    data class ShoppingCart(val text: String) : SkillCommand
    /** «Что ты помнишь о покупках», «забудь размер обуви». */
    data class ShoppingMemory(val forget: String?) : SkillCommand
}

enum class AlmanacKind { HOLIDAY, NEXT_HOLIDAY, WHEN_HOLIDAY, NAME_DAY, NAME_DAY_OF, OMEN }

enum class ScreenMode { READ, SUMMARY, TRANSLATE }

/** Разбор фраз новых навыков. Всё офлайн; сеть нужна уже для ответа. */
object SkillPhrases {
    private fun rx(p: String) = Rx.of(p)

    fun norm(text: String): String = RuTokenizer.normalize(text).trim().trimEnd('.', '!', '?', ',').replace(Regex("""\s+"""), " ")

    /** Фраза — это запись/напоминание/задача, а не вопрос («напомни взять зонт»). */
    private val RECORD_VERB = rx("""^(?:напомни|запиши|записать|добавь|создай|поставь задачу|поставь напоминание|заметка|заметку|купи|список|сохрани заметку|потратил|потратила|заплатил|заплатила)\b""")

    /** [money] = false — без вопросов о деньгах: помощник на них не ответил, пусть фразу возьмёт другой навык. */
    fun parse(text: String, today: LocalDate, assistantName: String = "Нова", money: Boolean = true): SkillCommand? {
        val t = norm(text)
        if (t.isEmpty()) return null
        profile(t, text)?.let { return it }
        places(t, text)?.let { return it }
        shopping(t, text)?.let { return it }
        find(t)?.let { return it }
        // «Добавь 3 минуты к таймеру» — это продление, а не запись; проверяем до записи.
        if (rx("""^добавь\b.*\bтаймер""").containsMatchIn(t)) timersExtend(t)?.let { return it }
        if (RECORD_VERB.containsMatchIn(t)) return null
        if (money) finance(t)?.let { return it }
        // Явный поиск в интернете — это команда телефону, а не вопрос навыку.
        if (rx("""^(?:найди|поищи|посмотри)\s+в\s+(?:интернете|гугле|яндексе|сети)|^(?:загугли|погугли)\b""").containsMatchIn(t)) return null
        almanac(t, today)?.let { return it }
        greet(t, text)?.let { return it }
        weather(t, text, today)?.let { return SkillCommand.Weather(it) }
        rates(t)?.let { return SkillCommand.Rates(it) }
        news(t)?.let { return it }
        messages(t, text)?.let { return it }
        screen(t)?.let { return SkillCommand.Screen(it) }
        contact(t)?.let { return SkillCommand.ContactNumber(it) }
        calendar(t, today)?.let { return it }
        radio(t)?.let { return it }
        music(t)?.let { return it }
        if (isFindPhone(t)) return SkillCommand.FindPhone
        if (isWhereAmI(t)) return SkillCommand.WhereAmI
        timers(t)?.let { return it }
        Game.parseStart(t)?.let { return SkillCommand.StartGame(it) }
        if (Game.isWhatToPlay(t)) return SkillCommand.StartGame(null)
        Tales.parse(t)?.let { return SkillCommand.Tale(it) }
        factOfDay(t)?.let { return it }
        fact(t, assistantName)?.let { return SkillCommand.Fact(it) }
        return null
    }

    // ------------------------------------------------------------------ Деньги (Финансовый помощник)

    /** «Сколько осталось на таймере», «сколько на часах» — не про деньги. */
    private const val NOT_MONEY = "таймер|будильник|часах|часы|батаре|заряд|телефон|улице|дворе|градуснике|термометре"

    private const val TODAY_WORDS = """(?:\s+(?:сегодня|на сегодня|за сегодня|в день|на день|сегодня ещё|сегодня еще))?"""

    /**
     * Вопросы, на которые отвечает сводка помощника. «Сколько я потратил…» сюда не входит —
     * это отчёт по тратам самой Нова. Если сводки нет, навык промолчит, и фразу разберёт Нова.
     */
    fun finance(t: String): SkillCommand.Finance? {
        val q = t.removePrefix("а ").removePrefix("скажи ").removePrefix("подскажи ").trim()
        if (rx("""^сколько\s+(?:мне\s+|я\s+)?(?:ещё\s+|еще\s+)?(?:можно|могу|осталось|остается|остаётся)\s+(?:по)?(?:тратить|потратить)$TODAY_WORDS$|^сколько\s+(?:мне\s+)?(?:ещё\s+|еще\s+)?осталось\s+на\s+(?:сегодня|день)$|^(?:можно|могу)\s+ли\s+(?:я\s+)?(?:ещё\s+|еще\s+)?(?:сегодня\s+)?(?:по)?тратить(?:\s+сегодня)?$|^(?:какой\s+)?(?:мой\s+|у меня\s+)?(?:дневной\s+)?лимит\s+на\s+(?:сегодня|день)$|^(?:мой\s+)?дневной\s+лимит$""").containsMatchIn(q)) {
            return SkillCommand.Finance(FinanceAsk.TODAY)
        }
        if (rx("""(?:хватит|хватает|доживу|дотяну)\s+(?:ли\s+)?(?:мне\s+|я\s+)?(?:денег\s+)?до\s+(?:зарплаты|зп|получки|аванса)|^сколько\s+(?:дней\s+)?(?:осталось\s+)?до\s+(?:зарплаты|зп|получки|аванса)$|^когда\s+(?:у меня\s+)?(?:будет\s+|следующая\s+)?(?:зарплата|зп|получка|аванс)$""").containsMatchIn(q)) {
            return SkillCommand.Finance(FinanceAsk.PAYDAY)
        }
        if (rx("""^(?:как\s+)?(?:у меня\s+)?(?:дела\s+)?(?:с|со)\s+(?:деньгами|финансами|бюджетом)$|^как\s+(?:там\s+)?(?:мои\s+)?(?:деньги|финансы|бюджет)$|^(?:финансовая\s+сводка|сводка\s+по\s+(?:деньгам|финансам|бюджету))$""").containsMatchIn(q)) {
            return SkillCommand.Finance(FinanceAsk.OVERVIEW)
        }
        if (rx("""^сколько\s+(?:у меня\s+)?(?:сейчас\s+)?(?:всего\s+)?денег(?:\s+(?:у меня|всего|сейчас|на счетах|на всех счетах|на картах|осталось))*$|^(?:какой\s+)?(?:у меня\s+)?(?:сейчас\s+)?(?:мой\s+)?(?:общий\s+)?(?:баланс|остаток\s+на\s+счетах)$|^сколько\s+(?:у меня\s+)?(?:всего\s+)?на\s+(?:счетах|всех счетах|картах)$""").containsMatchIn(q)) {
            return SkillCommand.Finance(FinanceAsk.BALANCE)
        }
        // «Сколько денег на Т-Банке», «сколько на карте Сбера», «баланс Тинькофф» — счёт по имени.
        rx("""^(?:сколько\s+(?:у меня\s+)?(?:денег\s+(?:на|в)|на)\s+(?:карте\s+|счете\s+|счету\s+)?|(?:какой\s+)?(?:баланс|остаток)\s+(?:на\s+|по\s+)?(?:карте\s+|счете\s+|карты\s+|счета\s+)?)(.{2,40})$""").find(q)?.let { m ->
            val subject = m.groupValues[1].trim()
            if (!rx("""^(?:сегодня|день|неделю|месяц|этот месяц|зарплат|${NOT_MONEY})""").containsMatchIn(subject)) {
                return SkillCommand.Finance(FinanceAsk.BALANCE, subject)
            }
        }
        // «Сколько осталось на продукты», «сколько ещё можно потратить на кафе», «какой лимит на такси».
        rx("""^(?:сколько\s+(?:мне\s+|у меня\s+)?(?:ещё\s+|еще\s+)?(?:осталось|остается|остаётся|можно\s+потратить|могу\s+потратить|можно\s+тратить)\s+(?:на|по)\s+|(?:какой\s+)?(?:у меня\s+)?(?:лимит|бюджет)\s+(?:на|по)\s+)(.{2,40}?)(?:\s+(?:в этом месяце|до конца месяца|на этот месяц|в месяц|на месяц))?$""").find(q)?.let { m ->
            val subject = m.groupValues[1].trim()
            if (!rx("""^(?:сегодня$|день$|неделю$|месяц$|этот месяц$|жизнь$|${NOT_MONEY})""").containsMatchIn(subject)) {
                return SkillCommand.Finance(FinanceAsk.CATEGORY, subject)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Поиск статьи и заметки

    // «посмотри», «узнай» не берём: «посмотри новости», «узнай, что нового» — вопросы другим навыкам.
    private val FIND_VERB = rx("""^(?:а\s+)?(?:найди|поищи|найти|поискать|глянь)\b(?:\s+мне)?\s*(?:пожалуйста)?\s*(.*)$""")
    private val SAVE_TO_NOTE = rx("""(?:^|[,.]?\s+)(?:и\s+)?(?:запиши|записать|сохрани|сохранить|занеси|занести|добавь|добавить)(?:\s+(?:это|его|её|ее|всё|все|туда))?\s+(?:в|во)\s+(?:заметки|заметку|блокнот|записи)(?:\s|$)""")
    private val SAVE_FIRST = rx("""^(?:запиши|сохрани|занеси|добавь)\s+(?:мне\s+)?(?:в|во)\s+(?:заметки|заметку|блокнот|записи)\s+(.+)$""")

    /** Поиск в интернете без сохранения — команда телефону открыть браузер, а не ответ навыка. */
    private val WEB_ONLY = rx("""^(?:найди|поищи|посмотри)\s+в\s+(?:интернете|гугле|яндексе|сети)|^(?:загугли|погугли)\b""")

    /** Личные записи, устройство и темы других навыков — их ищет Нова, а не Википедия. */
    private val NOT_FIND = rx("""(?:^|\s)(?:мо[йяеюи]|моего|моей|моих|наш[аеу]?|наши)\b|заметк|записях|записи|блокнот|задач|напоминан|расход|календар|контакт|сообщени|уведомлени|погод|курс|новост|пробк|афиш|радио|таймер|будильник|телефон|приложени|файл|фото|видео|песн|музык|фильм|сериал|игр[ауы]|на\s+карте|что\s+(?:нового|случилось|происходит)|в\s+(?:ютубе|youtube|телеграме|инстаграме|вконтакте)""")

    private val RECIPE_WORD = rx("""(?iu)рецепт|как\s+(?:приготовить|сварить|испечь|сделать|готовить|засолить|замариновать)""")
    private val ARTICLE_PREFIX = rx("""(?iu)^(?:статью|статья|статьи|информацию|информация|справку|справка|материал|текст|сведения)\s+(?:про|о|об|обо|по)\s+""")
    private val ARTICLE_TAIL = rx("""(?iu)\s+(?:(?:в|из)\s+(?:википедии|интернете|сети|гугле))\s*$""")
    private val WHERE_PREFIX = rx("""(?iu)^в\s+(?:википедии|интернете|сети|гугле|яндексе)\s+""")

    /**
     * «Найди рецепт борща и запиши его в заметки», «поищи статью про Байкал», «запиши в заметки рецепт сырников».
     * Поиск по своим записям («найди мою заметку про…») сюда не попадает.
     */
    fun find(t: String): SkillCommand.FindArticle? {
        val saveFirst = SAVE_FIRST.find(t)
        val save = SAVE_TO_NOTE.containsMatchIn(t) || saveFirst != null
        if (!save && WEB_ONLY.containsMatchIn(t)) return null
        val verb = FIND_VERB.find(t)
        val raw = when {
            verb != null -> verb.groupValues[1]
            saveFirst != null && RECIPE_WORD.containsMatchIn(saveFirst.groupValues[1]) -> saveFirst.groupValues[1]
            else -> return null
        }
        val body = SAVE_TO_NOTE.replace(raw, "").trim().trim('.', ',', '!', '?', ':', '«', '»')
        if (body.length < 3) return null
        if (RECORD_VERB.containsMatchIn(body)) return null
        // «Найди ближайшую аптеку», «поищи кафе рядом» — это карта, а не статья: пусть решает телефон.
        if (nearbyPlace(body)) return null
        if (NOT_FIND.containsMatchIn(body)) return null
        val query = body.replace(WHERE_PREFIX, "").replace(ARTICLE_PREFIX, "").replace(ARTICLE_TAIL, "")
            .trim().trim('.', ',', '!', '?', ':', '«', '»')
        if (query.length < 2) return null
        return SkillCommand.FindArticle(query, save, RECIPE_WORD.containsMatchIn(body))
    }

    /** Запрос про место рядом: «ближайшая аптека», «кафе рядом», «музей». */
    private fun nearbyPlace(body: String): Boolean {
        val words = RuTokenizer.normalize(body).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return false
        if (words.any { w -> w.any { it.isDigit() } }) return false
        val kinds = words.filterNot { ai.nova.core.nlp.RuQuery.isPlaceModifier(it) }
        return kinds.isNotEmpty() && kinds.all { ai.nova.core.nlp.RuQuery.isPlaceWord(it) }
    }

    // ------------------------------------------------------------------ Календарь и факты

    fun almanac(t: String, today: LocalDate): SkillCommand? {
        val day = dayOf(t, today) ?: today
        if (rx("""^(?:а\s+)?(?:какой|какие|что за)\s+(?:сегодня\s+|завтра\s+|послезавтра\s+|сейчас\s+)?праздник\S*(?:\s+(?:сегодня|завтра|послезавтра|в\s+\S+|на\s+\S+))?$|^(?:сегодня|завтра)\s+(?:какой\s+|есть\s+)?праздник|^(?:есть|будет)\s+(?:ли\s+)?(?:сегодня\s+|завтра\s+)?(?:какой-нибудь\s+|какой-то\s+)?праздник|^что\s+(?:сегодня|завтра)\s+(?:празднуют|отмечают|за праздник)|^что\s+(?:празднуют|отмечают)\s+(?:сегодня|завтра)|^праздник\s+(?:сегодня|завтра)$|^какой\s+(?:сегодня|завтра)\s+день\s+в\s+календаре""").containsMatchIn(t)) {
            return SkillCommand.Almanac(AlmanacKind.HOLIDAY, day)
        }
        if (rx("""(?:ближайш|следующ)\S*\s+праздник|^когда\s+(?:следующий|ближайший)\s+праздник|^скоро\s+(?:какой\s+)?праздник""").containsMatchIn(t)) {
            return SkillCommand.Almanac(AlmanacKind.NEXT_HOLIDAY, today)
        }
        rx("""^(?:а\s+)?когда\s+(?:будет\s+|в этом году\s+|наступит\s+|празднуют\s+|отмечают\s+)?(?:в этом году\s+)?(пасха|масленица|масленицу|троица|прощеное воскресенье|вербное воскресенье|медовый спас|яблочный спас|ореховый спас|покров|крещение|рождество|вознесение|старый новый год|татьянин день|иван купала|день\s+(?!рождени)\S+(?:\s+\S+){0,3}?)(?:\s+в этом году|\s+в следующем году)?$""").find(t)?.let { m ->
            val name = m.groupValues[1].replace("масленицу", "масленица")
            Almanac.findTitled(name, today)?.let { (d, title) -> return SkillCommand.Almanac(AlmanacKind.WHEN_HOLIDAY, d, title) }
        }
        if (rx("""^(?:а\s+)?(?:у кого|чьи|какие)\s+(?:сегодня\s+|завтра\s+)?именины|^именины\s+(?:сегодня|завтра)|^(?:сегодня|завтра)\s+(?:чьи\s+)?именины|^именины$|^кто\s+(?:сегодня\s+)?именинник""").containsMatchIn(t)) {
            return SkillCommand.Almanac(AlmanacKind.NAME_DAY, day)
        }
        rx("""^когда\s+(?:будут\s+)?(?:именины|день ангела)\s+(?:у\s+)?(\S+)$|^когда\s+(?:у\s+)?(\S+)\s+(?:именины|день ангела)$""").find(t)?.let { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (name !in setOf("меня", "тебя", "нас", "вас")) return SkillCommand.Almanac(AlmanacKind.NAME_DAY_OF, today, name)
        }
        if (rx("""^(?:а\s+)?(?:какие|какая|скажи|расскажи|назови)?\s*(?:народн\S*\s+)?примет[аыу](?:\s+(?:на|про)\s+(?:сегодня|завтра|этот день))?$|^(?:какие|какая)\s+(?:сегодня\s+|завтра\s+)?(?:народн\S*\s+)?примет|^примета\s+дня|^народный календарь""").containsMatchIn(t)) {
            return SkillCommand.Almanac(AlmanacKind.OMEN, day)
        }
        return null
    }

    /** «Поздравь Машу с днём рождения», «напиши поздравление маме с 8 марта», «поздравь папу». */
    fun greet(t: String, original: String): SkillCommand? {
        val m = rx("""^(?:поздравь|поздравить|отправь поздравление|напиши поздравление|пошли поздравление|сочини поздравление)\s+(?:для\s+)?(\S+)(?:\s+(с\s+.+))?$""").find(t) ?: return null
        val who = m.groupValues[1]
        if (who in setOf("меня", "нас", "всех", "с", "коллег")) return null
        val occ = m.groupValues[2].removePrefix("с ").trim()
        val occasion = when {
            occ.isEmpty() -> ""
            rx("""рожд|днюх|др$""").containsMatchIn(occ) && !occ.contains("рождеств") -> "с днём рождения"
            else -> "с ${occ}"
        }
        return SkillCommand.Greet(original.trim().split(Regex("""\s+""")).firstOrNull { RuTokenizer.normalize(it).trim(',', '.') == who } ?: who, occasion)
    }

    fun factOfDay(t: String): SkillCommand? {
        if (rx("""^(?:а\s+)?(?:расскажи|скажи|назови|дай)?\s*(?:мне\s+)?факт\s+дня|^(?:какой\s+)?факт\s+дня""").containsMatchIn(t)) return SkillCommand.FactOfDay(true)
        if (rx("""^(?:а\s+)?(?:расскажи|скажи|назови|дай)?\s*(?:мне\s+)?(?:еще\s+)?(?:какой-нибудь\s+|один\s+)?(?:интересн|любопытн|случайн|удивительн|научн)\S*\s+факт|^(?:расскажи|скажи)\s+(?:мне\s+)?(?:что-нибудь|что-то)\s+интересное$|^удиви\s+меня$|^(?:ты\s+)?знаешь\s+(?:что-нибудь|что-то)\s+интересное|^расскажи\s+факт$""").containsMatchIn(t)) {
            return SkillCommand.FactOfDay(false)
        }
        return null
    }

    // ------------------------------------------------------------------ Погода

    private val WEATHER = rx(
        """погод|прогноз(?!ир)|^(?:сегодня|завтра)\s+(?:будет\s+)?(?:холодно|тепло|жарко|прохладно)(?:\s+будет)?$|(?:дождик|дождичек)\s+(?:сегодня\s+|завтра\s+)?(?:будет|пойдет|ожидается)|(?:будет\s+(?:ли\s+)?)?(?:сегодня\s+|завтра\s+)?(?:солнечно|пасмурно|облачно|ветрено)(?:\s+(?:сегодня|завтра|на улице))?$|\bветер\s+(?:сильный|сегодня|завтра|на улице)|сильный\s+ветер\s+(?:сегодня|завтра)|какой\s+(?:сегодня\s+|завтра\s+)?ветер|(?:будет|пойдет|ожидается|обещают|идет)\s+(?:ли\s+)?(?:сегодня\s+|завтра\s+|сейчас\s+)?(?:дожд|снег|гроз|ливень)|(?:дождь|снег)\s+(?:сегодня\s+|завтра\s+)?(?:будет|пойдет|ожидается)|\bзонт|сколько\s+(?:сейчас\s+)?градусов|какая\s+(?:сейчас\s+|сегодня\s+|завтра\s+)?температура|(?:холодно|тепло|жарко|прохладно)\s+(?:ли\s+)?(?:сегодня|завтра|на улице|сейчас)|на улице\s+(?:холодно|тепло|жарко|дождь)|как\s+(?:мне\s+)?одеться|что\s+(?:мне\s+)?надеть|нужн[аоы]?\s+ли\s+(?:сегодня\s+|завтра\s+)?(?:куртк|шапк|пуховик|перчатк|шарф|плащ)|(?:куртку|шапку|пуховик|перчатки|шарф)\s+(?:надевать|брать)|(?:надевать|брать)\s+(?:ли\s+)?(?:куртку|шапку|пуховик)""",
    )

    private val DATE_WORDS = setOf(
        "сегодня", "завтра", "послезавтра", "сейчас", "неделю", "неделе", "выходные", "выходных", "понедельник", "вторник", "среду", "четверг",
        "пятницу", "субботу", "воскресенье", "течение", "ближайшие", "ближайшее", "будет", "ли", "погода", "погоду", "какая", "утром", "вечером",
        "днем", "ночью", "улице", "городе", "моем", "нашем", "тоже", "а", "и", "сколько", "градусов",
        "эти", "эту", "этот", "это", "следующий", "следующую", "следующие", "ближайший", "ближайшую", "выходной", "праздники", "отпуск",
    )

    fun weather(t: String, original: String, today: LocalDate): WeatherQuery? {
        if (!WEATHER.containsMatchIn(t)) return null
        if (rx("""\bпогод\S*\s+в\s+доме\b|прогноз\s+(?:расход|продаж|бюджет)""").containsMatchIn(t)) return null
        // «Сколько градусов в духовке», «что надеть в театр» — не погода.
        if (rx("""градус\S*\s+(?:в|во|у|на)\s+(?:духовк|печ|холодильник|морозилк|воде|ванн|бане|сауне|чайник|комнат|квартир|доме|машине|салоне|аквариум)|градус\S*\s+(?:угол|угла|спирт|водк|вин)|(?:надеть|одеться|нарядиться)\s+(?:в|на|для)\s+(?:\S+\s+)?(?:театр|школ|работ|свадьб|концерт|собеседован|ресторан|вечеринк|праздник|день рожд|клуб|офис|кино|гости|выпускн|свидани|экзамен|корпоратив|утренник|похорон|венчани|бал\b|интервью|фотосесси)""").containsMatchIn(t)) return null
        val aspect = when {
            rx("""\bзонт""").containsMatchIn(t) -> WeatherQuery.Aspect.UMBRELLA
            rx("""дожд|ливень|гроз""").containsMatchIn(t) -> WeatherQuery.Aspect.RAIN
            rx("""\bснег""").containsMatchIn(t) -> WeatherQuery.Aspect.SNOW
            rx("""на неделю|на неделе|на выходн|на 7 дней|на семь дней|на несколько дней""").containsMatchIn(t) -> WeatherQuery.Aspect.WEEK
            else -> WeatherQuery.Aspect.GENERAL
        }
        return WeatherQuery(place = weatherPlace(t, original), date = dayOf(t, today), aspect = aspect)
    }

    /** День в вопросе: сегодня/сейчас → null. */
    fun dayOf(t: String, today: LocalDate): LocalDate? {
        if (t.contains("послезавтра")) return today.plusDays(2)
        if (rx("""\bзавтра\b""").containsMatchIn(t)) return today.plusDays(1)
        rx("""через\s+(\d+|два|три|четыре|пять|шесть)\s+дн""").find(DevicePhrases.digitize(t))?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: mapOf("два" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6)[m.groupValues[1]] ?: 1
            return today.plusDays(n.toLong())
        }
        val days = listOf(
            "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среду" to DayOfWeek.WEDNESDAY, "четверг" to DayOfWeek.THURSDAY,
            "пятницу" to DayOfWeek.FRIDAY, "субботу" to DayOfWeek.SATURDAY, "воскресенье" to DayOfWeek.SUNDAY,
        )
        for ((w, d) in days) if (rx("""\b$w\b""").containsMatchIn(t)) {
            val date = today.with(TemporalAdjusters.nextOrSame(d))
            return if (date == today) null else date
        }
        return null
    }

    /** «в Казани», «во Владимире», «в Нижнем Новгороде», «в Ростове-на-Дону»; «дома» — сохранённое место. */
    fun weatherPlace(t: String, original: String): String? {
        if (rx("""\b(?:дома|у меня дома|у дома)\b""").containsMatchIn(t)) return "дом"
        val o = original.trim().trimEnd('.', '!', '?').replace(Regex("""\s+"""), " ")
        val m = Regex("""(?iu)(?:^|\s)(?:в|во)\s+""").findAll(o).toList()
        for (hit in m) {
            val words = o.substring(hit.range.last + 1).split(' ').filter { it.isNotBlank() }
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size && out.size < 3) {
                val w = words[i].trim(',', '.', '?', '!')
                val n = RuTokenizer.normalize(w)
                if (n in DATE_WORDS || n.isEmpty() || n.any { it.isDigit() }) break
                if (n == "на" && i + 1 < words.size && RuTokenizer.normalize(words[i + 1]).trim(',', '?').let { it in setOf("дону", "амуре", "волге", "неве") }) {
                    out += "на"; out += words[i + 1].trim(',', '.', '?', '!'); i += 2; continue
                }
                if (n in setOf("на", "в", "во", "и", "а", "у", "по", "с", "к")) break
                out += w
                i++
            }
            val place = out.joinToString(" ").trim()
            if (place.isNotEmpty() && RuTokenizer.normalize(place) !in DATE_WORDS && !rx("""^(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье|выходные|течение|ближайш)""").containsMatchIn(RuTokenizer.normalize(place))) {
                return place.split('-').joinToString("-") { part -> part.split(' ').joinToString(" ") { w -> if (w == "на") w else w.replaceFirstChar { it.uppercase() } } }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Курсы

    fun rates(t: String): RatesQuery? {
        if (rx("""^курс(?:ы)?\s+валют|^какой\s+курс$|^курсы$""").containsMatchIn(t)) return RatesQuery()
        val codes = RatesService.codesIn(t)
        if (codes.isEmpty()) return null
        // «Переведи 10 фунтов в килограммы» — это вес, а не валюта.
        if (rx("""\b(?:кг|килограмм|грамм|км|километр|мил[ьяие]|метр|фут|дюйм|литр|унци|галлон)""").containsMatchIn(t)) return null
        val asksRate = rx("""^(?:а\s+)?(?:что|как)\s+(?:там\s+)?(?:по|с)\s+(?:доллар|евро|юан|биткоин|рубл|валют)|\bкурс|почем|сколько\s+(?:сейчас\s+)?стоит|цена\s+(?:на\s+)?(?:доллар|евро|юан|биткоин|биткойн|эфир)|сколько\s+(?:сейчас\s+)?рублей\s+(?:за|в)\s+(?:одном\s+|1\s+)?(?:доллар|евро|юан)""").containsMatchIn(t)
        val conversion = rx("""сколько\s+будет|сколько\s+это|это\s+сколько|переведи|пересчитай|посчитай|конвертируй|в\s+рублях|в\s+рубли|сколько\s+рублей|сколько\s+(?:долларов|евро|юаней|биткоинов)|в\s+долларах|в\s+доллары|в\s+евро|в\s+юанях""").containsMatchIn(t)
        val d = DevicePhrases.digitize(t)
        val rubAmount = rx("""(\d+(?:[.,]\d+)?)\s*(?:руб|₽|р\b)""").find(d)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val anyAmount = rx("""(\d+(?:[.,]\d+)?)""").find(d)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        if (conversion && anyAmount != null) {
            return if (rubAmount != null) RatesQuery(codes, rubAmount, fromRub = true) else RatesQuery(codes, anyAmount, fromRub = false)
        }
        // «доллар сколько», «а евро?» — коротко про валюту.
        if (asksRate || (t.split(' ').size <= 3 && rx("""сколько$|почем$|^(?:а\s+)?(?:доллар|евро|юань|биткоин)\S*$""").containsMatchIn(t))) return RatesQuery(codes)
        return null
    }

    // ------------------------------------------------------------------ Новости

    private val NEWS = rx(
        """^(?:а\s+)?(?:(?:расскажи|прочитай|почитай|покажи|включи|какие|скажи|озвучь|давай)\s+)?(?:мне\s+)?(?:последние\s+|свежие\s+|главные\s+|сегодняшние\s+|самые\s+важные\s+)?новост|^что\s+нового(?:\s+(?:в|во|про|о|об|насчёт|насчет|по)\s+.+)?$|^что\s+(?:сейчас\s+)?происходит\s+в\s+мире|^что\s+(?:сейчас\s+|сегодня\s+)?(?:пишут|говорят)\s+в\s+новост|^(?:че|чё|что)\s+(?:там\s+)?(?:нового|слышно|пишут|говорят)(?:\s+(?:нового|новенького|интересного|свежего))?(?:\s+(?:в|во|про|о|об|насчёт|насчет|по)\s+.+)?$|^есть\s+(?:какие-нибудь\s+|свежие\s+)?новости(?!\s+(?:от|из|у)\s)|^что\s+(?:сегодня\s+|сейчас\s+)?в\s+новостях""",
    )

    fun news(t: String): SkillCommand? {
        newsDetails(t)?.let { return it }
        newsByOrdinal(t)?.let { return it }
        if (!NEWS.containsMatchIn(t)) return null
        // Конкретика важнее выбора в настройках: «новости про недвижимость» — это поиск по теме.
        val about = newsAbout(t)
        // Рубрику человек может назвать сам — она важнее выбранной в настройках.
        return SkillCommand.News(if (about == null) topicOf(t) else null, about)
    }

    /**
     * Уточнение темы после прочитанной ленты: «а про недвижимость?», «а спорт?».
     * null — темы в фразе нет.
     */
    fun newsFollowUp(t: String): SkillCommand.News? {
        val about = newsAbout("новости $t")
        val topic = if (about == null) topicOf(t) else null
        if (about == null && topic == null) return null
        return SkillCommand.News(topic, about)
    }

    /** Хвост фразы после слова «новости» / «что нового». */
    private val NEWS_ANCHOR = Regex("""(?:новост\p{L}*|что\s+нового|что\s+слышно|что\s+происходит|что\s+там)""")

    /** Связки перед темой: «про», «о», «в мире», «по теме», «в сфере». */
    private val NEWS_LEAD = Regex(
        """(?:(?:в|во)\s+(?:мире|стране|россии|нашей\s+стране)|нового|новенького|интересного|свежего|новостей|новости|новость|""" +
            """про|о|об|обо|насчёт|насчет|касательно|для|по\s+теме|по\s+поводу|в\s+сфере|в\s+области|в\s+теме|на\s+тему|по)\s+""",
    )

    /**
     * Тема новостей своими словами: «новости в мире видеоигр» → «видеоигр»,
     * «что нового про пк» → «пк». null — тема не названа, работаем по настройкам.
     */
    private fun newsAbout(t: String): String? {
        val anchor = NEWS_ANCHOR.find(t) ?: return null
        var tail = t.substring(anchor.range.last + 1).trim().trim(',', '.', ':', ';', '!', '?')
        // «за неделю», «на сегодня» — это период, а не тема.
        if (rx("""^(?:за|на|в)?\s*(?:неделю|месяц|день|сутки|выходные|сегодня|вчера|год|час)$""").containsMatchIn(tail)) return null
        tail = tail.replace(Regex("""\s+(?:сегодня|сейчас|пожалуйста|за\s+сегодня|на\s+сегодня|за\s+неделю|за\s+месяц)\s*$"""), "").trim()
        // «новости в мире», «новости в стране» — это регион: его обрабатывает topicOf, а не поиск.
        if (rx("""^(?:в|во)\s+(?:мире|стране|россии|нашей\s+стране)$""").containsMatchIn(tail)) return null
        // Связки идут подряд: «что нового про новости спорта» → «спорта».
        repeat(3) { tail = tail.replaceFirst(NEWS_LEAD, "").trim() }
        if (tail.isEmpty() || tail.length < 2) return null
        // «новости в мире», «новости спорта», «новости России» — регион и рубрики, а не тема.
        if (rx("""^(?:мир\p{L}*|стран\p{L}*|росси\p{L}*|рф|спорт\p{L}*|наук\p{L}*|эконом\p{L}*|технолог\p{L}*|культур\p{L}*|здоров\p{L}*|главн\p{L}*|важн\p{L}*|всё|все|вообще|интересн\p{L}*)$""").containsMatchIn(tail)) return null
        if (tail.split(' ').size > 6) return null
        // Названная тема — известная рубрика («новости про космос») — лучше рубрикой.
        if (tail.split(' ').size == 1 && topicOf(tail) != null) return null
        return tail
    }

    /** «Новости спорта», «что в экономике» — рубрика из фразы; null — «просто новости». */
    private fun topicOf(t: String): NewsTopic? = when {
        rx("""спорт|футбол|хоккей""").containsMatchIn(t) -> NewsTopic.SPORT
        rx("""технолог|айти|\bit\b|гаджет|компьютер""").containsMatchIn(t) -> NewsTopic.TECH
        rx("""наук|космос""").containsMatchIn(t) -> NewsTopic.SCIENCE
        rx("""эконом|финанс|бизнес|рынк""").containsMatchIn(t) -> NewsTopic.ECONOMY
        rx("""культур|кино|театр|концерт""").containsMatchIn(t) -> NewsTopic.CULTURE
        rx("""здоров|медицин|врач""").containsMatchIn(t) -> NewsTopic.HEALTH
        // «Новости мира», «что в мире», «новости страны», «что в стране» — это регион, а не рубрика.
        rx("""\bв мире\b|\bмир(?:а|е)\b""").containsMatchIn(t) -> NewsTopic.WORLD
        rx("""\bв стране\b|\bстран(?:а|е|ы)\b|\bросси""").containsMatchIn(t) -> NewsTopic.COUNTRY
        else -> null
    }

    private val NEWS_ORDINALS = listOf("перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят")

    /** Номер новости в последней ленте: «вторая», «про третью», «новость номер 4». */
    private fun newsIndex(t: String): Int? {
        Regex("""(?:новост\p{L}*\s*(?:номер|№)\s*|№\s*)(\d{1,2})""").find(t)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0).takeIf { it in 1..10 }?.minus(1)
        }
        NEWS_ORDINALS.forEachIndexed { i, stem ->
            // «о второй» — номер без слова «новость»; иначе порядочное слово ищем только рядом с новостями.
            val ordinal = rx("""\b$stem\p{L}*(?:\s+новост\p{L}*)?\b""").containsMatchIn(t)
            if (ordinal && (t.contains("новост") || t.trim().split(' ').size == 1)) return i
        }
        return null
    }

    /**
     * «Подробнее», «расскажи подробнее о второй», «про что третья новость», «про новость о сухогрузе».
     * Возвращает null, если фраза не про новости — тогда её разберут другие навыки.
     */
    private fun newsDetails(t: String): SkillCommand? {
        val asksMore = rx("""^(?:расскажи\s+|а\s+)?(?:подробнее|поподробнее|подробней|полностью|целиком)(?:\s+расскажи)?$""").containsMatchIn(t)
        if (asksMore) return SkillCommand.NewsDetails(0)
        // «подробнее о второй», «подробнее про сухогруз» — всегда про новости.
        val aboutMore = rx("""^(?:расскажи\s+|а\s+)?(?:подробнее|поподробнее|подробней)\s+(?:о|об|про)\s+(.+)$""")
        // «расскажи про вторую новость», «что там про сухогруз в новостях» — только если речь о новостях.
        val about = rx("""^(?:расскажи|прочитай|открой|покажи|что\s+там)\s+(?:мне\s+)?(?:подробнее\s+|поподробнее\s+)?(?:о|об|про)\s+(.+)$""")
        val subject = (aboutMore.find(t)?.groupValues?.get(1)
            ?: about.find(t)?.groupValues?.get(1)?.takeIf { it.contains("новост") })
            ?.trim()?.trim('?', '.', ',') ?: return null
        if (subject.isEmpty()) return null
        newsIndex(subject)?.let { return SkillCommand.NewsDetails(it) }
        // «про новость», «про новости» — первая в ленте; иначе ищем по словам заголовка.
        if (rx("""^(?:о|об|про)?\s*новост\p{L}*(?:\s+номер\s*\d+)?$""").containsMatchIn(subject)) return SkillCommand.NewsDetails(0)
        return SkillCommand.NewsDetails(about = subject)
    }

    /** Номер новости без слова «подробнее»: «третья новость», «новость номер 4». */
    private fun newsByOrdinal(t: String): SkillCommand? {
        // «Третья новость» — номер без слова «подробнее»; короткая фраза, чтобы не перехватывать лишнее.
        if (!t.contains("новост") || t.trim().split(' ').size > 4) return null
        return newsIndex(t)?.let { SkillCommand.NewsDetails(it) }
    }

    // ------------------------------------------------------------------ Факты (Википедия)

    private val FACT = Regex(
        """^(?:а\s+)?(?:скажи\s+)?(?:кто\s+(?:такой|такая|такие|такое|был|была|были)|что\s+(?:такое|значит|означает)|что\s+ты\s+знаешь\s+(?:про|о|об)|расскажи\s+(?:мне\s+)?(?:про|о|об)|кто\s+(?:написал|изобрел|придумал|открыл|основал|построил)|где\s+находится|сколько\s+лет)\s+(.+)$""",
    )
    private val NOT_FACT = rx("""^(?:ты|я|меня|тебя|себя|мы|вы|он|она|они|это|то|такое|мой|моя|мои|твой|твоя)$|(?:^|\s)(?:мо[йяеюи]|моего|моей|моих|моим|наш\S*|твой|твоего|твоей|твою|свой|свою|своего|своей)(?:\s|$)|задач|расход|заметк|напоминан|\bплан|\bдень\b|список|покупк|погод|курс|новост|сообщени|календар|себе|\bмне\b|себя|тебе|по\s*-?\s*твоему|как ты думаешь|твое мнение""")

    fun fact(t: String, assistantName: String): String? {
        val m = FACT.find(t) ?: return null
        val subject = m.groupValues[1].trim().trim('«', '»', '"', '?')
        if (subject.length < 2 || NOT_FACT.containsMatchIn(subject)) return null
        if (RuTokenizer.normalize(subject) in setOf(RuTokenizer.normalize(assistantName), "нова", "алиса", "сири")) return null
        // «где находится» — вопрос о месте: спросим статью о самом месте.
        return subject
    }

    // ------------------------------------------------------------------ Сообщения

    private val READ_MSG = rx(
        """^(?:прочитай|прочти|зачитай|почитай|покажи|озвучь|проверь)\s+(?:мне\s+)?(?:мои\s+|все\s+)?(?:новые\s+|последние\s+|непрочитанные\s+|входящие\s+)?(?:сообщени|уведомлени|смс|эсэмэск|месседж|чаты)|^что\s+(?:мне\s+)?(?:пришло|написали|пишут)(?!\s+(?:в|во|на|об?|про)\s+(?:новост|интернет|газет|сети|мире|стране|сми|соцсет|телеграм))|^(?:есть\s+(?:ли\s+)?(?:у меня\s+)?|были\s+)?(?:новые\s+)?сообщения$|^есть\s+(?:ли\s+)?(?:у меня\s+)?(?:новые\s+)?сообщения|^кто\s+мне\s+(?:писал|написал)|^(?:прочитай|покажи|скажи)\s+(?:мне\s+)?что\s+(?:мне\s+)?(?:пришло|написали)(?!\s+(?:в|во|на|об?|про)\s+(?:новост|интернет|газет|сети|мире|стране|сми|соцсет|телеграм))|^кто\s+(?:писал|написал)$|^что\s+(?:мне\s+)?(?:написал|написала|пишет|пишут)\s+(.+)$""",
    )

    fun messages(t: String, original: String): SkillCommand? {
        rx("""^(?:ответь|ответить|отправь\s+ответ|напиши\s+в\s+ответ)\b[,:]?\s*(.*)$""").find(t)?.let { m ->
            // «ответь на вопрос…», «ответь мне честно» — это просьба к Нова, а не ответ на сообщение.
            if (rx("""^(?:на\s+(?:вопрос|загадк|вопросы|тест)|мне\b|честно|пожалуйста|правду|быстро|коротко|подробно|как\s|почему|что\s+такое)""").containsMatchIn(m.groupValues[1].trim())) return null
            val rest = m.groupValues[1].trim().replace(Regex("""^(?:на\s+(?:это\s+|последнее\s+)?(?:сообщение|смс)|на\s+него|на\s+нее)\s*[,:]?\s*"""), "")
            if (rest.isEmpty()) return SkillCommand.ReplyMessage(null, "", rest)
            // «ответь маше: буду через 10 минут», «ответь ему, что опоздаю», «ответь буду…».
            val colon = rest.split(Regex("""\s*:\s*"""), limit = 2)
            if (colon.size == 2 && colon[0].isNotBlank() && colon[0].split(' ').size <= 3) {
                return SkillCommand.ReplyMessage(colon[0].trim(), colon[1].trim(), colon[1].trim(), explicitTo = true)
            }
            val first = rest.substringBefore(' ').trim(',', '.', '!')
            val tail = rest.substringAfter(' ', "").trim().removePrefix("что ").trim()
            if (colon.size == 2 && colon[0].isBlank()) return SkillCommand.ReplyMessage(null, colon[1].trim(), colon[1].trim())
            return SkillCommand.ReplyMessage(first.takeIf { tail.isNotEmpty() }, tail.ifEmpty { rest }, rest.removePrefix("что ").trim())
        }
        val m = READ_MSG.find(t) ?: return null
        val from = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: rx("""(?:от|из)\s+(.+)$""").find(t)?.groupValues?.get(1)?.takeIf { !it.startsWith("всех") }
        return SkillCommand.ReadMessages(from?.trim())
    }

    // ------------------------------------------------------------------ Экран

    fun screen(t: String): ScreenMode? = when {
        rx("""^переведи\s+(?:мне\s+)?(?:экран|страниц|то\s+что\s+на\s+экране|что\s+на\s+экране|текст\s+на\s+экране|это)""").containsMatchIn(t) -> ScreenMode.TRANSLATE
        rx("""^(?:перескажи|кратко|о\s+ч[её]м|про\s+что)\s+(?:мне\s+)?(?:экран|страниц|стать|эту\s+(?:статью|страницу|новость)|это|текст|что\s+на\s+экране|эта\s+статья|эта\s+страница|этот\s+текст)|^о\s+ч[её]м\s+(?:эта\s+)?(?:статья|страница|текст)""").containsMatchIn(t) -> ScreenMode.SUMMARY
        rx("""^(?:что|кто)\s+(?:у\s+меня\s+)?(?:сейчас\s+)?на\s+экране|^прочитай\s+(?:мне\s+)?(?:экран|страниц|то\s+что\s+на\s+экране|что\s+на\s+экране|текст\s+на\s+экране|с\s+экрана)""").containsMatchIn(t) -> ScreenMode.READ
        else -> null
    }

    // ------------------------------------------------------------------ Контакты

    fun contact(t: String): String? {
        val m = rx("""^(?:какой|скажи|назови|продиктуй|дай|найди|подскажи)\s+(?:мне\s+)?(?:номер|телефон)(?:\s+телефона)?\s+(?:у\s+)?(.+)$|^(?:номер|телефон)\s+(?:телефона\s+)?(?:у\s+)?(.+)$|^какой\s+у\s+(.+?)\s+(?:номер|телефон)(?:\s+телефона)?$""").find(t) ?: return null
        val name = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (name in setOf("мой", "мне", "меня", "телефона", "этот")) return null
        // «какой номер моего паспорта» — вопрос к памяти, а не к контактам.
        if (rx("""(?:^|\s)(?:мо[йяеи]|моего|моей|моих|наш\S*|твой|твоего|свой|своего|своей)(?:\s|$)|паспорт|квартир|машин|автомобил|карт[ыаеу]|счет|снилс|инн|полис|заказ|рейс|поезд|дом[аеу]?$|подъезд|офис""").containsMatchIn(name)) return null
        return name
    }

    // ------------------------------------------------------------------ Календарь

    fun calendar(t: String, today: LocalDate): SkillCommand? {
        if (rx("""^когда\s+(?:у\s+меня\s+)?(?:следующая|ближайшая|моя\s+следующая)\s+(?:встреча|событие|мероприятие)""").containsMatchIn(t)) {
            return SkillCommand.Calendar(today, today.plusDays(30), nextOnly = true)
        }
        val hit = rx("""в\s+календаре|^(?:какие|что\s+за|есть\s+ли)\s+(?:у\s+меня\s+)?(?:встречи|события|мероприятия)|^(?:мои\s+)?(?:встречи|события)\s+(?:на\s+)?(?:сегодня|завтра|неделю|неделе)""").containsMatchIn(t)
        if (!hit || rx("""^(?:добавь|запиши|создай|поставь|внеси)""").containsMatchIn(t)) return null
        val (from, to) = when {
            rx("""неделю|неделе|7 дней|семь дней""").containsMatchIn(t) -> today to today.plusDays(6)
            else -> (dayOf(t, today) ?: today).let { it to it }
        }
        return SkillCommand.Calendar(from, to)
    }

    // ------------------------------------------------------------------ Радио

    fun radio(t: String): SkillCommand? {
        if (rx("""^(?:выключи|останови|отключи|стоп|хватит|убери|заглуши|выруби)\s+(?:это\s+)?радио""").containsMatchIn(t)) return SkillCommand.RadioStop
        if (rx("""^(?:следующ(?:ая|ую)|другую|другая|переключи(?:\s+на)?\s+(?:другую|следующую))\s+(?:радио)?станци|^(?:другое|следующее)\s+радио$|^переключи\s+радио$""").containsMatchIn(t)) return SkillCommand.RadioNext
        rx("""^(?:включи|поставь|запусти|найди|играй|хочу\s+(?:по)?слушать|давай)\s+(?:мне\s+)?(?:какое[- ]нибудь\s+|любое\s+|интернет[- ]?)?радио(?:\s+(.+))?$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1].takeIf { g -> g.isNotBlank() }) }
        rx("""^радио(?:\s+(.+))?$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1].takeIf { g -> g.isNotBlank() }) }
        rx("""^(?:включи|поставь|запусти)\s+(.+?)\s+радио$""").find(t)?.let { return SkillCommand.RadioPlay(it.groupValues[1] + " радио") }
        rx("""^(?:включи|поставь|запусти)\s+(.+)$""").find(t)?.let { m ->
            val q = m.groupValues[1]
            val station = RadioCatalog.builtIn(q)
            // Только явные названия станций: «включи Европу Плюс», «включи Ретро ФМ».
            if (station != null && rx("""\b(?:фм|fm|плюс|радио|вести|маяк|шансон)\b""").containsMatchIn(q)) return SkillCommand.RadioPlay(q)
        }
        return null
    }

    // ------------------------------------------------------------------ Музыка

    /** Названия музыкальных приложений в конце фразы: «включи песню Кино в Яндекс Музыке». */
    private const val MUSIC_APP = """(?:яндекс\s*музык\S*|яндекс\s*мьюзик\S*|yandex\s*music|яндекс|спотифа\S*|spotify|звук\S*|вк\s*музык\S*|вконтакте|ютуб\s*музык\S*|youtube\s*music)"""

    private val PLAY_VERB = rx("""^(?:включи|поставь|запусти|вруби|врубай|сыграй|проиграй|найди|поищи|хочу\s+(?:послушать|услышать)|давай)(?:\s+|$)""")
    private val TRACK_WORD = rx("""^(?:мне\s+)?(?:песню|песенку|трек|композицию|музыку|музычку|музон|что-нибудь)(?:\s+|$)""")

    /** «Яндекс Музыка» / «спотифай» / «Звук» → приложение. */
    fun musicApp(name: String): MusicApp? {
        val n = name.lowercase()
        return when {
            rx("""спотифа|spotify""").containsMatchIn(n) -> MusicApp.SPOTIFY
            rx("""звук""").containsMatchIn(n) -> MusicApp.ZVUK
            rx("""вконтакте|(?:^|\s)вк(?:\s|$)""").containsMatchIn(n) -> MusicApp.VK
            rx("""ютуб|youtube""").containsMatchIn(n) -> MusicApp.YOUTUBE
            rx("""яндекс|yandex""").containsMatchIn(n) -> MusicApp.YANDEX
            else -> null
        }
    }

    fun music(t: String): SkillCommand? {
        // «найди текст песни Кино», «аккорды к песне Кино — Группа крови»
        rx("""^(?:найди|поищи|покажи|дай|подскажи|открой|где)?\s*(?:мне\s+)?(?:аккорды|аккорд)\s*(?:к\s+|для\s+)?(?:песне|песни|треку|трека)?\s*(.+)$""").find(t)?.let { m ->
            trackQuery(m.groupValues[1])?.let { return SkillCommand.MusicText(it, chords = true) }
            return SkillCommand.MusicText(null, chords = true)
        }
        rx("""^(?:найди|поищи|покажи|дай|подскажи|открой|прочитай|какие)?\s*(?:мне\s+)?(?:текст|слова)\s*(?:песни|трека|песенки)?\s*(.+)$""").find(t)?.let { m ->
            trackQuery(m.groupValues[1])?.let { return SkillCommand.MusicText(it, chords = false) }
            return SkillCommand.MusicText(null, chords = false)
        }
        // «поставь лайк на песню Кино», «лайкни Группу крови»
        rx("""^(?:поставь|поставить|добавь|поставь\s+же|лайкни|лайк)\s*(?:лайк|сердечко|в\s+избранное)?\s*(?:на|к)?\s*(?:песн[юеи]|треку?|композици[юи])?\s*(.*)$""").find(t)?.takeIf {
            t.contains("лайк") || t.contains("сердечко") || t.contains("в избранное")
        }?.let { m ->
            return SkillCommand.MusicLike(trackQuery(m.groupValues[1]) ?: "")
        }
        // «включи песню «Кино — Группа крови» в Яндекс Музыке» — приложение назвали явно.
        rx("""^(.+?)\s+в\s+(?:приложении\s+)?($MUSIC_APP)$""").find(t)?.let { m ->
            val app = musicApp(m.groupValues[2]) ?: return@let
            val query = trackQuery(m.groupValues[1])
            if (query != null) return SkillCommand.MusicPlay(query, app)
            // «включи музыку в спотифае» — название не назвали, просто открываем приложение.
            if (rx("""^(?:включи|поставь|запусти|открой|вруби|врубай)""").containsMatchIn(m.groupValues[1])) return SkillCommand.MusicPlay(null, app)
        }
        rx("""^(?:включи|поставь|запусти|открой|вруби|врубай)\s+(?:мне\s+)?(?:музыку\s+)?в\s+(?:приложении\s+)?($MUSIC_APP)$""").find(t)?.let { m ->
            musicApp(m.groupValues[1])?.let { return SkillCommand.MusicPlay(null, it) }
        }
        // «включи музыку» без названия песни: включаем музыку в приложении, а не жмём медиа-клавишу вслепую.
        rx("""^(?:включи|поставь|запусти|открой|вруби|врубай|играй|хочу|продолжи|продолжай)\s+(?:мне\s+)?(?:какую[- ]нибудь\s+|любую\s+|свою\s+|любимую\s+|немного\s+)?(?:музыку|музычку|музон|музыка|что[- ]нибудь)(?:\s+(?:пожалуйста|плиз))?$""").find(t)?.let {
            return SkillCommand.MusicPlay(null, null)
        }
        // «включи песню Кино — Группа крови» — играет встроенный плеер Нова.
        rx("""^(?:включи|поставь|запусти|вруби|врубай|сыграй|проиграй|найди|поищи)\s+(?:мне\s+)?(?:песню|песенку|трек|композицию|музыку|музычку|музон)\s+(.+)$""").find(t)?.let { m ->
            trackQuery(m.groupValues[1])?.let { return SkillCommand.MusicPlay(it) }
        }
        // «включи мне Многоточие» — песню не назвали, назвали исполнителя: включаем его песни.
        // Берём только «включи»/«вруби» (за «запусти» и «поставь» стоят приложения и медиа-клавиши)
        // и не трогаем предметы телефона: «включи свет», «включи будильник» — это команды телефону.
        rx("""^(?:включи|включи-ка|вруби|врубай)(?:\s+мне)?(?:\s+(?:исполнителя|группу|артиста))?\s+(.+)$""").find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (!DEVICE_THING.containsMatchIn(rest) && !SKILL_THING.containsMatchIn(rest)) {
                artistQuery(rest)?.let { return SkillCommand.MusicPlay(it) }
            }
        }
        // «сыграй Кино — Группа крови»: без слова «песня» — только явные глаголы, иначе это команда телефону.
        rx("""^(?:сыграй|проиграй)\s+(?:мне\s+)?(.+)$""").find(t)?.let { m ->
            trackQuery(m.groupValues[1])?.let { return SkillCommand.MusicPlay(it) }
        }
        return null
    }

    /**
     * Предметы телефона: «включи свет», «включи wi-fi» — это команды, их музыка не перехватывает.
     * Список нарочно широкий: лучше пропустить песню с таким словом, чем сломать команду телефону.
     */
    private val DEVICE_THING = rx("""(?:^|\s)(?:свет|светло|ламп\S*|фонар\S*|wi-?fi|вай-?фай|блютус|bluetooth|интернет|мобильн\S*|звук|громкост\S*|будильник|таймер|секундомер|экран|телевизор|тв|кондиционер|обогревател\S*|розетк\S*|режим\S*|камер\S*|запись|диктофон|вибро\S*|уведомлен\S*|авиарежим|переводчик|подогрев|массаж|вентилятор|автоответчик|не\s+беспокоить|паузу|пауза|медитаци\S*|тренировк\S*|зарядк\S*|телефон\S*|звон\S*|контакт\S*|настройк\S*|калькулятор\S*|часы|будильник\S*|ютуб|youtube|ютюб|telegram|телеграм\S*|ватсап|whatsapp|вконтакте|инстаграм\S*|тикток|браузер\S*|галере\S*|карты|карту|почт\S*|календар\S*|заметк\S*)(?:\s|$)""")

    /** Вопросы другим навыкам: их тоже не перехватываем. */
    private val SKILL_THING = rx("""(?:^|\s)(?:погод\S*|новост\S*|радио|сказк\S*|видео|фильм\S*|сериал\S*|игр\S*|рецепт\S*|текст|аккорды|напоминани\S*|заметк\S*|альбом\S*|плейлист\S*)(?:\s|$)""")

    /** Исполнитель из фразы: убираем кавычки; null — это не название (цифры, слишком длинно, пусто). */
    private fun artistQuery(raw: String): String? {
        val q = raw.replace(Regex("""[«»"'`]"""), " ").replace(rx("""\s+"""), " ").trim()
        if (q.isEmpty() || q.split(' ').size > 5) return null
        if (q.any { it.isDigit() }) return null
        return q
    }

    /** Название песни из фразы: убираем глагол, слово «песню», кавычки и вежливости. */
    fun trackQuery(raw: String): String? {
        var q = raw.trim().trim(' ', ',', '.', '"', '«', '»')
        q = PLAY_VERB.replace(q, "")
        q = TRACK_WORD.replace(q, "")
        q = q.removeSuffix(" пожалуйста").removeSuffix(" плиз").trim().trim('"', '«', '»', ' ', ',', '.')
        if (q.length !in 2..120) return null
        if (rx("""^(?:музыку|музыка|радио|песню|трек|что-нибудь|это|дальше|громче|тише|погромче|потише)$""").containsMatchIn(q)) return null
        return q
    }

    // ------------------------------------------------------------------ Найти телефон

    fun isFindPhone(t: String) = rx("""^(?:где\s+ты|ты\s+где|где\s+(?:мой\s+)?телефон|найди\s+(?:мой\s+)?телефон|подай\s+голос|отзовись|позвони\s+(?:мне\s+)?громко|я\s+тебя\s+(?:не\s+)?(?:вижу|найду|потерял|потеряла)|где\s+же\s+ты)$""").containsMatchIn(t)

    // ------------------------------------------------------------------ Где я

    /** «Где я», «где я нахожусь», «какой у меня адрес» — для тех, кто потерялся. */
    fun isWhereAmI(t: String) = rx("""^(?:нова,?\s+)?(?:а\s+)?(?:где\s+(?:я|мы|сейчас|это\s+место)(?:\s+(?:нахожусь|сейчас|нахожуся|стою|нахожусь\s+сейчас))*|я\s+где|какой\s+(?:у\s+меня\s+)?(?:сейчас\s+)?адрес|мой\s+адрес|покажи\s+(?:где\s+я|мой\s+адрес)|назови\s+(?:мой\s+)?адрес)$""").containsMatchIn(t)

    // ------------------------------------------------------------------ Таймеры

    fun timers(t: String): SkillCommand? {
        if (rx("""сколько\s+(?:еще\s+)?(?:времени\s+)?осталось(?:\s+(?:на\s+)?таймер\S*)?$|сколько\s+(?:еще\s+)?(?:времени\s+)?осталось\s+(?:на|у|до\s+конца)\s+таймер|когда\s+(?:сработает|зазвонит|закончится|прозвенит)\s+таймер|сколько\s+на\s+таймере|^какие\s+(?:у\s+меня\s+)?таймеры|^мои\s+таймеры|^покажи\s+таймер""").containsMatchIn(t)) return SkillCommand.TimersLeft
        rx("""^(?:отмени|выключи|останови|сбрось|удали|убери|отключи)\s+(?:все\s+)?таймер(?:ы|а)?(?:\s+(?:на\s+)?(.+))?$""").find(t)?.let { m ->
            return SkillCommand.TimersCancel(m.groupValues[1].trim().takeIf { it.isNotBlank() && it != "все" })
        }
        timersExtend(t)?.let { return it }
        return null
    }

    /**
     * «Продли таймер на 2 минуты», «продли ещё на 10 минут», «добавь 3 минуты к таймеру».
     * Без числа — пять минут. «Добавь таймер на 5 минут» — это новый таймер, не продление.
     */
    private fun timersExtend(t: String): SkillCommand.TimersExtend? {
        val verb = rx("""^(?:продли|продлить|продлевай|продлеваешь|докинь|прибавь|добавь)\b""")
        if (!verb.containsMatchIn(t)) return null
        // Без слова «таймер» продление понимаем только по «продли…»: иначе «прибавь громкость»
        // и «добавь таймер на 5 минут» уедут не туда.
        val prolong = rx("""^продли""").containsMatchIn(t)
        // «Добавь/прибавь/докинь … к таймеру» — продление; «добавь таймер» и «прибавь громкость» — нет.
        val addTo = rx("""^(?:добавь|прибавь|докинь)\b""").containsMatchIn(t) && t.contains("таймеру")
        if (!prolong && !addTo) return null
        val rest = t
            .replace(verb, " ")
            .replace(rx("""\bтаймер\p{L}*"""), " ")
            .replace(rx("""\b(?:на|к|ещё|еще|время|чуть|немного|пожалуйста)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val seconds = DevicePhrases.duration(rest) ?: return SkillCommand.TimersExtend(300)
        return seconds.takeIf { it in 1..86_400 }?.let { SkillCommand.TimersExtend(it) }
    }

    // ------------------------------------------------------------------ Места

    fun placeName(raw: String): String {
        val n = RuTokenizer.normalize(raw).trim(' ', ',', '.')
            .removePrefix("мой ").removePrefix("моя ").removePrefix("мое ").removePrefix("в ").removePrefix("на ").removePrefix("во ").removePrefix("к ")
        return when {
            rx("""^(?:дом|дома|домой|дому|из дома|из дому)$""").containsMatchIn(n) || n.startsWith("дом ") -> "дом"
            n.startsWith("работ") || n.startsWith("офис") -> "работа"
            n.startsWith("дач") -> "дача"
            n.startsWith("школ") -> "школа"
            n.startsWith("университет") || n.startsWith("универ") -> "университет"
            n.startsWith("спортзал") || n.startsWith("зал") || n.startsWith("фитнес") -> "спортзал"
            n.startsWith("магазин") -> "магазин"
            n.startsWith("садик") || n.startsWith("детск") -> "садик"
            else -> n.split(' ').take(2).joinToString(" ")
        }
    }

    fun places(t: String, original: String): SkillCommand? {
        if (rx("""^(?:какие|покажи|мои)\s+(?:у\s+меня\s+)?(?:напоминания\s+по\s+месту|места)|^какие\s+места\s+(?:ты\s+)?(?:знаешь|запомнила)""").containsMatchIn(t)) return SkillCommand.ListPlaces
        // Сохранить место: «запомни, я дома», «здесь моя работа, запомни», «запомни это место как дача».
        // Только короткие фразы целиком: «запомни, это мой пароль…» и «запомни, что я на работе до 7» — это память, а не место.
        val saveVerb = rx("""^(?:запомни|сохрани|отметь)\b|\b(?:запомни|сохрани|отметь)$""")
        if (saveVerb.containsMatchIn(t)) {
            val body = t.replace(rx("""^(?:запомни|сохрани|отметь)(?:\s+это)?\s*,?\s*(?:что\s+)?|\s*,?\s*(?:запомни|сохрани|отметь)$"""), "").trim(' ', ',')
            rx("""^я\s+(?:сейчас\s+)?(дома|на работе|на даче|в офисе)$""").find(body)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
            rx("""^(?:здесь|тут)\s+(?:находится\s+)?(?:мой|моя|мое|наш|наша|наше)\s+(\S+)$""").find(body)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
            rx("""^(?:это\s+)?(?:место\s+)?(?:мой|моя|мое|наш|наша|наше)\s+(дом|работа|дача|офис|школа|университет|универ|спортзал|садик)$""").find(body)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
            rx("""^(?:это\s+|здесь\s+|тут\s+)?место\s+как\s+(.+)$""").find(body)?.let { return SkillCommand.SavePlace(placeName(it.groupValues[1])) }
        }
        // Напоминание по месту.
        // Текст напоминания — с «ё», как сказано.
        val lo = original.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("""\s+"""), " ")
        val m = rx("""^(?:нова[,]?\s+)?напомни(?:\s+мне)?[,]?\s+(?:(.+?)[,]?\s+)?когда\s+(?:я\s+)?(буду|приду|приеду|вернусь|окажусь|доберусь|зайду|выйду|уйду|уеду|поеду)\s+(.+)$""").find(lo) ?: return null
        val before = m.groupValues[1].trim().trimEnd(',')
        val verb = m.groupValues[2]
        val rest = m.groupValues[3]
        val placeRx = rx("""^(домой|дома|из\s+дома|из\s+дому|с\s+работы|на\s+работу|на\s+работе|на\s+дачу|на\s+даче|в\s+\S+|во\s+\S+|на\s+\S+|к\s+\S+|из\s+\S+|с\s+\S+)(?:[,]?\s+(.*))?$""")
        val pm = placeRx.find(rest) ?: return null
        val place = placeName(pm.groupValues[1].replace(Regex("""^(?:из|с)\s+"""), ""))
        val after = pm.groupValues[2].trim().removePrefix("то ").removePrefix("напомни ").removePrefix("мне ").trim()
        val what = before.ifEmpty { after }.trim().trimEnd(',')
        if (what.isEmpty()) return null
        val onLeave = verb in setOf("выйду", "уйду", "уеду") || rx("""^(?:из|с)\s""").containsMatchIn(pm.groupValues[1])
        return SkillCommand.PlaceRemind(ai.nova.core.assistant.ActionTitle.clean(what).ifBlank { what }, place, onLeave)
    }

    // ------------------------------------------------------------------ Имя и город

    fun profile(t: String, original: String): SkillCommand? {
        if (rx("""^(?:а\s+)?(?:ты\s+знаешь\s+)?как\s+меня\s+зовут$|^кто\s+я$|^ты\s+помнишь\s+как\s+меня\s+зовут$""").containsMatchIn(t)) return SkillCommand.AskName
        val o = original.trim().trimEnd('.', '!', '?')
        Regex("""(?iu)^(?:(?:запомни[,]?\s+(?:что\s+)?)?(?:называй|зови)\s+меня|(?:запомни[,]?\s+(?:что\s+)?)?меня\s+зовут|мо[её]\s+имя)\s+[—-]?\s*(.+)$""").find(o)?.let { m ->
            val name = m.groupValues[1].trim().trim(',', '.', '«', '»', '"').split(Regex("""\s+""")).take(2).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            val firstWord = RuTokenizer.normalize(m.groupValues[1].trim()).substringBefore(' ')
            // «Меня зовут на день рождения к Маше» — приглашение, а не имя.
            val notName = firstWord in setOf("на", "в", "во", "к", "ко", "с", "со", "за", "по", "домой", "гулять", "играть", "туда", "сюда", "обратно", "замуж", "работать", "учиться")
            if (!notName && name.length in 2..30 && !rx("""\b(?:не|никак|ничего)\b""").containsMatchIn(RuTokenizer.normalize(name))) return SkillCommand.SetName(name)
        }
        Regex("""(?iu)^(?:запомни[,]?\s+(?:что\s+)?)?(?:я\s+живу|мы\s+живем|мы\s+живём|я\s+сейчас\s+живу)\s+(?:в|во)\s+(.+)$|^мой\s+город\s+[—-]?\s*(.+)$""").find(o)?.let { m ->
            val city = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trim(',', '.') ?: return null
            if (city.length in 2..40) return SkillCommand.SetCity(city.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
        }
        return null
    }

    // ------------------------------------------------------------------ Покупки (Ozon, Wildberries)

    /** «Что ты помнишь о покупках?» */
    private val SHOPPING_MEMORY = rx("""^(?:а\s+)?(?:что|чего)\s+ты\s+(?:помнишь|знаешь|запомнила|помнишь\s+про)\s+(?:про|о|об)?\s*(?:покупк\w*|магазин\w*|размер\w*|одежд\w*|обув\w*|бренд\w*|питомц\w*)""")

    /** «Забудь размер обуви», «удали из памяти покупок бренд». */
    private val SHOPPING_FORGET = rx("""^(?:забудь|удали|сотри|убери)\s+(?:из\s+памяти\s+)?(?:про\s+|о\s+)?(?:покупк\w*|размер\w*|обув\w*|одежд\w*|бренд\w*|питомц\w*|животн\w*)""")

    /**
     * Просьба о покупке. Слова «найди», «поищи», «посмотри» здесь нет намеренно: оно слишком широкое —
     * «найди маршрут», «найди аптеку», «найди видео» тоже начинаются с него. Магазин открывается на
     * покупку, а не на любой поиск.
     */
    private val PURCHASE_VERB = rx(
        """^(?:а\s+)?(?:нова,?\s*)?(?:купи|купить|куплю|покупаю|прикуп\w*|закаж\w*|заказать|подбер\w*|подобра\w*|""" +
            """выбер\w*|выбрать|хочу|хотел\w*|ищу|нужн\w*|надо|помоги\s+(?:выбрать|найти|купить))\b|""" +
            """\b(?:для\s+покупки|в\s+магазине|на\s+озоне|на\s+вайлдберриз|на\s+wildberries)""",
    )

    /** Признак товара без слова «купить»: размер, бюджет или рейтинг — их не называют про маршрут. */
    private val PRODUCT_MARKER = rx("""(?:размер\w*|р-р|бюджет\w*|не\s+дороже|до\s+\d|от\s+\d|рейтинг\w*)""")

    /** Продукты уходят в список покупок, если человек не сказал, что ищет их в магазине. */
    private val SHOP_IN_STORE = rx("""\b(?:для\s+покупки|в\s+магазине|в\s+супермаркете|на\s+озоне|на\s+вайлдберриз|на\s+wildberries)""")

    /** Прошедшее время покупки — расход, а не просьба найти товар. */
    private val PAST_PURCHASE = rx("""^(?:я\s+)?(?:купил|купила|купили|потратил|потратила|потратили|заплатил|заплатила|приобр[её]л|приобрела)\w*\b""")

    /** «Сколько стоят кеды». */
    private val PRICE_QUESTION = rx("""^сколько\s+сто(?:ят|ит)\b""")

    /** «… ли …» — вопрос о необходимости, а не заказ. */
    private val QUESTION_LI = rx("""(?:^|\s)ли(?:\s|$)""")

    /**
     * Что покупать не просят, даже если в словах есть «корм» или «телефон»: статья, песня, маршрут,
     * аптека, видео, часы работы. Список расширяется по мере находок — лучше переспросить, чем открыть магазин.
     */
    private val NOT_SHOPPING = rx(
        """стать\w*|рецепт\w*|справк\w*|информаци\w*|википеди\w*|песн\w*|текст\w*|аккорд\w*|фильм\w*|сериал\w*|""" +
            """мультфильм\w*|клип\w*|\bвидео\b|видеоролик\w*|\bролик\b|\bфото\b|картинк\w*|изображени\w*|""" +
            """новост\w*|погод\w*|курс\w*|пробк\w*|афиш\w*|маршрут\w*|билет\w*|расписани\w*|""" +
            """аптек\w*|банкомат\w*|заправк\w*|поликлиник\w*|больниц\w*|стоматолог\w*|врач\w*|""" +
            """такси\b|автобус\w*|электричк\w*|поезд\w*|самолет\w*|аэропорт\w*|вокзал\w*|""" +
            """отел\w*|гостиниц\w*|кафе\b|ресторан\w*|кофейн\w*|кино\b|театр\w*|концерт\w*|""" +
            """спектакл\w*|выставк\w*|экскурси\w*|музе[йя]\w*|адрес\w*|""" +
            """часы\s+работ\w*|время\s+работ\w*|режим\s+работ\w*|график\w*\s+(?:работ|прием|приёма)|""" +
            """инструкци\w*|характеристик\w*|отзыв\w*|сравнени\w*|обзор\w*|перевод\w*|контакт\w*|""" +
            """приложени\w*|узна\w*|расскаж\w*|объясни\w*|что\s+такое|кто\s+так\w*""")

    /**
     * Покупка на маркетплейсе: «хочу кеды puma 46 размер до 10 тысяч», «купи корм для неразлучников на озоне»,
     * «положи второй в корзину», «что ты помнишь о покупках».
     *
     * Магазин открывает просьба о покупке, а не слово «найди»: за ним стоят маршрут, аптека, песня и статья.
     * При этом обычные покупки из списка («купи хлеб») остаются списку покупок: продуктов без названия
     * площадки и явной просьбы искать в магазине мало.
     */
    fun shopping(t: String, original: String): SkillCommand? {
        if (SHOPPING_MEMORY.containsMatchIn(t)) return SkillCommand.ShoppingMemory(null)
        if (SHOPPING_FORGET.containsMatchIn(t)) return SkillCommand.ShoppingMemory(t)
        if (rx("""^(?:корзин\w*|в\s+корзину)\b|(?:положи|добавь|убери|удали)\s+(?:\S+\s+){0,3}в\s+корзин""").containsMatchIn(t)) {
            return SkillCommand.ShoppingCart(original)
        }
        // Просьба оплатить или оформить заказ — сюда, чтобы ответить отказом, а не молчать.
        if (rx("""(?:оплат\w*|оформ\w*|подтверд\w*)\s+(?:заказ\w*|покупк\w*|корзин\w*|товар\w*)""").containsMatchIn(t)) {
            return SkillCommand.ShoppingCart(original)
        }
        if (isFindPhone(t) || NOT_SHOPPING.containsMatchIn(t)) return null
        // «Купила кофе за 250» — это трата, её записывает Финансовый помощник, а не поиск товара.
        if (PAST_PURCHASE.containsMatchIn(t)) return null
        // «Запиши заметку размер обуви» — это запись: слово «размер» здесь не признак покупки.
        if (rx("""^(?:запиши|записать|запомни|напомни|создай|поставь|сохрани|добавь|отметь)\b""").containsMatchIn(t)) return null
        val marketplaces = ShoppingIntent.marketplaces(original)
        val category = ShoppingIntent.category(t)
        // «Нужна ли куртка?» — вопрос о погоде, а не о покупке.
        if (marketplaces == null && QUESTION_LI.containsMatchIn(t)) return null
        if (category == null) return null
        // «Сколько стоят кеды» — цена и есть повод искать товар; «кеды puma 46 размер до 10 тысяч» — тоже.
        val purchase = PURCHASE_VERB.containsMatchIn(t) || PRICE_QUESTION.containsMatchIn(t) ||
            PRODUCT_MARKER.containsMatchIn(t) || marketplaces != null
        if (!purchase) return null
        // Продукты ищут на площадке только по явной просьбе: «купи хлеб» — это список покупок.
        if (category == ShoppingCategory.FOOD && marketplaces == null && !SHOP_IN_STORE.containsMatchIn(t)) return null
        return SkillCommand.Shopping(ShoppingIntent.parse(original))
    }

    /** Число из фразы: «пять» → 5. */
    fun number(t: String): Int? = RuNumbers.mergeNumberWords(RuTokenizer.tokenize(t)).firstNotNullOfOrNull { it.number }?.toInt()
}
