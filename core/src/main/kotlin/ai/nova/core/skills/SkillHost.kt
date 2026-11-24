package ai.nova.core.skills

import java.time.Instant

/** Сообщение из мессенджера (из уведомления). */
data class IncomingMessage(
    val key: String,
    val app: String,
    val sender: String,
    val text: String,
    val time: Instant,
    val canReply: Boolean,
)

data class ContactInfo(val name: String, val phones: List<String>)

data class CalendarItem(val title: String, val start: Instant, val end: Instant?, val allDay: Boolean, val location: String? = null)

/** [seconds] — на сколько был поставлен таймер (0 — неизвестно). */
data class ActiveTimer(val id: String, val label: String, val endsAt: Instant, val seconds: Int = 0)

data class SavedPlace(val name: String, val lat: Double, val lon: Double)

data class PlaceReminder(val id: String, val text: String, val place: String, val onLeave: Boolean)

/** Для функции нужно разрешение — ответ предложит его выдать. */
class NeedsPermission(val permission: Permission, message: String) : Exception(message)

enum class Permission { LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS_ACCESS, CONTACTS, CALENDAR, ACCESSIBILITY }

/**
 * Возможности телефона для новых навыков. Реализует Android-приложение; по умолчанию — «не умею».
 * Методы могут бросать [NeedsPermission], если пользователь ещё не выдал доступ.
 */
interface SkillHost {
    /** Где сейчас телефон (приблизительно). */
    suspend fun location(): GeoPoint? = null

    /** Последние сообщения из уведомлений, новые сначала. */
    suspend fun messages(): List<IncomingMessage> = throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет доступа к уведомлениям")
    /** Ответить в тот же чат через кнопку «Ответить» уведомления. */
    suspend fun reply(message: IncomingMessage, text: String): Boolean = false

    /** Включены ли спецвозможности: без них Нова не может нажать кнопку в чужом приложении. */
    fun accessibilityEnabled(): Boolean = false

    /** Текст на экране (через спецвозможности). */
    suspend fun screenText(): String? = throw NeedsPermission(Permission.ACCESSIBILITY, "нет доступа к экрану")

    suspend fun contact(name: String): ContactInfo? = null

    /**
     * Контакты с телефонами — чтобы после «где я» предложить близкого человека для звонка или сообщения.
     * Без разрешения на контакты — пусто: спрашивать разрешение посреди вопроса «где я» неуместно.
     */
    suspend fun contacts(limit: Int = 10): List<ContactInfo> = emptyList()
    suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> = emptyList()
    /** Дни рождения из контактов телефона (только если доступ к контактам уже выдан). */
    suspend fun contactBirthdays(): List<Pair<String, java.time.MonthDay>> = emptyList()

    suspend fun playRadio(station: RadioStation): Boolean = false
    fun stopRadio(): Boolean = false
    /** Играет ли сейчас радио Нова: тогда «дальше» — следующая станция, а не трек. */
    fun radioPlaying(): Boolean = false

    /** Найденную песню играет встроенный плеер Нова (короткий фрагмент из открытого каталога). */
    suspend fun playTrack(track: MusicTrack): Boolean = false
    /** Установлено ли такое музыкальное приложение. */
    fun hasMusicApp(app: MusicApp): Boolean = false
    /**
     * Включить песню в приложении: сначала системный «включи по поиску» (Яндекс Музыка и Spotify
     * реально начинают играть), если не вышло — открыть поиск в приложении или на его сайте.
     * [query] пустой — просто открыть приложение.
     */
    suspend fun playInMusicApp(query: String, app: MusicApp): Boolean = false
    /** Музыка Нова на паузе — продолжаем её, а не открываем приложение. */
    fun resumeMusic(): Boolean = false
    /** Музыкальное приложение, выбранное в прошлый раз («включи музыку» в каком приложении). */
    fun musicApp(): MusicApp? = null
    fun rememberMusicApp(app: MusicApp) {}

    /** Громкий сигнал «я здесь». */
    suspend fun ringPhone(): Boolean = false

    fun timers(): List<ActiveTimer> = emptyList()
    fun cancelTimers(label: String?): Int = 0
    /** Отменить один таймер по id. */
    fun cancelTimer(id: String): Boolean = false

    /** Запомнить текущее место под именем. */
    suspend fun savePlace(name: String): SavedPlace? = null
    fun places(): List<SavedPlace> = emptyList()
    suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder? = null
    fun placeReminders(): List<PlaceReminder> = emptyList()
    fun cancelPlaceReminders(place: String?): Int = 0

    /**
     * Сводка «Финансового помощника» (JSON, договор версии 1) или null:
     * помощника нет, связь выключена или он не разрешил отдавать сводку.
     */
    suspend fun financeSummary(): String? = null
    /** Траты Нова, переданные в помощник после [since], — в его сводке их ещё нет. */
    suspend fun financePending(since: Instant): List<ai.nova.core.model.Expense> = emptyList()

    fun setUserName(name: String?) {}
    fun setCity(city: String?) {}

    /** Сохранить заметку (например, найденный рецепт). true — записала. */
    suspend fun saveNote(title: String, content: String): Boolean = false

    /**
     * Товары с площадок. Пустой результат с ошибкой — честный ответ «каталог не ответил»,
     * а не выдуманные товары: цены и наличие нельзя сочинять.
     */
    suspend fun searchProducts(query: ai.nova.core.shopping.ShoppingQuery): ai.nova.core.shopping.ShoppingSearchResult =
        ai.nova.core.shopping.ShoppingSearchResult(query = query, results = emptyList())

    /** Положить товар в корзину. По умолчанию Нова не умеет: открываем карточку, нажимает человек. */
    suspend fun addToCart(
        product: ai.nova.core.shopping.ShoppingProduct,
        size: String?,
        quantity: Int,
    ): ai.nova.core.shopping.CartResult? = null

    /** Открыть страницу товара или поиск на площадке. */
    suspend fun openShoppingUrl(url: String): Boolean = false

    /** Площадки, которые человек выбрал в настройках; пусто — обе доступные. */
    fun shoppingMarketplaces(): Set<ai.nova.core.shopping.Marketplace> = emptySet()

    /**
     * Положить товар в список покупок Нова. В корзину площадки приложение положить не может,
     * поэтому выбранное остаётся карточкой со ценой, фотографией и ссылкой: человек откроет и купит сам.
     */
    suspend fun addToShoppingList(product: ai.nova.core.shopping.ShoppingProduct): Boolean = false
}

object NoSkillHost : SkillHost
