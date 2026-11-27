package ai.nova.app.device

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import ai.nova.app.media.RadioService
import ai.nova.app.media.RingService
import ai.nova.app.notify.NovaNotificationListener
import ai.nova.app.reminders.GeoReminders
import ai.nova.app.reminders.NovaTimers
import ai.nova.core.skills.ActiveTimer
import ai.nova.core.skills.CalendarItem
import ai.nova.core.skills.ContactInfo
import ai.nova.core.skills.GeoPoint
import ai.nova.core.skills.IncomingMessage
import ai.nova.core.skills.MusicApp
import ai.nova.core.skills.MusicTrack
import ai.nova.core.skills.NeedsPermission
import ai.nova.core.skills.Permission
import ai.nova.core.skills.PlaceReminder
import ai.nova.core.skills.RadioStation
import ai.nova.core.skills.SavedPlace
import ai.nova.core.skills.SkillHost
import ai.nova.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Возможности телефона для навыков Нова. Обычные разрешения (контакты, календарь, геолокация)
 * спрашиваются прямо по ходу команды; особые доступы (уведомления, спецвозможности) — открываем нужный экран настроек.
 */
class AndroidSkillHost(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val launcher: BackgroundLauncher,
    val timers: NovaTimers,
    val geo: GeoReminders,
    private val onUserName: (String?) -> Unit,
    private val onCity: (String?) -> Unit,
    private val finance: FinanceBridge? = null,
    private val financePendingSince: suspend (Instant) -> List<ai.nova.core.model.Expense> = { emptyList() },
    /** Положить найденный текст в заметки Нова. */
    private val saveNoteTo: suspend (String, String) -> Boolean = { _, _ -> false },
    /** Выбранное приложение для музыки — то же, что видно в настройках «Действия». */
    private val musicAppSetting: () -> MusicApp? = { null },
    private val onMusicApp: (MusicApp) -> Unit = {},
    /** Клиент для каталога площадок (поиск товаров). */
    private val http: io.ktor.client.HttpClient? = null,
    /** Маркетплейсы, выбранные в настройках «Действия»; пусто — все. */
    private val selectedMarketplaces: () -> Set<ai.nova.core.shopping.Marketplace> = { emptySet() },
    /** Товар, который не удалось положить в корзину площадки, — в список покупок Нова. */
    private val saveToShoppingList: suspend (ai.nova.core.shopping.ShoppingProduct) -> Boolean = { false },
    /** Разрешил ли пользователь отдавать текст экрана облачному AI (пересказ/перевод экрана). */
    private val screenAiConsent: () -> Boolean = { false },
    /** Уходит ли текст навыков в облако: есть настроенный внешний провайдер или «Облако Нова». */
    private val cloudAiConfigured: () -> Boolean = { false },
) : SkillHost {
    private val locator = Locator(context)
    private val players = ai.nova.app.media.OtherPlayers(context)
    private val mediaSearch = ai.nova.app.media.MediaSearch(context)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val shoppingSearch by lazy { ai.nova.core.shopping.ShoppingSearchService(http) }
    /** Поиск через браузерный движок: нужен, когда каталог закрыт для обычных запросов. */
    private val webCatalog by lazy { WebCatalog(context) }
    /** Найденное на странице живёт недолго: повторная просьба не открывает сайт заново. */
    private val webCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Pair<ai.nova.core.shopping.Marketplace, String>, List<ai.nova.core.shopping.ShoppingProduct>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<ai.nova.core.shopping.Marketplace, String>, List<ai.nova.core.shopping.ShoppingProduct>>) = size > 4
        },
    )

    override suspend fun location(): GeoPoint? {
        if (!locator.hasCoarse() && !permissions.ensure(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return locator.current()?.let { GeoPoint(it.latitude, it.longitude) }
    }

    override suspend fun messages(): List<IncomingMessage> {
        if (!NovaNotificationListener.enabled(context)) {
            launcher.launch(NovaNotificationListener.settingsIntent(context))
            throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет доступа к уведомлениям")
        }
        return NovaNotificationListener.messages()
    }

    override suspend fun reply(message: IncomingMessage, text: String): Boolean =
        withContext(Dispatchers.Main) { NovaNotificationListener.reply(context, message.key, text) }

    override fun accessibilityEnabled(): Boolean = NovaAccessibilityService.current != null

    override suspend fun screenText(): String? {
        val service = NovaAccessibilityService.current
        if (service == null) {
            launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw NeedsPermission(Permission.ACCESSIBILITY, "спецвозможности выключены")
        }
        return withContext(Dispatchers.Main) { service.screenText() }
    }

    // Текст экрана уходит только модели на телефоне — согласие не нужно; в облако — только с явного разрешения.
    override fun screenAiAllowed(): Boolean = !cloudAiConfigured() || screenAiConsent()

    override suspend fun contact(name: String): ContactInfo? {
        if (!permissions.ensure(Manifest.permission.READ_CONTACTS)) throw NeedsPermission(Permission.CONTACTS, "нет доступа к контактам")
        return withContext(Dispatchers.IO) { ContactLookup.find(context, name)?.let { ContactInfo(it.name, it.phones) } }
    }

    /** Имена и номера из контактов для «где я»: кого можно позвать на помощь. Разрешение не спрашиваем — без него пусто. */
    override suspend fun contacts(limit: Int): List<ContactInfo> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        return withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, MutableList<String>>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )?.use { c ->
                    while (c.moveToNext() && found.size < limit) {
                        val name = c.getString(0)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        val number = c.getString(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        found.getOrPut(name) { ArrayList() }.add(number)
                    }
                }
            }
            found.map { (name, phones) -> ContactInfo(name, phones) }
        }
    }

    /** Дни рождения из контактов — только если доступ уже выдан: вопрос «у кого скоро ДР» не должен дёргать разрешения. */
    override suspend fun contactBirthdays(): List<Pair<String, java.time.MonthDay>> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, java.time.MonthDay>>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        // «1990-05-12», «--05-12» (без года), иногда «12.05.1990».
                        val raw = c.getString(1) ?: continue
                        val md = Regex("""(?:\d{4}|-)-(\d{2})-(\d{2})""").find(raw)?.let { m -> runCatching { java.time.MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() }
                            ?: Regex("""^(\d{1,2})\.(\d{1,2})""").find(raw)?.let { m -> runCatching { java.time.MonthDay.of(m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }
                        if (md != null) out += name to md
                    }
                }
            }
            out.distinctBy { it.first }
        }
    }

    override suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> {
        if (!permissions.ensure(Manifest.permission.READ_CALENDAR)) throw NeedsPermission(Permission.CALENDAR, "нет доступа к календарю")
        return withContext(Dispatchers.IO) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, from.toEpochMilli())
                android.content.ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val out = ArrayList<CalendarItem>()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CUSTOM_APP_URI,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 50) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Без названия"
                    // Дубликат собственного напоминания: Нова уже показывает его в плане дня.
                    if (c.getString(5)?.startsWith(ai.nova.app.calendar.CalendarMirror.CUSTOM_URI_PREFIX) == true) continue
                    val allDay = c.getInt(3) == 1
                    // Событие «на весь день» хранится в UTC-полночь — переносим на местную дату.
                    fun localDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                    val begin = if (allDay) localDay(c.getLong(1)) else Instant.ofEpochMilli(c.getLong(1))
                    val end = c.getLong(2).takeIf { it > 0 }?.let { if (allDay) localDay(it) else Instant.ofEpochMilli(it) }
                    // Вчерашнее событие «на весь день» попадает в окно запроса из-за часового пояса — отбрасываем.
                    if (allDay && (!begin.isBefore(to) || (end != null && !end.isAfter(from)))) continue
                    out += CalendarItem(title, begin, end, allDay, c.getString(4))
                }
            }
            out
        }
    }

    override suspend fun playRadio(station: RadioStation): Boolean = RadioService.play(context, station)
    override fun stopRadio(): Boolean = RadioService.stop(context)
    override fun radioPlaying(): Boolean = RadioService.playing

    /** Найденную песню играет тот же плеер, что и радио: медиасессия, фокус звука и уведомление уже готовы. */
    override suspend fun playTrack(track: MusicTrack): Boolean = RadioService.play(context, RadioStation(track.label, track.previewUrl), music = true)

    override fun hasMusicApp(app: MusicApp): Boolean = app.packages.any { installed(it) }

    override suspend fun playInMusicApp(query: String, app: MusicApp): Boolean {
        val pkg = app.packages.firstOrNull { installed(it) }
        // Свою музыку выключаем: иначе поверх играет прошлый трек Новы, а новая песня так и остаётся
        // на экране поиска — человек слышит старое и думает, что ничего не включилось.
        runCatching { RadioService.stop(context) }
        return try {
            if (query.isBlank()) {
                // Без названия песни: открываем приложение и просим его играть (у большинства
                // приложений это последний трек или «моя волна»). Клавишу жмём с задержкой —
                // сразу после запуска плеер ещё не готов принять команду.
                val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return false
                if (!launcher.launch(launch)) return false
                ensurePlaying(pkg)
                return true
            }
            // Играть может и прошлый трек: плеер отвечает «принято», а очередь остаётся прежней.
            // Поэтому каждый путь заканчивается проверкой, что заиграла именно названная песня.
            val before = pkg?.let { ai.nova.app.media.PlaybackCheck.track(context, it) }
            suspend fun heard(): Boolean? =
                pkg?.let { ai.nova.app.media.PlaybackCheck.waitTrack(context, it, before, query) }

            // 1. Служба плеера приложения: сама находит трек по названию — так же, как часы и Android Auto.
            if (pkg != null && mediaSearch.play(pkg, query)) {
                val ok = heard()
                Logger.d("Music", "служба плеера ${app.title}: ${ok ?: "не проверить"}")
                if (ok != false) return true
            }
            // 2. Стандартный запрос «включи по поиску»: по нему начинают играть Яндекс Музыка и Spotify.
            //    «Звуку» он не подходит — там сначала нужна страница поиска.
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            if (pkg != null) play.setPackage(pkg)
            if (!app.tapsFirstResult && play.resolveActivity(context.packageManager) != null && launcher.launch(play)) {
                ensurePlaying(pkg)
                val ok = heard()
                Logger.d("Music", "запрос воспроизведения ${app.title}: ${ok ?: "не проверить"}")
                return ok ?: started(pkg)
            }
            // 3. Поиск внутри приложения. «Звук» включает песню только после нажатия строки в результатах —
            //    это делают спецвозможности. Без них честно говорим, что нажать нужно самому.
            val web = app.webUrl(Uri.encode(query))
            val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(web))
            if (pkg != null) inApp.setPackage(pkg)
            if (inApp.resolveActivity(context.packageManager) != null && launcher.launch(inApp)) {
                if (app.tapsFirstResult) {
                    main.postDelayed({ NovaAccessibilityService.current?.tapTrack(query) }, TAP_RESULT_DELAY_MS)
                }
                ensurePlaying(pkg)
                // Страница поиска и подгрузка результатов занимают несколько секунд.
                val ok = pkg?.let {
                    ai.nova.app.media.PlaybackCheck.waitTrack(context, it, before, query, attempts = 10)
                }
                Logger.d("Music", "поиск в ${app.title}: ${ok ?: "не проверить"}")
                return ok ?: started(pkg)
            }
            // 4. Совсем не вышло — открываем поиск в браузере. Включать там нечего, поэтому ответ будет
            //    честным: песня не заиграла, нажать трек нужно самому.
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse(web))
            if (browser.resolveActivity(context.packageManager) == null) return false
            launcher.launch(browser)
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Music", "Не удалось включить ${app.title}", e)
            false
        }
    }

    /**
     * Проверяем, что песня правда заиграла: часть плееров (и подписка, и отказ службы) на запрос
     * из другого приложения отвечает только экраном поиска. Где проверить нельзя — верим на слово.
     */
    private suspend fun started(pkg: String?): Boolean {
        if (pkg == null) return true
        // Спецвозможности нажимают трек с задержкой: проверяем их путь чуть позже.
        return ai.nova.app.media.PlaybackCheck.waitPlaying(context, pkg) ?: true
    }

    /**
     * Плеер открылся, но молчит — жмём «играть» несколько раз с паузой: приложение после запуска
     * ещё не готово принять команду, а клавиша, нажатая раньше времени, уходит в никуда.
     */
    private fun ensurePlaying(packageName: String?) {
        pressPlay(delayMs = 1_200, packageName = packageName)
        pressPlay(delayMs = 3_000, packageName = packageName)
        pressPlay(delayMs = 6_000, packageName = packageName)
    }

    /**
     * Клавиша «играть» для чужого плеера. Работает только когда приложение на экране:
     * из фона такая команда уходит в никуда, а нашу музыку она бы поставила на паузу.
     */
    private fun pressPlay(delayMs: Long, packageName: String? = null) {
        main.postDelayed({
            if (RadioService.current != null) return@postDelayed
            // Сначала просим играть саму сессию плеера: клавишу не все приложения слышат.
            if (players.control(ai.nova.core.assistant.MediaAction.PLAY, packageName)) return@postDelayed
            val am = context.getSystemService(AudioManager::class.java) ?: return@postDelayed
            // Что-то уже играет — второй раз не жмём: чужие плееры понимают эту клавишу как паузу.
            if (am.isMusicActive) return@postDelayed
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            runCatching {
                am.dispatchMediaKeyEvent(down)
                am.dispatchMediaKeyEvent(up)
            }
        }, delayMs)
    }

    override fun resumeMusic(): Boolean = RadioService.resume(context)

    override fun musicApp(): MusicApp? = musicAppSetting()

    override fun rememberMusicApp(app: MusicApp) = onMusicApp(app)

    private fun installed(pkg: String): Boolean = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    override suspend fun ringPhone(): Boolean = RingService.start(context, "Я здесь!", "Нова ищет телефон. Коснитесь, чтобы остановить.", loud = true, flash = true)

    override fun timers(): List<ActiveTimer> = timers.all()
    override fun cancelTimers(label: String?): Int = timers.cancel(label)
    override fun cancelTimer(id: String): Boolean = timers.cancelOne(id)
    override fun extendTimer(id: String, seconds: Int): ActiveTimer? = timers.extend(id, seconds)

    override fun stopRing(): Boolean {
        val ringing = RingService.isRinging()
        if (ringing) RingService.stop(context)
        return ringing
    }

    override suspend fun savePlace(name: String): SavedPlace? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val loc = locator.current(maxAgeMs = 2 * 60_000L, precise = true) ?: return null
        return SavedPlace(name, loc.latitude, loc.longitude).also { geo.savePlace(it) }
    }

    override fun places(): List<SavedPlace> = geo.places()

    override suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val r = geo.add(text, place, onLeave)
        // Чтобы напоминание сработало, когда Нова закрыта, нужна геолокация «Всегда» (Android 10+).
        if (!locator.hasBackground()) permissions.ensure(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return r
    }

    override fun placeReminders(): List<PlaceReminder> = geo.reminders()
    override fun cancelPlaceReminders(place: String?): Int = geo.cancel(place)

    override suspend fun financeSummary(): String? = finance?.summary()
    override suspend fun financePending(since: Instant): List<ai.nova.core.model.Expense> = financePendingSince(since)

    override fun setUserName(name: String?) = onUserName(name)
    override fun setCity(city: String?) = onCity(city)

    override suspend fun saveNote(title: String, content: String): Boolean = saveNoteTo(title, content)

    // ------------------------------------------------------------------ Покупки

    /**
     * Товары: сначала прямой запрос к каталогу (быстро, когда площадка его отдаёт), а если он
     * закрыт — та же страница поиска в браузерном движке телефона (медленнее, зато работает).
     */
    override suspend fun searchProducts(query: ai.nova.core.shopping.ShoppingQuery): ai.nova.core.shopping.ShoppingSearchResult {
        val direct = withContext(Dispatchers.IO) { shoppingSearch.search(query) }
        if (direct.products.isNotEmpty()) {
            Logger.i("Shopping", "поиск: каталог ответил, товаров ${direct.products.size}")
            return direct
        }
        val wanted = query.marketplaces.ifEmpty { ai.nova.core.shopping.Marketplace.entries.toSet() }
        val keys = wanted.map { it to query.searchText() }
        val cached = keys.mapNotNull { (m, text) -> webCache[m to text]?.let { m to it } }.toMap()
        val need = wanted - cached.keys
        val fromWeb = java.util.concurrent.ConcurrentHashMap<ai.nova.core.shopping.Marketplace, List<ai.nova.core.shopping.ShoppingProduct>>(cached)
        // Площадки ищем одновременно: у каждой свой WebView, и ждать их по очереди вдвое дольше.
        val encoded = java.net.URLEncoder.encode(query.searchText(), "UTF-8")
        coroutineScope {
            need.forEach { marketplace ->
                launch {
                    val found = webCatalog.search(marketplace, marketplace.searchUrl(encoded), WEB_PER_MARKET)
                    if (found.isNotEmpty()) {
                        fromWeb[marketplace] = found
                        webCache[marketplace to query.searchText()] = found
                    }
                }
            }
        }
        if (fromWeb.isEmpty()) {
            Logger.w("Shopping", "поиск: каталог и страницы площадок не ответили")
            return direct
        }
        // Что нашлось на странице — то и показываем: прямые ответы идут первыми, страница их дополняет.
        val results = wanted.map { marketplace ->
            val web = fromWeb[marketplace].orEmpty()
            val known = direct.results.firstOrNull { it.marketplace == marketplace }
            ai.nova.core.shopping.MarketplaceResult(
                marketplace = marketplace,
                products = known?.products.orEmpty() + web,
                // Со страницы товары пришли — значит площадка ответила, и ошибку про закрытый каталог не показываем.
                error = if (web.isNotEmpty()) null else known?.error,
                status = known?.status ?: ai.nova.core.shopping.IntegrationStatus.SEARCH_ONLY,
            )
        }
        val merged = ai.nova.core.shopping.ShoppingSearchResult(
            query = query,
            results = results,
            products = results.flatMap { it.products },
        )
        if (merged.products.isEmpty()) return direct
        Logger.i(
            "Shopping",
            "поиск: товаров ${merged.products.size}, с фото ${merged.products.count { !it.imageUrl.isNullOrBlank() }}",
        )
        return merged
    }

    override suspend fun addToShoppingList(product: ai.nova.core.shopping.ShoppingProduct): Boolean =
        runCatching { saveToShoppingList(product) }.getOrDefault(false)

    override fun shoppingMarketplaces(): Set<ai.nova.core.shopping.Marketplace> = selectedMarketplaces()

    // Корзины у площадок для приложения нет: addToCart не переопределяем — навык сам положит товар
    // в список покупок Нова и оставит ссылку. Открывать браузер за человеком без просьбы нельзя.

    /** Страница товара или поиска: сначала в приложении площадки, иначе в браузере. */
    override suspend fun openShoppingUrl(url: String): Boolean {
        val marketplace = ai.nova.core.shopping.Marketplace.byUrl(url)
        val packages = when (marketplace) {
            ai.nova.core.shopping.Marketplace.OZON -> listOf("ru.ozon.app.android")
            ai.nova.core.shopping.Marketplace.WB -> listOf("com.wildberries.ru")
            null -> emptyList()
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val target = packages.firstOrNull { installed(it) }?.let { pkg -> Intent(view).setPackage(pkg) }
        if (target != null && target.resolveActivity(context.packageManager) != null && launcher.launch(target)) return true
        return launcher.launch(view)
    }

    private companion object {
        /** Сколько ждём, пока плеер отрисует результаты поиска. */
        const val TAP_RESULT_DELAY_MS = 2_500L

        /** Сколько карточек читаем со страницы поиска — больше десяти всё равно не показываем. */
        const val WEB_PER_MARKET = 20

    }
}
