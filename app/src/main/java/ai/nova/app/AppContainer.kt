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

    val http = HttpClient(Android) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 90_000
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
    val geo = ai.nova.app.reminders.GeoReminders(context)
    /** Дыхание и медитация голосом; «я за рулём» — сообщения вслух. */
    val relaxation = ai.nova.app.health.Relaxation(appScope) { speech.speak(it) }
    val driving = ai.nova.app.health.DrivingMode(context, appScope) { speech.speak(it) }
    val device = AndroidDeviceController(context, launcher, appAccess, permissions, fallbackReminder = { text, at ->
        val r = store.reminders.create(text, at, null, time.zone().id)
        reminderScheduler.schedule(r)
    }, timers = timers, relaxation = relaxation, driving = driving)
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
    )
    val skills = ai.nova.core.skills.Skills(skillHost, http, time)
    /** Офлайн-модель для разговора (~1 ГБ, скачивается по кнопке). */
    val offlineLlm = ai.nova.app.llm.OfflineLlm(context, appScope)
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
        localChat = offlineLlm,
        habits = ai.nova.core.health.Habits(store.habits, time),
        daySpend = ::daySpend,
        onPersona = { p -> appScope.launch { settings.setPersona(p.wire) } },
        expensesBetween = { a, b -> store.expenses.between(a, b) },
        agent = agentCore,
        cooking = store.cooking,
    ) }

    /** Ядро агента: реестр навыков, инструменты и постоянный контекст задач. */
    val agentCore: ai.nova.core.agent.AgentCore by lazy { ai.nova.core.agent.AgentCore(store.agent, time) }

    /** «Потрачено: …» за день (без доходов) — для итогов дня и вечерней сводки. */
    private suspend fun daySpend(day: java.time.LocalDate): String? =
        store.expenses.between(day, day).filter { it.category != ai.nova.core.nlp.ExpenseCategories.INCOME }.takeIf { it.isNotEmpty() }
            ?.let { list -> "Потрачено: " + list.groupBy { it.currency }.entries.joinToString(", ") { (cur, items) -> ai.nova.core.nlp.Money.format(items.sumOf { it.amountMinor }, cur) } + "." }

    /** Текст вечерней сводки: те же «итоги дня», что и по голосовой команде. */
    suspend fun eveningSummary(): String {
        val zone = time.zone()
        val today = time.today()
        val done = store.tasks.all().count { it.completedAt?.atZone(zone)?.toLocalDate() == today }
        return ai.nova.core.health.Habits(store.habits, time).daySummaryText(done, daySpend(today))
    }

    fun assistantSettings(): AssistantSettings = settings.settings.value.let {
        AssistantSettings(
            it.assistantName, it.useAI || cloudActive(), it.dialogModeEnabled, locked = isLocked() || appLocked(),
            userName = it.userName.takeIf { n -> n.isNotBlank() }, city = it.city.takeIf { c -> c.isNotBlank() },
            lockPolicy = lockPolicy(),
            persona = ai.nova.core.assistant.Persona.of(it.persona),
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
        voiceId = { novaVoiceModels.effective(settings.settings.value.novaVoice) },
        rate = { settings.settings.value.speechRate },
        pitch = { settings.settings.value.speechPitch },
    )
    /** Кто озвучивает ответы: встроенный голос или проверенный синтезатор телефона. */
    val speech = ai.nova.app.voice.SpeechOutput(
        system = { tts }, systemCreated = { ttsLazy.isInitialized() }, nova = novaVoice,
        mode = {
            when (settings.settings.value.voiceMode) {
                "nova" -> ai.nova.app.voice.SpeechOutput.Mode.NOVA
                "system" -> ai.nova.app.voice.SpeechOutput.Mode.SYSTEM
                else -> ai.nova.app.voice.SpeechOutput.Mode.AUTO
            }
        },
    )
    val voice: VoiceController by lazy {
        VoiceController(engine, settings.settings, systemStt, offlineStt, speech, appScope).also { v ->
            val stopWords = ai.nova.app.voice.StopWordWatcher(context, voskEngine, voskModels)
            v.stopWatcher = { stopWords.awaitStop() }
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
