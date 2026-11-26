package ai.nova.core.assistant

import ai.nova.core.nlp.RuNumbers
import ai.nova.core.nlp.RuQuery
import ai.nova.core.nlp.RuTokenizer
import ai.nova.core.nlp.Rx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import ai.nova.core.nlp.RuDateTimeParser
import kotlin.random.Random

/** Команды самому телефону. Выполняются платформой ([DeviceController]) без интернета. */
sealed interface DeviceCommand {
    data class Timer(val seconds: Int, val label: String = "") : DeviceCommand
    data class Alarm(val time: LocalTime, val label: String = "", val days: Set<DayOfWeek> = emptySet()) : DeviceCommand
    data class OpenApp(val name: String) : DeviceCommand
    data class Call(val who: String) : DeviceCommand
    data class Message(val who: String, val text: String) : DeviceCommand
    data class Flashlight(val on: Boolean) : DeviceCommand
    data object Battery : DeviceCommand
    data class Media(val action: MediaAction) : DeviceCommand
    data class Volume(val change: VolumeChange, val percent: Int? = null) : DeviceCommand
    data class WebSearch(val query: String) : DeviceCommand
    /** Маршрут; [app] — карты, выбранные голосом («в 2ГИС»), иначе те, что выбраны в настройках. */
    /**
     * Маршрут до места. [vague] — человек сказал только «поесть»/«перекусить», без вида места:
     * тогда сначала спросим, что именно нужно, а не повезём в случайное кафе.
     */
    /** [walking] — пешком: до ближайшей аптеки или кафе идут, до заправки едут. */
    data class Navigate(
        val destination: String,
        val app: MapsApp? = null,
        val vague: Boolean = false,
        val walking: Boolean = false,
    ) : DeviceCommand
    data class OpenSettings(val section: SettingsSection) : DeviceCommand
    data object Stopwatch : DeviceCommand
    data object ShowAlarms : DeviceCommand
    /** «Какие у меня будильники», «сколько включено»: свои будильники Нова знает точно. */
    data object AlarmsLeft : DeviceCommand
    /**
     * «Выключи будильник», «выключи будильник на 7», «выключи все будильники».
     * [delete] — человек сказал «удали»: будильник исчезает, а не просто выключается.
     */
    data class AlarmOff(val time: LocalTime? = null, val all: Boolean = false, val delete: Boolean = false) : DeviceCommand
    /** «Ещё 5 минут», «повтори через 30 минут» — перенос уже сработавшего будильника. */
    data class AlarmSnooze(val minutes: Int) : DeviceCommand
    /** «Стоп», «замолчи» — выключить звучащий сейчас сигнал (будильник или таймер). */
    data object Silence : DeviceCommand
    data class Camera(val video: Boolean = false, val selfie: Boolean = false) : DeviceCommand
    data class OpenUrl(val url: String) : DeviceCommand
    /** Музыка/видео по запросу: «включи Queen», «найди на ютубе рецепт пиццы». */
    data class Play(val query: String, val youtube: Boolean = false, val platform: VideoPlatform? = null) : DeviceCommand
    data class CalendarEvent(val title: String, val start: Instant?, val allDay: Boolean = false) : DeviceCommand
    data class AddContact(val name: String, val phone: String?) : DeviceCommand
    data class Share(val app: String, val text: String) : DeviceCommand
    data class DoNotDisturb(val on: Boolean) : DeviceCommand
    data class Brightness(val percent: Int?, val delta: Int = 0) : DeviceCommand
    /** Системные действия через спецвозможности: назад, домой, скриншот, блокировка… */
    data class Global(val action: GlobalAction) : DeviceCommand
    /** Дыхательное упражнение или медитация с голосовыми подсказками; STOP — прервать. */
    data class Relax(val kind: RelaxKind, val minutes: Int) : DeviceCommand
    /** «Я за рулём»: новые сообщения читаются вслух сами. */
    data class Driving(val on: Boolean) : DeviceCommand
    /** «Сделай резервную копию» / «восстановить из копии»: открывает экран копии (пароль вводится на экране, не голосом). */
    data object OpenBackup : DeviceCommand
    /** Помодоро: цикл «работа — отдых» таймерами; on = false останавливает. */
    data class Focus(val on: Boolean, val workMinutes: Int = 25, val restMinutes: Int = 5) : DeviceCommand
    /** Режим сна: каждый вечер в [from] включается «Не беспокоить», утром в [to] выключается; on = false отменяет. */
    data class SleepMode(val on: Boolean, val from: LocalTime? = null, val to: LocalTime? = null) : DeviceCommand
    /** Отдать файл (выгрузка в таблицу): приложение сохраняет его и открывает «Поделиться». */
    data class ShareFile(val name: String, val mime: String, val content: String) : DeviceCommand
    /** Голосовая заметка со звуком: запись голоса и текст. */
    data object VoiceMemo : DeviceCommand
    /** «Отмени последнее действие»: фонарик, «не беспокоить», яркость, громкость — то, что можно вернуть. */
    data object UndoDevice : DeviceCommand
    /** Парковка: [save] — запомнить место машины (и [note]: «третий уровень, место 45»), иначе — показать на карте. */
    data class Parking(val save: Boolean, val note: String = "") : DeviceCommand
}

enum class RelaxKind { BREATHING, MEDITATION, STOP }

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT, POWER_MENU, SPLIT_SCREEN }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }
enum class VolumeChange { UP, DOWN, MUTE, UNMUTE, MAX, SET }
enum class SettingsSection { WIFI, BLUETOOTH, SOUND, DISPLAY, BATTERY, LOCATION, APPS, MAIN, AIRPLANE, NFC, HOTSPOT, MOBILE_DATA, NOTIFICATIONS, SECURITY, ACCESSIBILITY, DATE_TIME, STORAGE }

data class DeviceResult(
    val text: String,
    val ok: Boolean = true,
    /** Ссылка на то, что открыли: остаётся в истории чата, чтобы можно было нажать ещё раз. */
    val link: String? = null,
)

/** Платформенная часть: Android выполняет команды через системные интенты и сервисы. */
interface DeviceController {
    suspend fun perform(command: DeviceCommand): DeviceResult

    /**
     * Ближайшие места по запросу вокруг текущего места телефона, от ближнего к дальнему.
     * Пусто — не нашли, нет сети или не известно, где телефон: тогда маршрут строим по названию.
     */
    suspend fun findPlaces(query: String, limit: Int = 6): List<PlaceCandidate> = emptyList()

    /** Маршрут до найденного места: карты строят путь от текущего места до точки. */
    fun routeTo(place: PlaceCandidate, app: MapsApp?): DeviceResult =
        DeviceResult("Не получилось построить маршрут.", ok = false)
}

object UnsupportedDevice : DeviceController {
    override suspend fun perform(command: DeviceCommand) = DeviceResult("На этом устройстве я так не умею.", ok = false)
}

/** Будильник, который знает сама Нова: время, дни повтора и ближайшее срабатывание. */
data class ActiveAlarm(
    val time: LocalTime,
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    /** Ближайшее срабатывание; null — выключен или уже прошёл. */
    val at: Instant? = null,
    val enabled: Boolean = true,
)

/**
 * Разбор команд телефону и «бытовых» вопросов полностью офлайн:
 * таймер, будильник, открыть приложение, позвонить, фонарик, заряд, музыка, громкость,
 * поиск в интернете, маршрут, настройки; сколько дней до даты, день недели, монетка, кубик, случайное число,
 * перевод единиц.
 */
object DevicePhrases {
    private fun re(p: String) = Rx.of(p)

    /** Числительные словами → цифры: «пять минут» → «5 минут», «полчаса» → «30 минут». */
    fun digitize(n: String): String {
        val prepared = n.replace(re("""\bполчаса\b"""), "30 минут").replace(re("""\bполминуты\b"""), "30 секунд")
            .replace(re("""\bполтора часа\b"""), "90 минут").replace(re("""\bчетверть часа\b"""), "15 минут")
        val tokens = RuNumbers.mergeNumberWords(RuTokenizer.tokenize(prepared))
        return tokens.joinToString(" ") { t ->
            t.number?.let { v -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() } ?: t.norm
        }
    }

    /** «Работаем 25 минут», «помодоро», «хватит работать»; «режим сна с 23 до 7», «выключи режим сна». */
    internal fun focus(t: String): DeviceCommand? {
        val d = digitize(t)
        if (re("""^(?:хватит\s+работать|останови\s+(?:помодоро|фокус|работу)|выключи\s+(?:помодоро|фокус|режим\s+фокуса)|отмени\s+(?:помодоро|фокус)|закончи\s+(?:помодоро|фокус)|хватит\s+помодоро)$""").containsMatchIn(d)) return DeviceCommand.Focus(false)
        if (re("""^(?:выключи|отключи|отмени|убери)\s+режим\s+сна$|^хватит\s+(?:спать\s+по\s+режиму|режима\s+сна)$""").containsMatchIn(d)) return DeviceCommand.SleepMode(false)
        re("""^(?:включи\s+|поставь\s+|давай\s+)?(?:режим\s+)?сна?\s+(?:с|от)\s+(\d{1,2})(?::(\d{2}))?\s*(утра|дня|вечера|ночи)?\s+(?:до|по)\s+(\d{1,2})(?::(\d{2}))?\s*(утра|дня|вечера|ночи)?$""").find(d)?.let { m ->
            return sleepRange(m)
        }
        re("""^(?:включи\s+|поставь\s+)?режим\s+сна\s+(?:с|от)\s+(\d{1,2})(?::(\d{2}))?\s*(утра|дня|вечера|ночи)?\s+(?:до|по)\s+(\d{1,2})(?::(\d{2}))?\s*(утра|дня|вечера|ночи)?$""").find(d)?.let { return sleepRange(it) }
        if (re("""^(?:включи\s+|поставь\s+)?режим\s+сна$""").containsMatchIn(d)) return DeviceCommand.SleepMode(true, LocalTime.of(23, 0), LocalTime.of(7, 0))
        val m = re("""^(?:давай\s+)?(?:включи\s+|запусти\s+|начни\s+)?(?:(?:работаем|поработаем|работать|сосредоточимся|фокус(?:ируемся)?)\s*(?:на\s+)?(\d{1,3})?\s*(?:минут\w*)?|помодоро|режим\s+фокуса|фокус)(?:\s*(?:на\s+)?(\d{1,3})\s*(?:минут\w*)?)?(?:[,\s]+(?:отдых|перерыв)\s*(\d{1,2})\s*(?:минут\w*)?)?$""").find(d) ?: return null
        val work = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: 25
        val rest = m.groupValues[3].toIntOrNull() ?: 5
        if (work !in 1..180 || rest !in 1..60) return null
        // «работаем» без числа и без слов про фокус — слишком общая фраза.
        if (!d.contains("помодоро") && !d.contains("фокус") && m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return null
        return DeviceCommand.Focus(true, work, rest)
    }

    /** «Запомни, где я припарковалась», «я припарковался на третьем уровне», «где моя машина». */
    internal fun parking(t: String): DeviceCommand.Parking? {
        if (re("""^(?:а\s+)?(?:где\s+(?:же\s+)?(?:моя\s+|наша\s+)?(?:машина|машинка|тачка|авто|автомобиль)|где\s+я\s+(?:оставил|оставила|поставил|поставила|бросил|бросила)\s+(?:свою\s+)?(?:машину|тачку|авто|автомобиль)|где\s+я\s+(?:припарковал\w*|встал[аи]?\s+на\s+парковке)|(?:найди|покажи)\s+(?:мою\s+|где\s+)?(?:машину|тачку|парковку|где\s+машина)|(?:веди|проведи|отведи)\s+(?:меня\s+)?к\s+машине|как\s+(?:дойти|пройти|вернуться)\s+(?:до|к)\s+машин\w*)$""").containsMatchIn(t)) {
            return DeviceCommand.Parking(save = false)
        }
        val m = re("""^(?:запомни\s*,?\s*(?:где|что)\s+)?(?:я\s+)?(?:припарковал(?:ся|ась|ись|а|и)?|запарковал(?:ся|ась|ись|а|и)?|оставил\s+машину|оставила\s+машину|поставил\s+машину|поставила\s+машину)(.*)$""").find(t)
            ?: re("""^(?:запомни|сохрани|отметь)\s+(?:парковку|место\s+(?:парковки|машины)|где\s+(?:стоит\s+)?(?:моя\s+)?машина|где\s+машина)(.*)$""").find(t)
            ?: return null
        val note = m.groupValues[1].trim().trim(',', ':', '.', ' ')
        // «Я поставила машину на ремонт / в сервис» — не парковка.
        if (re("""ремонт|сервис|продаж|учет|учёт""").containsMatchIn(note)) return null
        return DeviceCommand.Parking(save = true, note = note)
    }

    private fun sleepRange(m: MatchResult): DeviceCommand.SleepMode? {
        fun hour(h: String, marker: String, isStart: Boolean, other: Int): Int? {
            var x = h.toIntOrNull() ?: return null
            if (x > 23) return null
            when (marker) {
                "вечера" -> if (x < 12) x += 12
                "дня" -> if (x < 6) x += 12
                "ночи" -> if (x == 12) x = 0
                "утра" -> if (x == 12) x = 0
                else -> if (isStart && x in 6..11 && other < x) x += 12
            }
            return x
        }
        val endRaw = m.groupValues[4].toIntOrNull() ?: return null
        val from = hour(m.groupValues[1], m.groupValues[3], true, endRaw) ?: return null
        val to = hour(m.groupValues[4], m.groupValues[6], false, from) ?: return null
        val fromMin = m.groupValues[2].toIntOrNull() ?: 0
        val toMin = m.groupValues[5].toIntOrNull() ?: 0
        if (fromMin > 59 || toMin > 59) return null
        val a = LocalTime.of(from, fromMin); val b = LocalTime.of(to, toMin)
        if (a == b) return null
        return DeviceCommand.SleepMode(true, a, b)
    }

    /** Длительность: «5 минут», «1 час 20 минут», «90 секунд», «минуту». */
    fun duration(text: String): Int? {
        val d = digitize(text)
        var total = 0
        var found = false
        re("""(\d+(?:\.\d+)?)\s*(час|часа|часов|ч)\b""").find(d)?.let { total += (it.groupValues[1].toDouble() * 3600).toInt(); found = true }
        re("""(\d+)\s*(минут|минуты|минуту|мин)\b""").find(d)?.let { total += it.groupValues[1].toInt() * 60; found = true }
        re("""(\d+)\s*(секунд|секунды|секунду|сек)\b""").find(d)?.let { total += it.groupValues[1].toInt(); found = true }
        if (!found) {
            when {
                re("""\bминуту\b""").containsMatchIn(d) -> { total = 60; found = true }
                re("""\bчас\b""").containsMatchIn(d) -> { total = 3600; found = true }
            }
        }
        return total.takeIf { found && it > 0 }
    }

    /** «на пасту 8 минут» → «паста»: название таймера — слова, кроме длительности. */
    fun timerLabel(text: String): String {
        val rest = digitize(text)
            .replace(re("""\d+(?:\.\d+)?\s*(?:часов|часа|час|ч|минут|минуты|минуту|мин|секунд|секунды|секунду|сек)\b"""), " ")
            .replace(re("""\b(?:минуту|час|на|для|и|по|через)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (rest.length < 3) return ""
        val words = rest.split(' ').take(3)
        // «забрать сестру» — после глагола винительный падеж оставляем как есть.
        if (words.any { it.length > 3 && (it.endsWith("ть") || it.endsWith("ти")) }) return words.joinToString(" ")
        // «пасту» → «паста», «гречневую кашу» → «гречневая каша», «яйца» — как есть.
        return words.joinToString(" ") { w ->
            when {
                w.endsWith("ую") && w.length > 4 -> w.dropLast(2) + "ая"
                w.endsWith("юю") && w.length > 4 -> w.dropLast(2) + "яя"
                w.endsWith("у") && w.length > 3 -> w.dropLast(1) + "а"
                w.endsWith("ю") && w.length > 3 && !w.endsWith("ию") -> w.dropLast(1) + "я"
                else -> w
            }
        }
    }

    fun parse(n: String, now: Instant, zone: ZoneId): AssistantAction.Device? {
        fun cmd(c: DeviceCommand) = AssistantAction.Device(c)
        val t = n.trim().trimEnd('.', '!', '?')

        // Таймер: «поставь таймер на 5 минут», «засеки 10 минут», «таймер полчаса».
        re("""^(?:поставь|заведи|запусти|включи|установи)?\s*(?:таймер|засеки|отсчитай)\s*(?:на\s+)?(.+)$""").find(t)?.let { m ->
            duration(m.groupValues[1])?.let { return cmd(DeviceCommand.Timer(it, timerLabel(m.groupValues[1]))) }
        }
        if (re("""^(?:включи|запусти)\s+секундомер""").containsMatchIn(t) || t == "секундомер") return cmd(DeviceCommand.Stopwatch)

        // «Ещё 5 минут», «повтори через 30 минут», «отложи будильник на 10 минут» — перенос сработавшего.
        alarmSnooze(t)?.let { return cmd(it) }

        // Будильник: «разбуди меня в 7», «поставь будильник на 6:30», «будильник на 8 утра по будням».
        re("""^(?:разбуди(?:\s+меня)?|поставь будильник|заведи будильник|установи будильник|будильник|подними меня)\s*(?:на|в|к)?\s*(.+)$""").find(t)?.let { m ->
            alarmTime(m.groupValues[1])?.let { (time, days) -> return cmd(DeviceCommand.Alarm(time, "", days)) }
        }
        // «Какие у меня будильники», «сколько включено», «мои будильники».
        if (re("""^(?:какие|сколько|мои|покажи|список|перечисли|есть)\b.*\bбудильник|^будильники$""").containsMatchIn(t)) return cmd(DeviceCommand.AlarmsLeft)
        // «Открой будильники в часах» — открыть приложение «Часы».
        if (re("""^(?:открой|запусти|покажи)\s+(?:приложение\s+)?(?:будильники|часы)""").containsMatchIn(t)) return cmd(DeviceCommand.ShowAlarms)
        alarmOff(t)?.let { return cmd(it) }
        // «Стоп», «замолчи», «выключи сигнал» — просто прекратить звон, без разбора, чей он.
        if (re("""^(?:стоп|стоп\s+сигнал|выключи\s+сигнал|останови\s+сигнал|замолчи(?:те)?(?:\s+пожалуйста)?|прекрати\s+звонить|отбой)$""").containsMatchIn(t)) return cmd(DeviceCommand.Silence)

        // Системные действия (через спецвозможности).
        val global = when {
            re("""^(?:назад|вернись назад|шаг назад)$""").containsMatchIn(t) -> GlobalAction.BACK
            re("""^(?:домой|на главный экран|главный экран|сверни всё|сверни все|на рабочий стол)$""").containsMatchIn(t) -> GlobalAction.HOME
            re("""^(?:(?:покажи|открой)\s+)?(?:недавние|последние)\s+приложения$|^переключи приложение$""").containsMatchIn(t) -> GlobalAction.RECENTS
            re("""^(?:открой|покажи|опусти)\s+(?:шторку|уведомления)$""").containsMatchIn(t) -> GlobalAction.NOTIFICATIONS
            re("""^(?:открой|покажи)\s+быстрые настройки$""").containsMatchIn(t) -> GlobalAction.QUICK_SETTINGS
            re("""^(?:заблокируй|выключи)\s+(?:экран|телефон)$|^заблокируй$""").containsMatchIn(t) -> GlobalAction.LOCK
            re("""^(?:сделай|сними)\s+(?:скриншот|снимок экрана)|^скриншот$""").containsMatchIn(t) -> GlobalAction.SCREENSHOT
            re("""^(?:меню|кнопки)\s+питания$|^(?:выключи|перезагрузи)\s+телефон$""").containsMatchIn(t) -> GlobalAction.POWER_MENU
            re("""^(?:раздели экран|разделённый экран|разделенный экран|два окна)$""").containsMatchIn(t) -> GlobalAction.SPLIT_SCREEN
            else -> null
        }
        if (global != null) return cmd(DeviceCommand.Global(global))

        // Камера.
        when {
            re("""^(?:сделай|сними)\s+селфи|^(?:открой|включи)\s+(?:фронтальную|переднюю|селфи)\s*камеру""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera(selfie = true))
            re("""^(?:сделай|сними)\s+(?:фото|фотографию|снимок)|^(?:открой|включи|запусти)\s+камеру$|^сфотографируй|^сфоткай""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera())
            re("""^(?:отмени|верни)\s+(?:свое\s+|своё\s+)?(?:последн\w+\s+)?(?:действие|команду|как\s+было)$|^верни\s+как\s+было$|^отмени\s+что\s+(?:ты\s+)?сделал\w*$""").containsMatchIn(t) -> return cmd(DeviceCommand.UndoDevice)
            re("""^(?:сними|запиши)\s+видео|^(?:включи|начни)\s+(?:запись видео|видеозапись)""").containsMatchIn(t) -> return cmd(DeviceCommand.Camera(video = true))
        }

        // Не беспокоить, яркость.
        re("""^(?:включи|активируй)\s+(?:режим\s+)?не беспокоить|^(?:режим\s+)?не беспокоить$""").find(t)?.let { return cmd(DeviceCommand.DoNotDisturb(true)) }
        re("""^(?:выключи|отключи)\s+(?:режим\s+)?не беспокоить""").find(t)?.let { return cmd(DeviceCommand.DoNotDisturb(false)) }
        re("""^(?:сделай\s+)?яркость\s+(?:на\s+)?(\d+)""").find(digitize(t))?.let { return cmd(DeviceCommand.Brightness(it.groupValues[1].toInt().coerceIn(0, 100))) }
        when {
            re("""^(?:сделай\s+)?(?:экран\s+)?(?:ярче|поярче|прибавь яркость|увеличь яркость)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(null, 20))
            re("""^(?:сделай\s+)?(?:экран\s+)?(?:темнее|потемнее|убавь яркость|уменьши яркость)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(null, -20))
            re("""^(?:максимальная яркость|яркость на максимум)""").containsMatchIn(t) -> return cmd(DeviceCommand.Brightness(100))
        }

        // Сайты: «открой сайт habr.com», «открой youtube.com».
        re("""^(?:открой|зайди на|перейди на)\s+(?:сайт\s+)?([a-z0-9][a-z0-9.-]*\.[a-z]{2,}(?:/\S*)?)$""").find(t)?.let { m ->
            val u = m.groupValues[1]
            return cmd(DeviceCommand.OpenUrl(if (u.startsWith("http")) u else "https://$u"))
        }

        // Музыка и видео по запросу.
        re("""^(?:найди|включи|открой|покажи|поставь)\s+(?:на\s+)?(?:ютубе|youtube)\s+(.+)$|^(?:включи|найди|покажи)\s+(.+?)\s+(?:на\s+)?(?:ютубе|youtube)$""").find(t)?.let { m ->
            val q = RuQuery.toSearchForm(m.groupValues[1].ifBlank { m.groupValues[2] }.trim())
            if (q.isNotEmpty()) return cmd(DeviceCommand.Play(q, youtube = true))
        }
        // «Найди видео про рыбалку», «покажи ролик про котиков» — поиск видео на YouTube.
        re("""^(?:найди|поищи|покажи|включи|открой)\s+(?:мне\s+)?(?:видео|видос\w*|ролик\w*|клип\w*)\s+(?:про|о|об|на\s+тему)?\s*(.+)$""").find(t)?.let { m ->
            // «про рыбалку» → «рыбалка»: в поиске YouTube словарная форма точнее.
            val q = RuQuery.toSearchForm(m.groupValues[1].trim())
            if (q.isNotEmpty()) return cmd(DeviceCommand.Play(q, youtube = true))
        }
        re("""^(?:включи|поставь|сыграй|воспроизведи|вруби|врубай|запусти)\s+(?:песню|песенку|трек|музыку|музычку|музон|альбом|плейлист|группу|исполнителя)\s+(.+)$""").find(t)?.let {
            // «вруби музыку группы Кино» → «Кино».
            val q = it.groupValues[1].trim().replace(re("""^(?:группы|исполнителя|певца|певицы|рэпера)\s+"""), "")
            if (q.isNotEmpty()) return cmd(DeviceCommand.Play(q))
        }

        // Календарь: «добавь в календарь встречу с Машей завтра в 15:00».
        re("""^(?:добавь|запиши|создай|поставь)\s+(?:в\s+календарь|событие(?:\s+в\s+календарь)?|встречу\s+в\s+календарь)\s+(.+)$""").find(t)?.let { m ->
            val today = now.atZone(zone).toLocalDate()
            val dt = RuDateTimeParser()
            val parsed = dt.parse(m.groupValues[1], today)
            val title = parsed.remainder.ifBlank { m.groupValues[1] }.trim().replaceFirstChar { it.uppercase() }
            val start = if (parsed.spec.isEmpty) null else dt.resolveTrigger(parsed.spec, now, zone)
            return cmd(DeviceCommand.CalendarEvent(title, start, allDay = parsed.spec.time == null && parsed.spec.date != null))
        }

        // Новый контакт: «добавь контакт Саша 8 900 123 45 67».
        re("""^(?:добавь|создай|сохрани)\s+(?:новый\s+)?контакт\s+(.+)$""").find(t)?.let { m ->
            val rest = m.groupValues[1]
            val phone = re("""(\+?[\d][\d\s()-]{5,}\d)""").find(rest)?.value?.filter { it.isDigit() || it == '+' }
            val name = rest.replace(re("""(?:\s+(?:номер|телефон))?\s*\+?[\d][\d\s()-]{5,}\d"""), "").trim().replaceFirstChar { it.uppercase() }
            if (name.isNotEmpty()) return cmd(DeviceCommand.AddContact(name, phone))
        }

        // Поделиться текстом в приложении: «отправь в телеграм привет всем».
        re("""^(?:отправь|перешли|поделись)\s+в\s+(телеграм\w*|ватсап\w*|вотсап\w*|whatsapp|telegram|вк|вконтакте|viber|вайбер|почту|gmail)\s+(.+)$""").find(t)?.let {
            return cmd(DeviceCommand.Share(it.groupValues[1], it.groupValues[2].trim()))
        }

        // Резервная копия: сам экран, где вводится пароль (голосом пароль не диктуют).
        if (re("""^(?:(?:сделай|создай|сохрани|сделать|создать|сохранить)\s+(?:мне\s+)?(?:резервную\s+копию(?:\s+(?:данных|записей|всего))?|бэкап|бекап|копию\s+(?:данных|записей|всего))|резервн\w+\s+копи\w+|бэкап|бекап|(?:восстанови|восстановить|верни)\s+(?:данные|записи|всё|все)?\s*(?:из\s+(?:резервной\s+)?копии|из\s+бэкапа)|как\s+(?:перенести|перекинуть)\s+(?:данные|записи|всё|все)\s+на\s+нов\w+\s+телефон|перенос\s+на\s+нов\w+\s+телефон)$""").containsMatchIn(t)) {
            return cmd(DeviceCommand.OpenBackup)
        }


        // Фокус (помодоро) и режим сна.
        focus(t)?.let { return cmd(it) }
        parking(t)?.let { return cmd(it) }
        // Голосовая заметка со звуком (не диктовка текстом): «запиши голосовую заметку», «включи диктофон».
        if (re("""^(?:запиши|записать|сделай|создай|новая|включи|запусти|открой)?\s*(?:мне\s+)?(?:голосов\w+\s+(?:заметк\w*|запис\w*|сообщени\w*)|аудио\s*(?:заметк\w*|запис\w*)|аудиозаметк\w*|аудиозапис\w*|диктофон|запись\s+голоса)$|^запиши\s+(?:мой\s+)?голос$""").containsMatchIn(t)) return cmd(DeviceCommand.VoiceMemo)

        // «Я за рулём» — сообщения вслух.
        if (re("""^(?:я\s+)?(?:сейчас\s+)?(?:за рул[её]м|веду машину|еду на машине|в машине|сажусь за руль|поехала|поехал)$|^(?:включи\s+)?режим\s+(?:вождения|за рул[её]м|водителя|автомобиля)$|^(?:включи\s+)?автомобильный режим$""").containsMatchIn(t)) {
            return cmd(DeviceCommand.Driving(true))
        }
        if (re("""^(?:я\s+)?(?:приехал[аи]?|припарковал(?:ся|ась)|уже не за рул[её]м|вышел из машины|вышла из машины)$|^(?:выключи|отключи)\s+(?:режим\s+)?(?:вождения|за рул[её]м|водителя|автомобильный режим)$""").containsMatchIn(t)) {
            return cmd(DeviceCommand.Driving(false))
        }

        // Фонарик.
        if (re("""^(?:включи|зажги|вруби)\s+(?:фонарик|фонарь|вспышку|свет на телефоне)|^(?:фонарик|фонарь|посвети(?:\s+мне)?)$|^(?:фонарик|фонарь)\s+(?:включи|вруби|зажги)$""").containsMatchIn(t)) return cmd(DeviceCommand.Flashlight(true))
        if (re("""^(?:выключи|погаси|выруби)\s+(?:фонарик|фонарь|вспышку)""").containsMatchIn(t)) return cmd(DeviceCommand.Flashlight(false))

        // Заряд.
        if (re("""(сколько|какой|какой у меня|проверь)\s+(?:процентов\s+)?(заряд|зарядк|батаре|аккумулятор)|^заряд\s+(?:батареи|телефона|аккумулятора)|^сколько процентов""").containsMatchIn(t)) {
            return cmd(DeviceCommand.Battery)
        }

        // Музыка.
        when {
            re("""^(?:следующ\w*\s+(?:трек|песн\w*|композици\w*)|переключи\s+(?:трек|песню)|следующ(?:ую|ий|ая)$|дальше$|переключи$|другую песню)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.NEXT))
            re("""^(?:предыдущ\w*\s+(?:трек|песн\w*|композици\w*)|верни\s+(?:трек|песню|предыдущ\w*)|предыдущ(?:ую|ий|ая)$)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PREVIOUS))
            re("""^(?:пауза|поставь на паузу|останови музыку|выключи музыку|стоп музыка)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PAUSE))
            re("""^(?:включи музыку|продолжи музыку|играй|воспроизведи|сними с паузы|продолжи воспроизведение|(?:вруби|поставь|запусти|врубай)\s+(?:музыку|музон|музычку|песенку)$|продолжи$|продолжай$)""").containsMatchIn(t) -> return cmd(DeviceCommand.Media(MediaAction.PLAY))
        }

        // Громкость.
        re("""^(?:сделай\s+)?(?:громкость|звук)\s+(?:на\s+)?(\d+)\s*(?:%|процент\w*)?$""").find(digitize(t))?.let {
            return cmd(DeviceCommand.Volume(VolumeChange.SET, it.groupValues[1].toInt().coerceIn(0, 100)))
        }
        when {
            re("""^(?:сделай\s+)?(?:погромче|громче|прибавь\s+(?:звук|громкость)|увеличь\s+(?:звук|громкость))""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.UP))
            re("""^(?:сделай\s+)?(?:потише|тише|убавь\s+(?:звук|громкость)|уменьши\s+(?:звук|громкость))""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.DOWN))
            re("""^(?:выключи звук|без звука|беззвучн\w*\s*режим|включи беззвучн\w*|отключи звук)""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.MUTE))
            re("""^(?:включи звук|верни звук)$""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.UNMUTE))
            re("""^(?:громкость на максимум|максимальн\w* громкость|на полную)""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.MAX))
            re("""^(?:громкость на минимум|минимальн\w* громкость|(?:звук|громкость) на минимум)""").containsMatchIn(t) -> return cmd(DeviceCommand.Volume(VolumeChange.SET, 10))
        }

        // Настройки телефона.
        re("""^(?:открой|включи|выключи|покажи)\s+(?:настройки\s+)?(wi[- ]?fi|вай[- ]?фай|блютуз|блю ту[зс]|bluetooth|звук|экран|яркость|батаре\w*|геолокаци\w*|местоположени\w*|gps|приложения|настройки|режим полета|режим полёта|авиарежим|nfc|нфс|точку доступа|модем|мобильный интернет|мобильные данные|уведомлени\w*|безопасность|спецвозможности|специальные возможности|дату и время|память|хранилище)$""").find(t)?.let { m ->
            val s = m.groupValues[1]
            val section = when {
                s.startsWith("режим пол") || s == "авиарежим" -> SettingsSection.AIRPLANE
                s == "nfc" || s == "нфс" -> SettingsSection.NFC
                s == "точку доступа" || s == "модем" -> SettingsSection.HOTSPOT
                s.startsWith("мобильн") -> SettingsSection.MOBILE_DATA
                s.startsWith("уведомлени") -> SettingsSection.NOTIFICATIONS
                s == "безопасность" -> SettingsSection.SECURITY
                s.contains("возможности") -> SettingsSection.ACCESSIBILITY
                s == "дату и время" -> SettingsSection.DATE_TIME
                s == "память" || s == "хранилище" -> SettingsSection.STORAGE
                s.contains("fi") || s.contains("фай") -> SettingsSection.WIFI
                s.contains("блю") || s.contains("bluetooth") -> SettingsSection.BLUETOOTH
                s == "звук" -> SettingsSection.SOUND
                s == "экран" || s == "яркость" -> SettingsSection.DISPLAY
                s.startsWith("батаре") -> SettingsSection.BATTERY
                s.startsWith("гео") || s.startsWith("мест") || s == "gps" -> SettingsSection.LOCATION
                s == "приложения" -> SettingsSection.APPS
                else -> SettingsSection.MAIN
            }
            // «включи звук» уже разобран выше; здесь — именно экран настроек.
            return cmd(DeviceCommand.OpenSettings(section))
        }

        // Звонок и сообщение: «позвони маме», «набери 8 900 …», «напиши Саше что я задержусь».
        re("""^(?:позвони|набери|вызови|звонок|звякни|брякни|сделай звонок|соедини с)\s+(?:на\s+номер\s+|номер\s+)?(.+)$""").find(t)?.let {
            // «соедини с интернетом» — не звонок.
            val who = it.groupValues[1].trim()
            if (!re("""^(?:интернет|вай|wi|сет|блют|bluetooth|сервер|компьютер|ноутбук|телевизор|колонк|наушник)""").containsMatchIn(who)) {
                // «Вызови скорую», «позвони в полицию» — сразу нужный номер, а не поиск в контактах.
                return cmd(DeviceCommand.Call(emergencyNumber(who) ?: who))
            }
        }
        re("""^(?:напиши|черкни|(?:отправь|скинь|пошли)\s+(?:смс|сообщение|эсэмэску|смску))\s+(\S+(?:\s+\S+)?)\s*(?:,|что|:)\s*(.+)$""").find(t)?.let {
            val who = it.groupValues[1].trim()
            // «напиши заметку: …» — это запись, а не сообщение человеку.
            if (!re("""^(?:заметк|иде|задач|список|в\s|себе|мне)""").containsMatchIn(who)) {
                return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
            }
        }
        // «напиши Саше привет» — без запятой, как пишет распознавание речи. Кому — слово в дательном падеже.
        // «отправь маме сообщение перезвоню позже»
        re("""^(?:отправь|напиши|скинь|пошли)\s+(\S+[еуюиам])\s+(?:смс|сообщение|смску|эсэмэску)[,:]?\s*(?:что\s+)?(.+)$""").find(t)?.let {
            val who = it.groupValues[1]
            if (isRecipient(who)) return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
        }
        re("""^(?:напиши|черкни|(?:отправь|скинь|пошли)\s+(?:смс|сообщение|смску|эсэмэску))\s+(\S+[еуюиам])\s+(.+)$""").find(t)?.let {
            val who = it.groupValues[1]
            if (isRecipient(who) && !re("""^(?:заметк|иде|задач|список|списк|себе|мне|письм|текст|сообщени|смс|отзыв|пост|стих|сочинени|код)""").containsMatchIn(who)) {
                return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
            }
        }

        // «Скажи маме, что я приеду вечером» — сообщение маме (не «скажи мне»: это вопрос к Нова).
        re("""^(?:скажи|передай|сообщи)\s+(\S+[еуюиам]),?\s+(?:что|чтобы)\s+(.+)$""").find(t)?.let {
            val who = it.groupValues[1]
            if (isRecipient(who) && !re("""^(?:мне|нам|тебе|себе|ей|ему|им|всем)$""").matches(who)) return cmd(DeviceCommand.Message(who, it.groupValues[2].trim()))
        }

        // Маршрут: «построй маршрут до вокзала», «как доехать до дома в 2ГИС», «отвези домой», «ближайшая аптека».
        route(t)?.let { return cmd(it) }

        // Поиск в интернете: «найди в интернете …», «загугли …».
        re("""^(?:найди|поищи|посмотри)\s+в\s+(?:интернете|гугле|яндексе|сети)\s+(.+)$|^(?:загугли|погугли)\s+(.+)$""").find(t)?.let { m ->
            val q = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (q.isNotEmpty()) return cmd(DeviceCommand.WebSearch(q))
        }

        // Погода, курсы валют, новости, пробки — нужны свежие данные: открываем поиск.
        if (re("""^(?:какая|какой|что с|что по)\s+(?:сегодня\s+|завтра\s+|сейчас\s+)?(?:погод|курс|пробк)|^погода\b|^курс\s+(?:доллар|евро|юан|рубл|биткоин)|^(?:какие|последние)\s+новости|^новости$|^(?:будет ли|пойд[её]т ли)\s+(?:сегодня\s+|завтра\s+)?(?:дождь|снег)""").containsMatchIn(t)) {
            return cmd(DeviceCommand.WebSearch(t))
        }

        // Открыть приложение: «открой телеграм», «запусти камеру». Разделы самого ассистента — не приложения.
        re("""^(?:открой|запусти|включи)\s+(?:приложение\s+)?(.+)$""").find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            // «открой телеграм и напиши Саше» — это две команды: имя приложения не может содержать союз.
            if (re("""\s(?:и|а|потом|затем|но)\s|,""").containsMatchIn(name)) return null
            if (name.isNotEmpty() && name !in OWN_SECTIONS && !re("""^(?:заметк|иде|задач|расход|напоминани|список|памят|музык)""").containsMatchIn(name)) {
                return cmd(DeviceCommand.OpenApp(name))
            }
        }
        return null
    }

    private val NOT_RECIPIENTS = setOf("мне", "нам", "все", "всем", "что", "про", "это", "по", "еще", "ещё", "одно", "одну", "такое", "там", "тут", "сейчас", "срочно", "быстро", "потом", "завтра")

    /** «напиши красивое сообщение», «отправь еще сообщение» — прилагательные и наречия не адресаты. */
    /** Номера экстренных служб: «скорая» → 103, «полиция» → 102, «пожарные» → 101, «спасатели» → 112. */
    fun emergencyNumber(who: String): String? {
        val n = who.lowercase()
        return when {
            Regex("""(?:скор|неотлож|врач|доктор|фельдшер|реанимац)""").containsMatchIn(n) -> "103"
            Regex("""(?:полици|милици|мент)""").containsMatchIn(n) -> "102"
            Regex("""(?:пожар)""").containsMatchIn(n) -> "101"
            Regex("""(?:спасател|спасени|мчс|чрезвычайн|112)""").containsMatchIn(n) -> "112"
            else -> null
        }
    }

    private fun isRecipient(who: String): Boolean =
        who.lowercase() !in NOT_RECIPIENTS && !Regex("""(?:ое|ее|ие|ые)$""").containsMatchIn(who.lowercase())

    private val OWN_SECTIONS = setOf("заметки", "идеи", "задачи", "расходы", "напоминания", "историю", "память", "настройки ассистента")

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    /** «7», «6:30», «8 утра», «7 вечера», «половину восьмого» → время и дни повтора. */
    fun alarmTime(text: String): Pair<LocalTime, Set<DayOfWeek>>? {
        val d = digitize(text)
        val days = when {
            re("""по будням|в будни|каждый будний""").containsMatchIn(d) -> WEEKDAYS
            re("""каждый день|ежедневно""").containsMatchIn(d) -> DayOfWeek.entries.toSet()
            re("""по выходным|каждый выходной|на выходных""").containsMatchIn(d) -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else -> alarmDays(d)
        }
        re("""\bполовин\w*\s+(\S+)""").find(d)?.let { m ->
            val next = m.groupValues[1].toIntOrNull() ?: ORDINAL_HOURS.entries.firstOrNull { m.groupValues[1].startsWith(it.key) }?.value ?: return@let
            val h = (next - 1).let { if (it == 0) 12 else it }
            return LocalTime.of(adjust(h, d) % 24, 30) to days
        }
        val m = re("""(\d{1,2})(?:[:. ](\d{2}))?""").find(d) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        return LocalTime.of(adjust(h, d) % 24, min) to days
    }

    /**
     * «Будильник на воскресенье» без времени: дни есть, часа нет.
     * Тогда Нова спросит «во сколько?», а не промолчит.
     */
    fun alarmWithoutTime(t: String): Set<DayOfWeek>? {
        val m = re("""^(?:разбуди(?:\s+меня)?|поставь будильник|заведи будильник|установи будильник|будильник|подними меня)\s*(?:на|в|к)?\s*(.+)$""").find(t) ?: return null
        val tail = m.groupValues[1]
        if (alarmTime(tail) != null) return null
        return alarmDays(tail).takeIf { it.isNotEmpty() }
    }

    /** «выключи будильник», «выключи будильник на 7», «убери все будильники». */
    private fun alarmOff(t: String): DeviceCommand.AlarmOff? {
        val m = re("""^(выключи|отключи|удали|отмени|убери|сними|стоп)\s+(?:мне\s+)?(?:(все|всех|всё)\s+)?будильник\p{L}*(?:\s+(?:на|в|к)?\s*(.+))?$""").find(t) ?: return null
        val all = m.groupValues[2].isNotEmpty()
        val delete = m.groupValues[1].startsWith("удал")
        val tail = m.groupValues[3].trim()
        if (tail.isEmpty()) return DeviceCommand.AlarmOff(all = all, delete = delete)
        return DeviceCommand.AlarmOff(alarmTime(tail)?.first, all, delete)
    }

    /**
     * «Ещё 5 минут», «дай поспать ещё 10», «повтори через 30 минут», «продли будильник на 10 минут».
     *
     * «Отложи 10 минут» без слова «будильник» оставляем переносу напоминания — это частая фраза про запись.
     */
    private fun alarmSnooze(t: String): DeviceCommand.AlarmSnooze? {
        val d = digitize(t)
        val withAlarm = re("""^(?:продли|отложи|перенеси|добавь|повтори)\s+(?:ещё\s+)?будильник\p{L}*(?:\s+(?:на|через)\s*)?(?:(\d{1,3})\s*(?:минут\p{L}*|мин))?$""").find(d)
        if (withAlarm != null) {
            val minutes = withAlarm.groupValues[1].toIntOrNull() ?: 5
            return minutes.takeIf { it in 1..180 }?.let { DeviceCommand.AlarmSnooze(it) }
        }
        val m = re(
            """^(?:(?:ещ[её]|дай|хочу|посплю)|дай\s+\p{L}+)\b[^\d]{0,18}(\d{1,3})\s*(?:минут\p{L}*|мин)\b(?:\s+ещ[её])?$""" +
                """|^повтори\s+через\s+(\d{1,3})\s*(?:минут\p{L}*|мин)$""",
        ).find(d) ?: return null
        val minutes = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: return null
        return minutes.takeIf { it in 1..180 }?.let { DeviceCommand.AlarmSnooze(it) }
    }

    /**
     * Дни недели из фразы: «в воскресенье», «по вторникам», «с понедельника по пятницу».
     * Пустой набор — будильник одноразовый.
     */
    fun alarmDays(text: String): Set<DayOfWeek> {
        val d = digitize(text).lowercase().replace('ё', 'е')
        val range = re("""(?:^|\s)с\s+([\p{L}]+)\s+по\s+([\p{L}]+)""").find(d)
        if (range != null) {
            val from = dayOf(range.groupValues[1])
            val to = dayOf(range.groupValues[2])
            if (from != null && to != null) {
                val out = linkedSetOf<DayOfWeek>()
                var day: DayOfWeek = from
                // Понедельник → пятница; воскресенье → среда тоже должно закончиться средой.
                repeat(7) {
                    out += day
                    if (day == to) return out
                    day = day.plus(1)
                }
                return out
            }
        }
        val found = linkedSetOf<DayOfWeek>()
        Regex("""[\p{L}]+""").findAll(d).forEach { w ->
            dayOf(w.value)?.let { found += it }
        }
        return found
    }

    private fun dayOf(word: String): DayOfWeek? {
        val w = word.lowercase().replace('ё', 'е')
        DAY_SHORT[w]?.let { return it }
        return DAY_FORMS.entries.firstOrNull { (form, _) -> w == form }?.value
            ?: DAY_FORMS.entries.firstOrNull { (form, _) -> form.startsWith(w) && w.length >= 4 }?.value
            ?: DAY_FORMS.entries.firstOrNull { (form, _) -> w.startsWith(form) && form.length >= 5 }?.value
    }

    private val DAY_SHORT = mapOf(
        "пн" to DayOfWeek.MONDAY, "вт" to DayOfWeek.TUESDAY, "ср" to DayOfWeek.WEDNESDAY,
        "чт" to DayOfWeek.THURSDAY, "пт" to DayOfWeek.FRIDAY, "сб" to DayOfWeek.SATURDAY, "вс" to DayOfWeek.SUNDAY,
    )

    /** Все падежи, которые встречаются в речи: «в воскресенье», «с понедельника», «по вторникам». */
    private val DAY_FORMS: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg forms: String) = forms.forEach { put(it, day) }
        add(DayOfWeek.MONDAY, "понедельник", "понедельника", "понедельнику", "понедельником", "понедельникам", "понедельники")
        add(DayOfWeek.TUESDAY, "вторник", "вторника", "вторнику", "вторником", "вторникам", "вторники")
        add(DayOfWeek.WEDNESDAY, "среда", "среду", "среды", "среде", "средой", "средам", "среды")
        add(DayOfWeek.THURSDAY, "четверг", "четверга", "четвергу", "четвергом", "четвергам", "четверги")
        add(DayOfWeek.FRIDAY, "пятница", "пятницу", "пятницы", "пятнице", "пятницей", "пятницам")
        add(DayOfWeek.SATURDAY, "суббота", "субботу", "субботы", "субботе", "субботой", "субботам")
        add(DayOfWeek.SUNDAY, "воскресенье", "воскресенья", "воскресенью", "воскресеньем", "воскресеньям")
    }

    /** «по будням», «по выходным», «каждый день», «по пятницам» — как сказать дни вслух. */
    fun daysText(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> ""
        days.size == 7 -> "каждый день"
        days == WEEKDAYS -> "по будням"
        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "по выходным"
        else -> "по " + DayOfWeek.entries.filter { it in days }.joinToString(", ") { stem ->
            when (stem) {
                DayOfWeek.MONDAY -> "понедельникам"
                DayOfWeek.TUESDAY -> "вторникам"
                DayOfWeek.WEDNESDAY -> "средам"
                DayOfWeek.THURSDAY -> "четвергам"
                DayOfWeek.FRIDAY -> "пятницам"
                DayOfWeek.SATURDAY -> "субботам"
                DayOfWeek.SUNDAY -> "воскресеньям"
            }
        }
    }

    private fun alarmTimeText(a: ActiveAlarm): String = buildString {
        append(RuFormat.time(a.time))
        daysText(a.days).takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
        if (a.label.isNotBlank()) append(", «").append(a.label).append('»')
    }

    /** Сколько включено и какие: «Сейчас включено 2 будильника: на 07:00 по будням и на 10:00 по выходным.» */
    fun alarmsText(alarms: List<ActiveAlarm>): String {
        val on = alarms.filter { it.enabled }.sortedBy { it.time }
        if (on.isEmpty()) return "Включённых будильников нет. Скажите, например: «будильник на 7 утра»."
        val head = "Сейчас ${on.size} ${plural(on.size, "включённый будильник", "включённых будильника", "включённых будильников")}: "
        val items = on.map { alarmTimeText(it) }
        val list = when (items.size) {
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " и " + items.last()
        }
        return head + list + "."
    }

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

    /** «половина восьмого» — порядковые числительные часа. */
    private val ORDINAL_HOURS = linkedMapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвертого" to 4, "пятого" to 5, "шестого" to 6,
        "седьмого" to 7, "восьмого" to 8, "девятого" to 9, "десятого" to 10, "одиннадцатого" to 11, "двенадцатого" to 12,
    )

    private fun adjust(h: Int, d: String): Int = when {
        re("""\b(?:вечера|вечером|дня)\b""").containsMatchIn(d) && h in 1..11 -> h + 12
        re("""\bночи\b""").containsMatchIn(d) && h == 12 -> 0
        else -> h
    }

    // ------------------------------------------------------------------ Бытовые ответы без интернета

    private val holidays = mapOf(
        "новог" to MonthDay.of(1, 1), "нового года" to MonthDay.of(1, 1), "рождеств" to MonthDay.of(1, 7),
        "8 марта" to MonthDay.of(3, 8), "восьмого марта" to MonthDay.of(3, 8), "23 февраля" to MonthDay.of(2, 23),
        "дня победы" to MonthDay.of(5, 9), "9 мая" to MonthDay.of(5, 9), "1 сентября" to MonthDay.of(9, 1),
        "лета" to MonthDay.of(6, 1), "зимы" to MonthDay.of(12, 1), "осени" to MonthDay.of(9, 1), "весны" to MonthDay.of(3, 1),
        "хэллоуина" to MonthDay.of(10, 31), "дня святого валентина" to MonthDay.of(2, 14), "14 февраля" to MonthDay.of(2, 14),
    )

    private val months = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    /** Ответ на бытовой вопрос или null. [today] — текущая дата пользователя. */
    fun answer(n: String, today: LocalDate, random: Random = Random.Default): String? {
        // «маршрут в 2гис» без места — это всё равно «куда ехать?»: название карт убираем.
        val t = stripMapsApp(normalizeRoute(n.trim())).first.trimEnd('?', '!', '.')
        // Монетка, кубик, случайное число.
        if (re("""(подбрось|брось|кинь)\s+монет|орел или решка|орёл или решка""").containsMatchIn(t)) return if (random.nextBoolean()) "Орёл!" else "Решка!"
        if (re("""(брось|кинь|подбрось)\s+(кубик|кость)""").containsMatchIn(t)) return "Выпало ${random.nextInt(1, 7)}."
        re("""(?:случайное число|(?:загадай|назови|выбери|придумай)\s+(?:мне\s+)?(?:любое\s+|какое-нибудь\s+|какое-то\s+|случайное\s+)?число(?=\s+от\s+\d))(?:\s+от\s+(\d+)\s+до\s+(\d+))?""").find(digitize(t))?.let { m ->
            val from = m.groupValues[1].toIntOrNull() ?: 1
            val to = m.groupValues[2].toIntOrNull() ?: 100
            if (to > from) return "Пусть будет ${random.nextInt(from, to + 1)}."
        }
        // Сколько дней до даты/праздника.
        re("""сколько\s+(?:еще\s+)?(?:осталось\s+)?(?:дней|день)\s+(?:осталось\s+)?до\s+(.+)$|сколько\s+(?:еще\s+)?осталось\s+до\s+(лета|зимы|весны|осени|нового года|нг)$""").find(digitize(t))?.let { m ->
            val target = targetDate(m.groupValues[1].ifEmpty { m.groupValues[2] }, today) ?: return null
            val days = ChronoUnit.DAYS.between(today, target)
            return when (days) {
                0L -> "Это сегодня!"
                1L -> "Завтра!"
                else -> "До ${RuFormat.date(target, today)} — ${plural(days, "день", "дня", "дней")}."
            }
        }
        // День недели для даты: «какой день недели 8 марта».
        re("""какой\s+день\s+недели\s+(?:будет\s+|был\s+)?(.+)$""").find(digitize(t))?.let { m ->
            val target = targetDate(m.groupValues[1], today) ?: return null
            val dow = target.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("ru"))
            return "${RuFormat.date(target, today).replaceFirstChar { it.uppercase() }} — $dow."
        }
        // «Маршрут», «поехали», «найди маршрут» без места: лучше переспросить, чем ответить «не поняла».
        // «Маршрут на сегодня» — тоже без места: слово времени адресом не считается.
        routeQuestion(n)?.let { return it }
        worldTime(t)?.let { return it }
        return convert(digitize(t))
    }

    /**
     * «Маршрут», «поехали», «найди маршрут» без места — вопрос «куда ехать?», а не поиск в интернете.
     * Возвращает текст уточнения или null, если фраза не про маршрут.
     */
    fun routeQuestion(n: String): String? {
        val t = stripMapsApp(normalizeRoute(n.trim())).first.trimEnd('?', '!', '.')
        if (!ROUTE_QUESTION.containsMatchIn(t)) return null
        return "Куда построить маршрут? Скажите, например: «построй маршрут до вокзала» — или просто «ближайшая аптека»."
    }

    private val cities = mapOf(
        "москв" to "Europe/Moscow", "питер" to "Europe/Moscow", "петербург" to "Europe/Moscow", "калининград" to "Europe/Kaliningrad",
        "самар" to "Europe/Samara", "екатеринбург" to "Asia/Yekaterinburg", "омск" to "Asia/Omsk", "новосибирск" to "Asia/Novosibirsk",
        "красноярск" to "Asia/Krasnoyarsk", "иркутск" to "Asia/Irkutsk", "якутск" to "Asia/Yakutsk", "владивосток" to "Asia/Vladivostok",
        "магадан" to "Asia/Magadan", "камчатк" to "Asia/Kamchatka", "минск" to "Europe/Minsk", "киев" to "Europe/Kyiv", "астан" to "Asia/Almaty",
        "алмат" to "Asia/Almaty", "ташкент" to "Asia/Tashkent", "тбилиси" to "Asia/Tbilisi", "ереван" to "Asia/Yerevan", "баку" to "Asia/Baku",
        "лондон" to "Europe/London", "париж" to "Europe/Paris", "берлин" to "Europe/Berlin", "рим" to "Europe/Rome", "мадрид" to "Europe/Madrid",
        "стамбул" to "Europe/Istanbul", "дубай" to "Asia/Dubai", "дели" to "Asia/Kolkata", "пекин" to "Asia/Shanghai", "шанха" to "Asia/Shanghai",
        "токио" to "Asia/Tokyo", "сеул" to "Asia/Seoul", "бангкок" to "Asia/Bangkok", "сингапур" to "Asia/Singapore", "сидне" to "Australia/Sydney",
        "нью-йорк" to "America/New_York", "нью йорк" to "America/New_York", "вашингтон" to "America/New_York", "чикаго" to "America/Chicago",
        "лос-анджелес" to "America/Los_Angeles", "лос анджелес" to "America/Los_Angeles", "сан-франциско" to "America/Los_Angeles",
        "торонто" to "America/Toronto", "мехико" to "America/Mexico_City", "рио" to "America/Sao_Paulo", "буэнос" to "America/Argentina/Buenos_Aires",
        "каир" to "Africa/Cairo", "анталь" to "Europe/Istanbul", "пхукет" to "Asia/Bangkok", "бали" to "Asia/Makassar",
    )

    /** «Сколько времени в Токио», «который час в Нью-Йорке». */
    private fun worldTime(t: String): String? {
        val m = re("""(?:сколько\s+(?:сейчас\s+)?(?:времени|время)|который\s+(?:сейчас\s+)?час|какое\s+(?:сейчас\s+)?время|^время)\s+(?:сейчас\s+)?(?:в|во)\s+(.+)$""").find(t) ?: return null
        val place = m.groupValues[1].trim()
        val zone = cities.entries.firstOrNull { place.startsWith(it.key) || place.contains(it.key) }?.value ?: return null
        val time = java.time.ZonedDateTime.now(ZoneId.of(zone))
        val title = place.split(" ").joinToString(" ") { w -> w.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } } }
        return "В $title сейчас ${RuFormat.time(time.toLocalTime())}."
    }

    private fun targetDate(s: String, today: LocalDate): LocalDate? {
        val x = s.trim()
        holidays.entries.firstOrNull { x.contains(it.key) }?.value?.let { md -> return next(md, today) }
        re("""(\d{1,2})\s+([а-я]+)""").find(x)?.let { m ->
            val idx = months.indexOfFirst { m.groupValues[2].startsWith(it) }
            if (idx >= 0) runCatching { return next(MonthDay.of(idx + 1, m.groupValues[1].toInt()), today) }
        }
        re("""(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?""").find(x)?.let { m ->
            return runCatching {
                val year = m.groupValues[3].toIntOrNull()
                if (year != null) LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                else next(MonthDay.of(m.groupValues[2].toInt(), m.groupValues[1].toInt()), today)
            }.getOrNull()
        }
        mapOf("лет" to MonthDay.of(6, 1), "зим" to MonthDay.of(12, 1), "весн" to MonthDay.of(3, 1), "осен" to MonthDay.of(9, 1), "нг" to MonthDay.of(1, 1))
            .entries.firstOrNull { x.startsWith(it.key) }?.let { return next(it.value, today) }
        if (x.startsWith("конца год")) return LocalDate.of(today.year, 12, 31)
        if (x.startsWith("конца месяц")) return today.withDayOfMonth(today.lengthOfMonth())
        if (x.startsWith("выходн")) return generateSequence(today) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.SATURDAY }
        return null
    }

    private fun next(md: MonthDay, today: LocalDate): LocalDate {
        val thisYear = md.atYear(today.year)
        return if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
    }

    private data class Unit(val names: List<String>, val kind: String, val factor: Double, val title: String)

    private val units = listOf(
        Unit(listOf("километр", "км"), "len", 1000.0, "км"), Unit(listOf("метр", "м"), "len", 1.0, "м"),
        Unit(listOf("сантиметр", "см"), "len", 0.01, "см"), Unit(listOf("миллиметр", "мм"), "len", 0.001, "мм"),
        Unit(listOf("мил"), "len", 1609.344, "миль"), Unit(listOf("фут"), "len", 0.3048, "футов"),
        Unit(listOf("дюйм"), "len", 0.0254, "дюймов"), Unit(listOf("ярд"), "len", 0.9144, "ярдов"),
        Unit(listOf("килограмм", "кг", "кило"), "mass", 1.0, "кг"), Unit(listOf("грамм", "г"), "mass", 0.001, "г"),
        Unit(listOf("тонн"), "mass", 1000.0, "т"), Unit(listOf("фунт"), "mass", 0.45359237, "фунтов"),
        Unit(listOf("унци"), "mass", 0.0283495, "унций"),
        Unit(listOf("литр", "л"), "vol", 1.0, "л"), Unit(listOf("миллилитр", "мл"), "vol", 0.001, "мл"),
        Unit(listOf("галлон"), "vol", 3.78541, "галлонов"),
        Unit(listOf("час"), "time", 3600.0, "ч"), Unit(listOf("минут"), "time", 60.0, "мин"), Unit(listOf("секунд"), "time", 1.0, "с"),
        Unit(listOf("сут", "дн", "день", "дней"), "time", 86400.0, "дней"), Unit(listOf("недел"), "time", 604800.0, "недель"),
    )

    private fun unitOf(word: String): Unit? = units.firstOrNull { u -> u.names.any { n -> if (n.length <= 2) word == n else word.startsWith(n) } }

    /** «переведи 5 миль в километры», «сколько 10 фунтов в кг», «100 градусов фаренгейта в цельсии». */
    fun convert(t: String): String? {
        ai.nova.core.nlp.Kitchen.answer(t)?.let { return it }
        // «72 по фаренгейту» / «20 градусов по цельсию»: целевая шкала — противоположная.
        re("""(?:сколько\s+(?:это\s+)?)?(-?\d+(?:\.\d+)?)\s*(?:градус\w*\s*)?по\s+(фаренгейт\w*|цельси\w*)\s*[?.!]*$""").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val fromF = m.groupValues[2].startsWith("фарен")
            val r = if (fromF) (v - 32) * 5 / 9 else v * 9 / 5 + 32
            return "${fmt(v)}° ${if (fromF) "F" else "C"} = ${fmt(r)}° ${if (fromF) "C" else "F"}."
        }
        re("""(-?\d+(?:\.\d+)?)\s*градус\w*\s*(фаренгейт\w*|цельси\w*)\s+(?:в|во)\s+(фаренгейт\w*|цельси\w*)""").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val toC = m.groupValues[3].startsWith("цельс")
            val r = if (toC) (v - 32) * 5 / 9 else v * 9 / 5 + 32
            return "${fmt(v)}° ${if (toC) "F" else "C"} = ${fmt(r)}° ${if (toC) "C" else "F"}."
        }
        val m = re("""(?:переведи|конвертируй|сколько)?\s*(\d+(?:\.\d+)?)\s+([а-я]+)\s+(?:в|во)\s+([а-я]+)""").find(t) ?: return null
        val v = m.groupValues[1].toDouble()
        val from = unitOf(m.groupValues[2]) ?: return null
        val to = unitOf(m.groupValues[3]) ?: return null
        if (from.kind != to.kind || from === to) return null
        val r = v * from.factor / to.factor
        return "${fmt(v)} ${from.title} = ${fmt(r)} ${to.title}."
    }

    /**
     * Отделяет от адреса название карт: «дом в 2ГИС» → («дом», 2ГИС).
     * Без карт в фразе приложение выберет настройка.
     */
    /** Служебные слова перед местом: «найди ближайшую аптеку», «хочу в кино», «где музей». */
    private val PLACE_LEAD = re("""^(?:(?:найди|найти|поищи|поискать|искать|покажи|посмотри|подскажи|где|куда|хочу|хотим|хочется|пойдём|пойдем|сходим|сходить|съездить|давай|можно|мне|нам|надо|нужно|бы|отведи|отведите|веди|проведи|отвези|отвезите|довези|довезите)(?:\s+|$)|(?:в|во|на|до|к)\s+)""")

    /**
     * Маршрут и поиск места на карте — самый широкий набор формулировок.
     *
     * Понимаем три вида фраз:
     *  1. с командой: «построй маршрут до музея», «как доехать до рынка», «отвези меня в аэропорт»;
     *  2. без команды, но с предлогом: «до аптеки», «к вокзалу»;
     *  3. просто место: «музей», «ближайшая аптека», «где поесть», «заправка рядом».
     *
     * Возвращает null, если фраза не про место — тогда её разбирают другие правила.
     */
    private fun route(t: String): DeviceCommand.Navigate? {
        // Название карт мог назвать кто угодно и где угодно: «маршрут домой в 2гис», «в 2ГИС до музея».
        // Сначала убираем его из фразы целиком: хвост «в 2гис» иначе ломает разбор — «в 2» читалось
        // как время, и команда уходила в напоминания («Напомню «Маршрут домой гис» завтра в 02:00»).
        val (withoutApp, namedApp) = stripMapsApp(normalizeRoute(t))
        val p = withoutApp.ifBlank { t }

        fun navigate(raw: String): DeviceCommand.Navigate? {
            val (place, app) = stripMapsApp(raw)
            // «до вокзала» → «вокзал»: карты точнее находят словарную форму.
            val destination = RuQuery.placeForm(place)
            if (destination.isBlank() || destination.length > 80) return null
            // «маршрут на сегодня» — это не место: про время маршрут не строим.
            if (destination.lowercase() in TIME_WORDS) return null
            return DeviceCommand.Navigate(destination, app ?: namedApp)
        }

        // 1. Команда + предлог + место: «построй маршрут до музея», «как доехать до рынка».
        re("""^$ROUTE_LEAD\s+(?:меня\s+)?(?:до|к|в|на)\s+(.+)$""").find(p)?.let { m -> navigate(m.groupValues[1])?.let { return it } }
        // «поехали домой», «отвези меня на работу», «маршрут домой» — без предлога.
        re("""^$ROUTE_LEAD\s+(?:меня\s+)?((?:домой|дом|на\s+работу|работу|на\s+дачу|дачу|в\s+офис|офис|домой\s+и\s+всё))$""").find(p)?.let { m -> navigate(m.groupValues[1])?.let { return it } }
        // «маршрут музей», «поехали кино», «отвези меня аэропорт» — команда есть, предлога нет.
        // Дальше идёт просто место, поэтому служебные ответы («маршрут карту») отсеиваем.
        re("""^$ROUTE_ONLY\s+(?:меня\s+)?(.+)$""").find(p)?.let { m ->
            val rest = m.groupValues[1].trim()
            val words = rest.split(' ').filter { it.isNotBlank() }
            if (rest.isNotEmpty() && rest.length <= 60 && words.size <= 6 &&
                !re("""^(?:карт\w*|маршрут\w*|навигац\w*|навигатор\w*|путь|дорог\w*|пожалуйста)$""").containsMatchIn(rest) &&
                !re("""^(?:до|к|в|во|на)\s""").containsMatchIn(rest) &&
                !re("""\s(?:и|а|потом|затем|но|или)\s|,""").containsMatchIn(rest)
            ) navigate(rest)?.let { return it }
        }

        // 2. Без команды, но с предлогом: «до аптеки», «к вокзалу». «до свидания» — не место.
        re("""^(?:до|к)\s+(.+)$""").find(p)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (!re("""^(?:свидания|завтра|вечера|утра|пока|встречи|конца|обеда|ужина|пятницы|понедельника)\b""").containsMatchIn(rest)) {
                navigate(rest)?.let { return it }
            }
        }

        // 3. Просто место: «музей», «ближайшая аптека», «заправка рядом», «где поесть».
        // Служебные слова в начале («найди», «где», «хочу», «пойдём») место не меняют.
        var bare = re("""^(?:а\s+|ну\s+|нова,?\s+|пожалуйста,?\s+)*(.+)$""").find(p)?.groupValues?.get(1)?.trim() ?: return null
        while (true) {
            val next = PLACE_LEAD.replaceFirst(bare, "").trim()
            if (next == bare || next.isEmpty()) break
            bare = next
        }
        val words = bare.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return null
        // «расход 1500 аптека», «потратила 450 на кафе» — это траты, а не маршрут.
        if (words.any { it.any { c -> c.isDigit() } }) return null
        if (re("""\s(?:и|а|потом|затем|но|или)\s|,""").containsMatchIn(bare)) return null
        // «где поесть», «хочу поесть», «перекусить» → кафе: вопрос о еде решается картой, а не заметкой.
        if (FOOD_ONLY.containsMatchIn(bare)) {
            // Вид места человек не назвал: спросим «посидеть или перекусить» и только потом поведём.
            return DeviceCommand.Navigate("поесть", MapsApp.parse(bare), vague = true)
        }
        // Вид места — уже маршрут, даже если словаря мест слово не знает: «торговый центр»,
        // «снять деньги», «автобусная остановка». Проверяем до словаря: он знает «рынок», но не
        // «торговый центр», и такие фразы раньше уходили в поиск по записям вместо карты.
        // Покупки и записи не перехватываем: «купи продукты» — это список, а не маршрут.
        if (Places.isKindPhrase(bare) && !SHOPPING_VERB.containsMatchIn(bare)) return navigate(bare)
        // Вся фраза должна состоять из места и уточнений: «ближайшая аптека», «музей поблизости».
        // «сходить в парикмахерскую», «схожу в спортзал» — задачи и планы, их не перехватываем.
        val kinds = words.map { it.trim(',', '.', '!', '?') }.filterNot { RuQuery.isPlaceModifier(it) }
        if (kinds.isEmpty() || kinds.any { !RuQuery.isPlaceWord(it) }) return null
        // «где мой музей» — про записи, а не про карту: «мой», «моя», «мои» оставляем личным вопросам.
        if (re("""(?:^|\s)(?:мо[йяею]|наш\w*|у\s+меня)(?:\s|$)""").containsMatchIn(bare)) return null
        return navigate(bare)
    }

    /** «Купи продукты», «добавь в список» — про покупки, а не про маршрут до магазина. */
    private val SHOPPING_VERB = re("""^(?:купи|купить|куплю|покупать|добавь|допиши|запиши|внеси|положи|напомни|не\s+забудь)""")

    /** «Где поесть», «хочу есть», «перекусить» — вид места не назван, спросим уточнение. */
    private val FOOD_ONLY = re("""^(?:где|куда|хочу|хотим|можно|надо|мне|нам)?\s*(?:бы\s+)?(?:(?:хочу|хотим|можно|давай)\s+)?(?:поесть|пообедать|перекусить|покушать|подкрепиться|перекус|голоден|голодна|(?:хочу|хотим)\s+есть)$""")

    /**
     * Фразы, после которых место идёт без предлога: «маршрут музей», «поехали кино».
     * Отличаются от [ROUTE_LEAD] тем, что за ними точно идёт место, а не «карту», «навигатор».
     */
    private const val ROUTE_ONLY = """(?:маршрут|навигатор|навигация|поехали|поедем|покатили|отвези|отвезите|довези|довезите|веди|проведи|направь|покажи\s+дорогу|построй\s+маршрут|построить\s+маршрут|проложи\s+маршрут|проложить\s+маршрут|построй\s+путь|построй\s+дорогу|проложи\s+путь|проложи\s+дорогу|(?:найди|найти|поищи|посмотри|ищи)\s+(?:мне\s+)?(?:маршрут|путь|дорогу|дорогу\s+до|навигацию|навигатор))"""

    /** Начало фразы-маршрута без места: «построй маршрут», «как доехать», «отвези меня». */
    private const val ROUTE_LEAD = """(?:(?:построй|постройте|построить|проложи|проложите|проложить|покажи|показать|включи|включить|запусти|открой|подскажи|скажи|найди|найти|поищи|поискать|посмотри|посмотреть|ищи|искать)\s+(?:мне\s+)?(?:маршрут|путь|дорогу|навигацию|навигатор|карту)|как\s+(?:мне\s+)?(?:доехать|добраться|дойти|проехать|пройти|попасть|до)|маршрут|навигатор|навигация|поехали|поедем|покатили|отвези|отвезите|довези|довезите|веди(?:\s+меня)?|проведи(?:\s+меня)?|направь(?:\s+меня)?|покажи\s+дорогу)"""

    /** Фраза про маршрут без самого места: «маршрут», «найди маршрут», «маршрут на сегодня». */
    private val ROUTE_QUESTION = re(
        """^(?:построй|постройте|проложи|покажи|включи|открой|найди|поищи|посмотри)?\s*(?:маршрут|навигатор|навигацию|поехали|поедем|покатили|отвези(?:те)?(?:\s+меня)?|довези(?:те)?(?:\s+меня)?|как\s+(?:доехать|добраться|дойти))""" +
            """(?:\s+(?:на|в|за)\s+(?:сегодня|завтра|послезавтра|утро|утром|вечер|вечером|сейчас|потом|скорее|быстрее))?$""",
    )

    /**
     * Приводит фразу к словарному виду до разбора: опечатки, «построй-ка» и огрехи распознавания речи.
     *
     * Так писали живые люди и диктовали голосом: «маршут» вместо «маршрут»,
     * «постройки маршрут до дом» (распознанное «построй-ка»), «построй маршут до дом».
     */
    internal fun normalizeRoute(text: String): String {
        var s = text
        // Любое слово на «марш…» с буквой «т» — это маршрут: маршут, марштрут, маршруты.
        s = s.replace(re("""(?i)\bмарш\p{L}*т\p{L}*"""), "маршрут")
        // «постройки маршрут», «построй ка маршрут» — распознанное «построй-ка».
        s = s.replace(re("""(?i)\bпостройк\p{L}*"""), "построй")
        s = s.replace(re("""(?i)\b(построй|проложи|покажи|веди|проведи|направь|открой|включи|скажи|подскажи|найди|поищи|посмотри|отвези|довези)(?:[-\s]?ка)\b"""), "$1")
        return s.trim()
    }

    /** Слова времени: «маршрут на сегодня», «поехали завтра» — это не адрес. */
    private val TIME_WORDS = setOf(
        "сегодня", "завтра", "послезавтра", "вчера", "утром", "днем", "вечером", "ночью",
        "сейчас", "потом", "скорее", "быстрее", "домой и всё",
    )

    internal fun stripMapsApp(destination: String): Pair<String, MapsApp?> {
        val app = MapsApp.parse(destination) ?: return destination to null
        // Кириллица: \w её не ловит, поэтому окончания берём явным классом букв
        // («яндекс карты» → убираем слово целиком, а не «яндекс карт» + «ы»).
        val words = """2\s*гис|2гис|дубльгис|2gis|яндекс\s*карт[\p{L}]*|яндексе|яндекс|google\s*карт[\p{L}]*|гугл\s*карт[\p{L}]*|гуглкарт[\p{L}]*|гугле|гугл"""
        val cleaned = destination
            // «в 2ГИС», «через Яндекс Карты» — убираем предлог вместе с названием.
            .replace(Regex("""(?i)\s*(?:в|на|через|открой|открыть|приложение)?\s*(?:$words)"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .replace(Regex("""(?i)^(?:до|к|в|на)\s+"""), "")
            .trim().trim(',', '.', '-')
        return (cleaned.ifBlank { destination }.trim()) to app
    }

    private fun fmt(v: Double): String {
        val rounded = if (kotlin.math.abs(v) >= 100) Math.round(v * 10) / 10.0 else Math.round(v * 1000) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString().replace('.', ',')
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }

    /** Человеческое описание длительности: «5 минут», «1 час 30 минут». */
    fun describeDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { plural(it.toLong(), "час", "часа", "часов") },
            m.takeIf { it > 0 }?.let { plural(it.toLong(), "минуту", "минуты", "минут") },
            s.takeIf { it > 0 }?.let { plural(it.toLong(), "секунду", "секунды", "секунд") },
        ).joinToString(" ")
    }
}
