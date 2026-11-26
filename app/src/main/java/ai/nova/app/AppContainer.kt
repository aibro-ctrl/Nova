package ai.nova.app

import android.content.Context
import ai.nova.app.data.DatabaseFactory
import ai.nova.app.device.AndroidDeviceController
import ai.nova.app.reminders.AlarmReminderScheduler
import ai.nova.app.security.KeystoreSecretStore
import ai.nova.app.security.KeystoreSessionStore
import ai.nova.app.settings.SettingsRepository
import ai.nova.app.sync.NetworkMonitor
import ai.nova.app.sync.SyncScheduler
import ai.nova.app.voice.AndroidSpeechRecognizerProvider
import ai.nova.app.voice.AndroidTtsProvider
import ai.nova.app.voice.VoiceController
import ai.nova.app.voice.VoskEngine
import ai.nova.app.voice.VoskModelManager
import ai.nova.app.voice.VoskSpeechProvider
import ai.nova.core.ai.AIConfig
import ai.nova.core.ai.AIProvider
import ai.nova.core.ai.AIProviderFactory
import ai.nova.core.ai.AIProviderType
import ai.nova.core.ai.EmbeddingProvider
import ai.nova.core.assistant.ActionExecutor
import ai.nova.core.assistant.AssistantEngine
import ai.nova.core.assistant.AssistantSettings
import ai.nova.core.assistant.TargetResolver
import ai.nova.core.auth.AuthManager
import ai.nova.core.auth.AuthState
import ai.nova.core.auth.SupabaseAuthClient
import ai.nova.core.data.LocalStore
import ai.nova.core.remote.PostgrestRemote
import ai.nova.core.remote.SupabaseConfig
import ai.nova.core.search.SearchService
import ai.nova.core.sync.SyncEngine
import ai.nova.core.util.SystemTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ручное внедрение зависимостей: один объект собирает все слои.
 * UI → VoiceController/ViewModel → AssistantEngine (core) → репозитории (core) → SQLCipher / Supabase.
 */
class AppContainer(private val context: Context) {
    /** Сбой в фоновой корутине пишется в журнал и не роняет приложение (раньше любое необработанное исключение завершало процесс). */
    private val backgroundErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        if (e !is kotlinx.coroutines.CancellationException) ai.nova.core.util.Logger.e("App", "Необработанный сбой в фоне", e)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + backgroundErrors)
    val time = SystemTimeSource()

    val secrets = KeystoreSecretStore(context)
    val settings = SettingsRepository(context, appScope)
    /** Что Нова сделала: журнал действий, только на этом телефоне. */
    val journal = ai.nova.app.diagnostics.ActionJournal(context)

    /** Клиент создаётся при первом запросе: его загрузка на старте съедала секунды и приводила к ANR. */
    val http: HttpClient by lazy {
        HttpClient(Android) {
            expectSuccess = false
            install(HttpTimeout) {
                // Было 90 секунд: при медленной сети «думаю» висело полторы минуты, и система убивала приложение.
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    /** БД открывается лениво и заранее прогревается в фоне (ключ SQLCipher — тяжёлая операция). */
    val store: LocalStore by lazy { LocalStore(DatabaseFactory.create(context, secrets), time) }

    // --- Supabase ---
    fun supabaseConfig(): SupabaseConfig {
        val s = settings.settings.value
        val url = s.supabaseUrlOverride.ifBlank { BuildConfig.SUPABASE_URL }
        val key = secrets.get(KeystoreSecretStore.SUPABASE_ANON_OVERRIDE)?.takeIf { s.supabaseUrlOverride.isNotBlank() } ?: BuildConfig.SUPABASE_ANON_KEY
        return SupabaseConfig(url, key)
    }

    val auth = AuthManager(
        clientProvider = { supabaseConfig().takeIf { it.isConfigured }?.let { SupabaseAuthClient(http, it) } },
        store = KeystoreSessionStore(secrets),
        time = time,
    )

    /** Резервная копия в зашифрованный файл и восстановление из него. */
    val backup: ai.nova.core.backup.BackupService by lazy {
        ai.nova.core.backup.BackupService(store, ai.nova.app.backup.AppBackupExtras(geo, settings), BuildConfig.VERSION_NAME)
    }

    val syncScheduler = SyncScheduler(context)
    val syncEngine: SyncEngine by lazy { SyncEngine(
        local = store,
        remote = object : ai.nova.core.remote.RemoteDataSource {
            // Конфигурация может меняться в настройках — создаём клиент на каждый вызов.
            private fun remote() = PostgrestRemote(http, supabaseConfig()) { force -> auth.accessToken(force) }
            override suspend fun upsert(table: String, rows: List<kotlinx.serialization.json.JsonObject>) = remote().upsert(table, rows)
            override suspend fun fetchChanges(table: String, since: java.time.Instant?, afterId: String?, limit: Int) = remote().fetchChanges(table, since, afterId, limit)
            override suspend fun fetchSingle(table: String) = remote().fetchSingle(table)
        },
        profile = settings,
        userId = { auth.userId },
        // История чата уходит на сервер открытым текстом — только если пользователь сам включил.
        tableEnabled = { table -> table != "conversation_messages" || settings.settings.value.syncChat },
    ) }
    val network = NetworkMonitor(context) { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon(1) }

    // --- AI ---
    /** Все включённые провайдеры в порядке приоритета (ключи — из Keystore). */
    fun aiConfigs(): List<AIConfig> {
        val s = settings.settings.value
        return s.activeProviders.map { p -> aiConfig(p.type) }
    }

    fun aiConfig(type: AIProviderType): AIConfig {
        val p = settings.settings.value.provider(type)
        return AIConfig(
            type = type,
            endpoint = p.endpoint,
            model = p.model,
            apiKey = secrets.get(KeystoreSecretStore.aiKey(type.id)).orEmpty(),
            embeddingsEnabled = settings.settings.value.embeddingsEnabled,
        )
    }

    /** Списки моделей, загруженные с серверов провайдеров (на время работы приложения). */
    val modelCache = mutableMapOf<AIProviderType, List<ai.nova.core.ai.ModelInfo>>()

    fun aiConfigured(): Boolean = aiConfigs().any { it.isComplete } || cloudActive()

    /** «Облако Нова» доступно: сервер настроен в сборке, пользователь вошёл и не выключил облако. */
    fun cloudActive(): Boolean = settings.settings.value.novaCloud && auth.state.value is AuthState.SignedIn && supabaseConfig().isConfigured

    /** Облако Нова идёт первым (без ключа пользователя), затем провайдеры пользователя. */
    private suspend fun aiProvider(): AIProvider {
        val cloud = if (cloudActive()) auth.accessToken()?.let { token ->
            AIConfig(AIProviderType.NOVA_CLOUD, supabaseConfig().url.trimEnd('/') + "/functions/v1/ai-proxy", "auto", token, embeddingsEnabled = false)
        } else null
        val user = if (settings.settings.value.useAI) aiConfigs() else emptyList()
        return AIProviderFactory.createChain(http, listOfNotNull(cloud) + user)
    }
    private fun embeddingProvider(): EmbeddingProvider? =
        if (network.online.value && settings.settings.value.useAI) AIProviderFactory.createEmbeddings(http, aiConfigs()) else null

    // --- Ассистент ---
    val reminderScheduler = AlarmReminderScheduler(context)
    /** Какие напоминания «настойчивые» (повторяются, пока не нажато «Готово»). */
    val nags = ai.nova.app.reminders.NagStore(context)
    val search: SearchService by lazy { SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { embeddingProvider() } }
    private val resolver by lazy { TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories) }
    /** Команды телефону (таймер, будильник, приложения, звонки…); запасной таймер — напоминание Нова. */
    val launcher = ai.nova.app.device.BackgroundLauncher(context)
    val systemAccess = ai.nova.app.device.SystemAccess(context)
    val updates = ai.nova.app.update.UpdateManager(context)
    val appLock = ai.nova.app.security.AppLock(context)
    val appAccess = ai.nova.app.device.AppAccess(context) { settings.settings.value }
    val permissions = ai.nova.app.device.PermissionBroker(context)
    /** Свои таймеры и напоминания по месту. */
    val timers = ai.nova.app.reminders.NovaTimers(context)
    /** Свои будильники: их можно перечислить, выключить и отложить голосом. */
    val alarms = ai.nova.app.reminders.NovaAlarms(context)
    val geo = ai.nova.app.reminders.GeoReminders(context)
    /** Дыхание и медитация голосом; «я за рулём» — сообщения вслух. */
    val relaxation = ai.nova.app.health.Relaxation(appScope) { speech.speak(it) }
    val driving = ai.nova.app.health.DrivingMode(context, appScope) { speech.speak(it) }
    /** Где телефон — для ближайших мест и геозон. */
    private val locator = ai.nova.app.device.Locator(context)
    /** Название и адрес по координатам (OpenStreetMap): вид места ищут сами карты. */
    private val streetPlaces by lazy { ai.nova.app.device.StreetPlaces(http) }

    val device = AndroidDeviceController(context, launcher, appAccess, permissions, fallbackReminder = { text, at ->
        val r = store.reminders.create(text, at, null, time.zone().id)
        reminderScheduler.schedule(r)
    }, timers = timers, alarms = alarms, relaxation = relaxation, driving = driving,
        // Карты из настроек: «в 2ГИС», «в Google» — иначе открываем те, что назначены системой для geo:.
        mapsApp = { ai.nova.core.assistant.MapsApp.fromId(settings.settings.value.mapsApp) },
        // Где искать в интернете: Android не сообщает поисковик браузера, поэтому выбор хранит Нова.
        searchEngine = { ai.nova.core.assistant.SearchEngine.fromId(settings.settings.value.searchEngine) },
        // «Мой город»: маршрут ищется в нём; пусто — как раньше, по последнему городу карт.
        city = { settings.settings.value.city.takeIf { it.isNotBlank() } },
        // «Я дома», «это моя работа»: маршрут до сохранённого места строится по координатам.
        places = { geo.places() },
        // Ближайшее место ищем сами: карты по слову показывают список, а маршрут строят по точке.
        streetPlaces = streetPlaces,
        here = {
            val at = locator.current()
            at?.let { it.latitude to it.longitude }
        })
    /** Связь с «Финансовым помощником»: траты уходят туда, ответы о деньгах — оттуда. */
    val finance = ai.nova.app.device.FinanceBridge(context, appScope)
    /** Траты, которые заодно уходят в помощник. Сама Нова пишет их так же, как раньше. */
    val expenses: ai.nova.core.domain.ExpenseRepository by lazy { ai.nova.core.finance.FinanceLinkedExpenses(store.expenses, finance) }
    /** Погода, курсы, новости, сообщения, экран, радио, игры, сказки… */
    val skillHost = ai.nova.app.device.AndroidSkillHost(
        context, permissions, launcher, timers, geo,
        onUserName = { n -> appScope.launch { settings.setUserName(n.orEmpty()) } },
        onCity = { c -> appScope.launch { settings.setCity(c.orEmpty()) } },
        finance = finance,
        financePendingSince = { since -> store.expenses.all().filter { it.createdAt.isAfter(since) && finance.known(it.id) } },
        saveNoteTo = { title, content ->
            runCatching {
                store.notes.create(ai.nova.core.model.NoteKind.NOTE, title.take(120), content, listOf("поиск"))
                true
            }.getOrDefault(false)
        },
        // Музыкальное приложение — одна настройка на всё приложение: и голос, и экран «Действия».
        musicAppSetting = { ai.nova.core.skills.MusicApp.entries.firstOrNull { it.name == settings.settings.value.musicApp } },
        onMusicApp = { app -> appScope.launch { settings.setMusicApp(app.name) } },
        http = http,
        selectedMarketplaces = { shoppingMarketplaces() },
        saveToShoppingList = { product ->
            runCatching {
                store.shopping.addProduct(ai.nova.core.model.ShoppingItem.DEFAULT_LIST, product) != null
            }.getOrDefault(false)
        },
        screenAiConsent = { settings.settings.value.screenAiConsent },
        cloudAiConfigured = { cloudActive() || (settings.settings.value.useAI && aiConfigs().any { it.isComplete }) },
    )
    /** Площадки для покупок: выбранные в настройках, иначе обе. */
    fun shoppingMarketplaces(): Set<ai.nova.core.shopping.Marketplace> {
        val ids = settings.settings.value.shoppingMarketplaces.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return ai.nova.core.shopping.Marketplace.entries.toSet()
        return ai.nova.core.shopping.Marketplace.entries.filter { it.id in ids }.toSet()
    }
    val skills: ai.nova.core.skills.Skills by lazy {
        // Память покупок — общая память Нова (категория «покупки»), а не отдельное хранилище.
        val memory = if (settings.settings.value.shoppingMemory) store.memories else null
        ai.nova.core.skills.Skills(skillHost, http, time, memory)
    }
    /** Каталог моделей Hugging Face: свежие Qwen без обновления приложения. */
    val modelCatalog = ai.nova.app.llm.ModelCatalog()
    /** Офлайн-модель для разговора (~1 ГБ, скачивается по кнопке). */
    val offlineLlm: ai.nova.app.llm.OfflineLlm by lazy {
        ai.nova.app.llm.OfflineLlm(context, appScope, modelCatalog) {
            val s = settings.settings.value
            ai.nova.core.ai.LocalChat.systemPrompt(
                s.assistantName,
                s.userName.takeIf { n -> n.isNotBlank() },
                ai.nova.core.assistant.Persona.of(s.persona).personality.skillStyle,
            )
        }
    }
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)

    /** Телефон заблокирован (экран блокировки показан). */
    fun isLocked(): Boolean = keyguard?.isKeyguardLocked == true

    /** Правила для заблокированного экрана; если пользователь запретил Нова на блокировке — всё закрыто. */
    /** Включён «Вход по отпечатку», а приложение ещё не разблокировано — чужой с открытым телефоном не должен видеть записи. */
    fun appLocked(): Boolean {
        val s = settings.settings.value
        return s.appLock && !appLock.unlocked.value && appLock.deviceSecure()
    }

    fun lockPolicy(): ai.nova.core.assistant.LockPolicy? {
        // Телефон разблокирован, но Нова под паролем: записывать и управлять телефоном можно, смотреть и менять записи — нет.
        if (!isLocked() && appLocked()) return ai.nova.core.assistant.LockPolicy(create = true, basicDevice = true, calls = true, view = false, edit = false, apps = true)
        if (!isLocked()) return null
        val s = settings.settings.value
        return if (s.lockScreenEnabled) s.lockPolicy
        else ai.nova.core.assistant.LockPolicy(create = false, basicDevice = false, calls = false, view = false, edit = false, apps = false)
    }

    val executor: ai.nova.core.assistant.ActionExecutor by lazy {
        ActionExecutor(
            store.notes, expenses, store.tasks, store.reminders, store.memories, search, resolver, reminderScheduler, time, device,
            lockPolicy = { lockPolicy() }, shopping = store.shopping, routines = store.routines, secrets = store.secrets,
            agendaExtras = { date -> skills.agendaExtras(date, assistantSettings()) },
            contactBirthdays = { if (assistantSettings().locked) emptyList() else skillHost.contactBirthdays() },
            onPersistentReminder = { nags.mark(it) },
            onCriticalReminder = { nags.mark(it); nags.markCritical(it) },
            onReminderCancelled = { nags.clear(it); nags.clearCritical(it) },
            videoPlatform = { ai.nova.core.assistant.VideoPlatform.fromId(settings.settings.value.videoPlatform) },
            onVideoPlatform = { platform -> appScope.launch { settings.setVideoPlatform(platform.id) } },
            onPlanCreated = { plan -> engine.notePlan(plan) },
        )
    }

    val engine: AssistantEngine get() = engineLazy.value
    private val engineLazy = lazy { AssistantEngine(
        notes = store.notes, tasks = store.tasks, reminders = store.reminders, memories = store.memories,
        conversations = store.conversations, search = search, executor = executor, time = time,
        settings = { assistantSettings() },
        aiProvider = { aiProvider() },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = skills,
        onNotUnderstood = { ai.nova.app.diagnostics.Diagnostics.rememberUnknown(context, it) },
        onExecuted = { actions, reply -> journal.log(actions, reply) },
        localChat = offlineLlm,
        habits = health,
        daySpend = ::daySpend,
        onPersona = { p -> appScope.launch { settings.setPersona(p.wire) } },
        expensesBetween = { a, b -> store.expenses.between(a, b) },
        agent = agentCore,
        cooking = store.cooking,
    ) }

    /** Ядро агента: реестр навыков, инструменты и постоянный контекст задач. */
    val agentCore: ai.nova.core.agent.AgentCore by lazy { ai.nova.core.agent.AgentCore(store.agent, time) }

    /** Напоминания, которые сработали и ждут «Готово»: для «Моего дня». */
    val pending: ai.nova.app.reminders.PendingStore by lazy { ai.nova.app.reminders.PendingStore(context.applicationContext) }

    /** Шаги с телефона (системный счётчик): только по согласию, только локально. */
    val stepSync: ai.nova.app.health.StepSync by lazy { ai.nova.app.health.StepSync(context.applicationContext, store.habits, time) }

    init {
        // Шаги обновляем на старте: настройки к этому моменту прочитаны.
        appScope.launch {
            settings.loaded.first { it }
            refreshHealth()
        }
        // И каждый раз, когда приложение снова выходит на экран: счётчик читаем только пока оно живо.
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_START) appScope.launch { refreshHealth() }
                },
            )
        }
    }

    /** Обновить шаги, если человек разрешил. Молча: не вышло — значит, не вышло. */
    suspend fun refreshHealth() {
        if (!settings.current().healthSync) return
        runCatching { stepSync.sync() }
    }

    /** Здоровье и привычки: «Мой день» знает, сколько напоминаний сегодня пропущено. */
    val health: ai.nova.core.health.Habits by lazy { ai.nova.core.health.Habits(store.habits, time) { missedToday() } }

    /** Сколько сегодняшних напоминаний сработало, но так и осталось без «Готово». */
    private suspend fun missedToday(): Int = pending.countToday(time.today(), time.zone())

    /** «Потрачено: …» за день (без доходов) — для итогов дня и вечерней сводки. */
    private suspend fun daySpend(day: java.time.LocalDate): String? =
        store.expenses.between(day, day).filter { it.category != ai.nova.core.nlp.ExpenseCategories.INCOME }.takeIf { it.isNotEmpty() }
            ?.let { list -> "Потрачено: " + list.groupBy { it.currency }.entries.joinToString(", ") { (cur, items) -> ai.nova.core.nlp.Money.format(items.sumOf { it.amountMinor }, cur) } + "." }

    /** Текст вечерней сводки: те же «итоги дня», что и по голосовой команде. */
    suspend fun eveningSummary(): String {
        refreshHealth()
        val zone = time.zone()
        val today = time.today()
        val blocks = settings.current().briefBlocks
        val done = store.tasks.all().count { it.completedAt?.atZone(zone)?.toLocalDate() == today }
        val summary = health.daySummaryText(done, daySpend(today), blocks)
        if (!ai.nova.core.health.BriefBlocks.on(blocks, ai.nova.core.health.BriefBlocks.IDEAS)) return summary
        val ideas = store.notes.all().count {
            it.kind == ai.nova.core.model.NoteKind.IDEA && it.createdAt.atZone(zone).toLocalDate() == today
        }
        val ideasLine = if (ideas > 0) "Идей записано: $ideas." else ""
        return listOf(summary, ideasLine).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun assistantSettings(): AssistantSettings = settings.settings.value.let {
        // «Облачный AI» — главный выключатель: пока он выключен, вопросы решаются на телефоне,
        // даже если облако Нова доступно. Иначе каждая фраза сначала ждала сеть.
        AssistantSettings(
            it.assistantName, it.useAI, it.dialogModeEnabled, locked = isLocked() || appLocked(),
            userName = it.userName.takeIf { n -> n.isNotBlank() }, city = it.city.takeIf { c -> c.isNotBlank() },
            lockPolicy = lockPolicy(),
            persona = ai.nova.core.assistant.Persona.of(it.persona),
            newsScope = ai.nova.core.skills.NewsScope.of(it.newsScope),
            newsCity = it.newsCity.takeIf { c -> c.isNotBlank() } ?: it.city.takeIf { c -> c.isNotBlank() },
            newsTopics = ai.nova.core.skills.NewsQuery.topicsOf(it.newsTopics),
        )
    }

    // --- Голос ---
    val voskModels = VoskModelManager(context)
    val voskEngine = VoskEngine(voskModels)
    val systemStt = AndroidSpeechRecognizerProvider(context)
    val offlineStt = VoskSpeechProvider(voskEngine, voskModels)
    /** Синтезатор речи подключается только когда понадобится (не при запуске из будильника/синхронизации). */
    private val ttsLazy = lazy { AndroidTtsProvider(
            context,
            rate = { settings.settings.value.speechRate },
            pitch = { settings.settings.value.speechPitch },
            voiceName = { settings.settings.value.voiceName },
            engineName = { settings.settings.value.ttsEngine },
            autoEngine = { settings.settings.value.ttsAutoEngine },
            saveAutoEngine = { settings.setTtsAutoEngine(it) },
        ) }
    val tts: AndroidTtsProvider get() = ttsLazy.value
    /** Встроенный «Голос Нова»: скачиваемые русские голоса и офлайн-синтез. */
    val novaVoiceModels = ai.nova.app.voice.NovaVoiceModels(context)
    val novaVoice = ai.nova.app.voice.NovaVoice(
        novaVoiceModels,
        // Род ассистента решает, каким голосом он говорит: мужское имя — мужской голос и наоборот.
        voiceId = {
            val s = settings.settings.value
            novaVoiceModels.effectiveFor(s.novaVoice, ai.nova.core.nlp.Gender.ofAssistant(s.assistantName))
        },
        rate = { settings.settings.value.speechRate },
        pitch = { settings.settings.value.speechPitch },
        volume = { settings.settings.value.speechVolume },
    )
    /** Кто озвучивает ответы: встроенный голос или проверенный синтезатор телефона. */
    init {
        // Если приложение закрыли во время приглушения музыки, возвращаем громкость на место.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { ai.nova.app.media.MusicDucker.restoreStale(context) }
    }

    val speech = ai.nova.app.voice.SpeechOutput(
        system = { tts }, systemCreated = { ttsLazy.isInitialized() }, nova = novaVoice,
        mode = {
            when (settings.settings.value.voiceMode) {
                "nova" -> ai.nova.app.voice.SpeechOutput.Mode.NOVA
                "system" -> ai.nova.app.voice.SpeechOutput.Mode.SYSTEM
                else -> ai.nova.app.voice.SpeechOutput.Mode.AUTO
            }
        },
        // Пока Нова говорит, музыка тише: иначе её слова тонут в песне.
        duck = { on ->
            if (on) ai.nova.app.media.MusicDucker.request(context, "speech")
            else ai.nova.app.media.MusicDucker.release(context, "speech")
        },
    )
    val voice: VoiceController by lazy {
        VoiceController(
            engine, settings.settings, systemStt, offlineStt, speech, appScope,
            dictationKeepAlive = object : ai.nova.app.voice.DictationKeepAlive {
                override fun start() = ai.nova.app.voice.DictationService.start(context)
                override fun stop() = ai.nova.app.voice.DictationService.stop(context)
            },
        ).also { v ->
            // Пока микрофон слушает, музыка приглушается: голос ловится без крика.
            appScope.launch {
                v.state.collect { st ->
                    if (st is ai.nova.app.voice.VoiceState.Listening) ai.nova.app.media.MusicDucker.request(context, "listen")
                    else ai.nova.app.media.MusicDucker.release(context, "listen")
                    // Волна в виджете «дышит», пока Нова слушает, — как орб на главном экране.
                    ai.nova.app.widget.WidgetRender.wave(context, appScope, st is ai.nova.app.voice.VoiceState.Listening)
                }
            }
            val stopWords = ai.nova.app.voice.StopWordWatcher(context, voskEngine, voskModels)
            v.stopWatcher = { bargeIn -> stopWords.awaitInterruption(bargeIn) }
            v.understands = { engine.understandsLocally(it) }
            v.onStop = { offlineLlm.stop() }
            v.onVoiceReply = { reply ->
                // Экран заблокирован или приложение свёрнуто — результат придёт уведомлением.
                if (reply.text.isNotBlank() && (isLocked() || !launcher.isForeground())) {
                    ai.nova.app.reminders.Notifications.showResult(context, reply.text)
                }
            }
            v.systemDialogAvailable = {
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
            }
        }
    }

    /** Голосовые заметки со звуком: запись, расшифровка Vosk, прослушивание. */
    val voiceMemo: ai.nova.app.voice.VoiceMemoRecorder by lazy {
        ai.nova.app.voice.VoiceMemoRecorder(
            context, appScope, notes = { store.notes }, vosk = voskEngine, models = voskModels,
            // Ждём, пока голосовой разговор закончится (команда выполнена, ответ произнесён).
            busySpeaking = { voice.state.value.let { it !is ai.nova.app.voice.VoiceState.Idle && it !is ai.nova.app.voice.VoiceState.Error } },
        )
    }

    /** Заготовка начала фразы из ярлыка («Запиши расход »): чат подставит её в поле ввода и сбросит. */
    val quickDraft = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Хранилище ключей Android временно недоступно: база не тронута, интерфейс предлагает «Повторить». */
    private val _storeProblem = kotlinx.coroutines.flow.MutableStateFlow(false)
    val storeProblem: kotlinx.coroutines.flow.StateFlow<Boolean> = _storeProblem
    private var storeListenersAttached = false

    /** Открывает БД вне главного потока и подписывается на изменения (синхронизация, виджеты). */
    private fun warmStoreAsync() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val s = store
                _storeProblem.value = false
                synchronized(this@AppContainer) {
                    if (storeListenersAttached) return@launch
                    storeListenersAttached = true
                }
                s.changes.addListener { if (auth.state.value is AuthState.SignedIn) syncScheduler.requestSoon() }
                // Виджеты «Задачи на сегодня» и «Покупки» обновляются сразу после изменений.
                s.changes.addListener { table ->
                    if (table == "tasks" || table == "shopping_items") runCatching { ai.nova.app.widget.ListWidget.refreshAll(context) }
                }
            } catch (e: KeystoreSecretStore.KeystoreUnavailableException) {
                ai.nova.core.util.Logger.w("Store", "Хранилище ключей недоступно — жду повтора", e)
                _storeProblem.value = true
            }
        }
    }

    /** Кнопка «Повторить» на экране о недоступном хранилище. */
    fun retryStore() = warmStoreAsync()

    /** Завершается, когда сессия и настройки загружены (важно для холодного старта из WorkManager/Receiver). */
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() = ready.await()
    private val _started = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Сессия восстановлена — можно показывать интерфейс (без мелькания экрана входа). */
    val started: kotlinx.coroutines.flow.StateFlow<Boolean> = _started

    init {
        ai.nova.app.notify.NovaNotificationListener.onNewMessage = { driving.onMessage(it) }
        warmStoreAsync()
        appScope.launch {
            auth.restore()
            if (settings.current().localOnly && auth.state.value !is AuthState.SignedIn) auth.useLocalOnly()
            ready.complete(Unit)
            _started.value = true
            updates.schedulePeriodic()
            ai.nova.app.reminders.MorningBrief.schedule(context, settings.current().morningBrief)
            ai.nova.app.reminders.EveningBrief.schedule(context, settings.current().eveningBrief)
            ai.nova.app.reminders.WeeklyReview.schedule(context, settings.current().weeklyReview)
            ai.nova.app.backup.AutoBackup.schedule(context)
            ai.nova.app.reminders.ReminderWatchdog.schedule(context)
            ai.nova.app.quick.QuickInput.sync(context, settings.current().quickInput)
            if (auth.state.value is AuthState.SignedIn) {
                syncScheduler.schedulePeriodic()
                syncScheduler.requestSoon(1)
            }
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { rescheduleReminders() }
        // Уборка старых задач агента (старше суток) — состояние готовки и найденное не растёт бесконечно.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { agentCore.vacuum() } }
        // Экран выключили — разговор забывается: прочитанные сообщения и прочее личное не остаются в контексте.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: android.content.Intent) {
                        if (engineLazy.isInitialized()) appScope.launch { engine.forgetConversation() }
                    }
                },
                android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        // Первый запуск: если по-русски говорить нечем, голос Нова скачивается сам — только по Wi‑Fi.
        appScope.launch {
            awaitReady()
            kotlinx.coroutines.delay(4_000)
            val s = settings.current()
            val unmetered = context.getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
                cm.activeNetwork != null && !cm.isActiveNetworkMetered
            } == true
            if (s.ttsEnabled && unmetered && !novaVoiceModels.anyReady() && runCatching { speech.needsNovaVoice() }.getOrDefault(false)) {
                novaVoiceModels.download(s.novaVoice)
            }
        }
    }

    /** [afterBoot] — телефон перезагрузился: будильники и зоны мест сброшены системой. */
    suspend fun rescheduleReminders(afterBoot: Boolean = false) {
        runCatching { timers.rescheduleAll(afterBoot) }
        runCatching { alarms.rescheduleAll() }
        runCatching { geo.registerAll(force = afterBoot) }
        runCatching { ai.nova.app.reminders.SleepMode.reschedule(context) }
        reminderScheduler.rescheduleAll(store.reminders.active())
    }

    /** Выход: отправляем несинхронизированное, затем стираем локальные данные этого аккаунта. */
    suspend fun signOut(): Boolean {
        if (store.pendingChanges() > 0) {
            val report = syncEngine.sync()
            if (!report.ok) return false
        }
        store.reminders.active().forEach { reminderScheduler.cancel(it.id) }
        syncScheduler.cancelAll()
        auth.signOut()
        store.wipe()
        engine.context.reset()
        return true
    }

    suspend fun forceSignOutDiscardingLocal() {
        store.reminders.active().forEach { reminderScheduler.cancel(it.id) }
        syncScheduler.cancelAll()
        auth.signOut()
        store.wipe()
        engine.context.reset()
    }

    fun onSignedIn() {
        syncScheduler.schedulePeriodic()
        syncScheduler.requestSoon(0)
    }
}
