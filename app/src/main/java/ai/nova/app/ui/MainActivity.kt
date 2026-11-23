package ai.nova.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.nova.app.AppContainer
import ai.nova.app.NovaApp
import ai.nova.app.settings.ThemeMode
import ai.nova.app.ui.nav.Navigator
import ai.nova.app.ui.nav.Tab
import ai.nova.app.ui.nav.rememberNavigator
import ai.nova.app.ui.screens.AuthScreen
import ai.nova.app.ui.screens.ExpensesScreen
import ai.nova.app.ui.screens.HistoryScreen
import ai.nova.app.ui.screens.HomeScreen
import ai.nova.app.ui.screens.HomeViewModel
import ai.nova.app.ui.screens.PlansScreen
import ai.nova.app.ui.screens.RecordsScreen
import ai.nova.app.ui.screens.SettingsPage
import ai.nova.app.ui.screens.SettingsScreen
import ai.nova.app.ui.theme.NovaTheme
import ai.nova.app.voice.WakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as NovaApp).container

    /** Запрос «начать слушать» от ярлыка или жеста ассистента. */
    private val listenRequest = MutableStateFlow(0)
    /** Открыть настройки (из системных настроек телефона — «Настройки в приложении»). */
    private val openSettingsRequest = MutableStateFlow(0)
    /** Открыть экран резервной копии («Нова, сделай резервную копию»). */
    private val openBackupRequest = MutableStateFlow(0)
    /** Ярлык «Расход / Задача / Заметка» с долгого нажатия на значок: открыть чат с готовым началом фразы. */
    private val quickRequest = MutableStateFlow(0)
    /** Уведомление со сводкой по расписанию: открыть чат, где лежит весь текст сводки. */
    private val openChatRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // После пересоздания (поворот экрана) исходный intent не должен снова включать микрофон.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Цвет значков строки состояния — под выбранную в приложении тему, а не только системную.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            // Защита от скриншотов, записи экрана и показа в «недавних» — по настройке пользователя.
            androidx.compose.runtime.LaunchedEffect(settings.secureScreen) {
                if (settings.secureScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            NovaTheme(settings.themeMode, settings.dynamicColor, settings.accent) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                        // Фон всей программы: органические формы, подкрашенные выбранным цветом.
                        ai.nova.app.ui.components.OrganicBackground()
                        NovaRoot(container, listenRequest, openSettingsRequest, openBackupRequest, quickRequest, openChatRequest)
                        ai.nova.app.ui.components.VoiceMemoOverlay(container)
                        ai.nova.app.ui.components.WhatsNewAutoShow()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Фоновое прослушивание можно (пере)запустить только когда приложение на экране (Android 14+).
        lifecycleScope.launch {
            val s = container.settings.current() // не значение по умолчанию до загрузки DataStore
            if (s.wakeWordEnabled && container.voskModels.isObtainable() && !WakeWordService.running) WakeWordService.start(this@MainActivity)
            // Проверка новой версии не чаще раза в 6 часов; установка — по кнопке на главной или автоматически.
            if (s.autoUpdate && System.currentTimeMillis() - container.updates.lastCheckedAt > 6 * 3600_000L) container.updates.check()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN, "android.intent.action.SEARCH_LONG_PRESS" ->
                listenRequest.value = listenRequest.value + 1
            // Уведомление «Доступна новая версия»: сразу скачиваем и ставим.
            // Только из нашего уведомления: другое приложение не знает секрет и не может запустить установку.
            ACTION_UPDATE -> if (intent.getStringExtra(EXTRA_TOKEN) == intentToken(this)) lifecycleScope.launch {
                val info = container.updates.check() ?: return@launch
                if (!container.updates.canInstall()) startActivity(container.updates.installPermissionIntent())
                else container.updates.downloadAndInstall(info)
            }
            // «Настройки приложения» из системных настроек телефона.
            Intent.ACTION_APPLICATION_PREFERENCES -> openSettingsRequest.value = openSettingsRequest.value + 1
            ACTION_BACKUP -> openBackupRequest.value = openBackupRequest.value + 1
            ACTION_VOICE_MEMO -> container.voiceMemo.start()
            // Уведомление со сводкой (утро, вечер, итоги недели, сценарий): сразу в чат, где вся сводка.
            ACTION_OPEN_CHAT -> openChatRequest.value = openChatRequest.value + 1
            ACTION_QUICK -> {
                container.quickDraft.value = when (intent.getStringExtra("kind")) {
                    "expense" -> "Запиши расход "
                    "task" -> "Добавь задачу "
                    "note" -> "Запиши заметку "
                    else -> null
                }
                quickRequest.value = quickRequest.value + 1
            }
        }
    }

    companion object {
        const val ACTION_LISTEN = "ai.nova.action.LISTEN"
        const val ACTION_UPDATE = "ai.nova.action.UPDATE"
        const val ACTION_BACKUP = "ai.nova.action.BACKUP"
        const val ACTION_QUICK = "ai.nova.action.QUICK"
        const val ACTION_VOICE_MEMO = "ai.nova.action.VOICE_MEMO"
        const val ACTION_OPEN_CHAT = "ai.nova.action.OPEN_CHAT"
        const val EXTRA_TOKEN = "ai.nova.token"

        /** Случайный секрет этой установки для своих уведомлений. */
        fun intentToken(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("nova_intents", android.content.Context.MODE_PRIVATE)
            return prefs.getString("token", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("token", it).apply() }
        }
    }
}

/** Системное окно распознавания Google — запасной вариант, если встроенное распознавание не работает. */
@Composable
fun rememberSystemSpeechDialog(c: AppContainer): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        c.voice.onSystemDialogResult(text)
    }
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    val show = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите, $name слушает")
        }
        runCatching { launcher.launch(intent) }.onFailure { c.voice.onSystemDialogResult(null) }
        Unit
    }
    // Окно показывает только экран, который сейчас виден (главный экран или окно ассистента).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { c.voice.systemDialogRequests.collect { show() } }
    }
    return show
}

/** Показывает системные вопросы о разрешениях, которые команды просят «по ходу дела» (контакты, календарь). */
@Composable
fun PermissionRequests(c: AppContainer) {
    var current by remember { mutableStateOf<ai.nova.app.device.PermissionBroker.Request?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        current?.result?.complete(result.isNotEmpty() && result.values.all { it })
        current = null
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            c.permissions.requests.collect { r ->
                current?.result?.complete(false)
                current = r
                runCatching { launcher.launch(r.permissions.toTypedArray()) }.onFailure { r.result.complete(false); current = null }
            }
        }
    }
}

/** Показывать ли подсказку про доступ к микрофону (после отказа в разрешении). */
private val micDenied = mutableStateOf(false)

/** Запрос микрофона и запуск прослушивания. */
@Composable
fun rememberListenAction(c: AppContainer): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pending) c.voice.startListening()
        if (!granted) micDenied.value = true
        pending = false
    }
    MicDeniedDialog()
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            c.voice.startListening()
        } else {
            pending = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Отказ в доступе к микрофону: объясняем, что именно включить, и ведём прямо в настройки приложения. */
@Composable
private fun MicDeniedDialog() {
    if (!micDenied.value) return
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { micDenied.value = false },
        title = { Text("Нова не слышит без микрофона") },
        text = {
            Text(
                "Разрешение на микрофон выключено. Откройте «Настройки» → «Приложения» → «Нова» → " +
                    "«Разрешения» → «Микрофон» и выберите «Разрешить». Пока доступ закрыт, можно писать текстом.",
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                micDenied.value = false
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            }) { Text("Открыть настройки") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { micDenied.value = false }) { Text("Позже") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovaRoot(c: AppContainer, listenRequest: MutableStateFlow<Int>, openSettingsRequest: MutableStateFlow<Int>, openBackupRequest: MutableStateFlow<Int>, quickRequest: MutableStateFlow<Int>, openChatRequest: MutableStateFlow<Int>) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var showAuth by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val listen = rememberListenAction(c)
    rememberSystemSpeechDialog(c)
    PermissionRequests(c)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val started by c.started.collectAsStateWithLifecycle()
    val loaded by c.settings.loaded.collectAsStateWithLifecycle()
    if (!started || !loaded) {
        Box(Modifier.fillMaxSize()) // доли секунды при запуске: сессия и настройки ещё читаются
        return
    }

    // Хранилище ключей Android временно недоступно (бывает сразу после загрузки телефона): базу не трогаем и просим повторить.
    val storeProblem by c.storeProblem.collectAsStateWithLifecycle()
    if (storeProblem) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Хранилище ключей Android сейчас недоступно", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ваши записи целы и не тронуты. Подождите несколько секунд и нажмите «Повторить»; если не помогает — перезагрузите телефон.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp),
            )
            ai.nova.app.ui.components.PrimaryButton("Повторить", { c.retryStore() })
        }
        return
    }

    // Вход по отпечатку/PIN, если включён в «Доступ и безопасность».
    val appUnlocked by c.appLock.unlocked.collectAsStateWithLifecycle()
    if (settings.appLock && !appUnlocked && c.appLock.deviceSecure()) {
        LockGate(c)
        return
    }

    // Вход в аккаунт не обязателен: Нова сразу работает на телефоне, синхронизацию можно включить в «Настройки → Аккаунт».
    val needsAuth = showAuth
    if (needsAuth) {
        BackHandler(enabled = showAuth) { showAuth = false }
        AuthScreen(c, onClose = if (showAuth) ({ showAuth = false }) else null) {
            showAuth = false
            c.appScope.launch { c.settings.setOnboardingDone(true) }
        }
        return
    }

    val nav = rememberNavigator()
    // Выбранные разделы внутри вкладок — общие, чтобы главная могла открыть, например, «Напоминания».
    val recordsSegment = rememberSaveable { mutableIntStateOf(0) }
    val plansSegment = rememberSaveable { mutableIntStateOf(0) }

    val request by listenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            listenRequest.value = 0 // запрос обработан — не повторять при возврате в композицию
            nav.showRoot(Tab.HOME)
            listen()
        }
    }
    val settingsRequest by openSettingsRequest.collectAsStateWithLifecycle()
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) {
            openSettingsRequest.value = 0
            nav.showRoot(Tab.SETTINGS)
        }
    }
    val quick by quickRequest.collectAsStateWithLifecycle()
    LaunchedEffect(quick) {
        if (quick > 0) {
            quickRequest.value = 0
            nav.showRoot(Tab.HOME)
            nav.open("chat/input")
        }
    }
    val chatRequest by openChatRequest.collectAsStateWithLifecycle()
    LaunchedEffect(chatRequest) {
        if (chatRequest > 0) {
            openChatRequest.value = 0
            nav.showRoot(Tab.HOME)
            nav.open("chat")
        }
    }
    val backupRequest by openBackupRequest.collectAsStateWithLifecycle()
    LaunchedEffect(backupRequest) {
        if (backupRequest > 0) {
            openBackupRequest.value = 0
            nav.showRoot(Tab.SETTINGS)
            nav.open(SettingsPage.BACKUP.route, Tab.SETTINGS)
        }
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    // Пока открыта клавиатура, нижнее меню прячется, а поле ввода встаёт прямо над клавиатурой.
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        // Фон рисуется под всем приложением — панели прозрачные, чтобы формы были видны.
        containerColor = Color.Transparent,
        // Чат открыт на весь экран — как отдельное окно, без нижнего меню.
        bottomBar = { if (!imeVisible && nav.route?.startsWith("chat") != true) BottomBar(nav) },
    ) { padding ->
        val holder = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = nav.tab to nav.route,
            transitionSpec = {
                val (fromTab, fromRoute) = initialState
                val (toTab, toRoute) = targetState
                when {
                    // Чат выезжает снизу, как отдельное окно, и уезжает вниз.
                    toRoute?.startsWith("chat") == true && fromRoute == null ->
                        (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(220))) togetherWith fadeOut(tween(200))
                    fromRoute?.startsWith("chat") == true && toRoute == null ->
                        fadeIn(tween(220)) togetherWith (slideOutVertically(tween(280)) { it / 2 } + fadeOut(tween(220)))
                    // Вложенный экран открывается справа, «Назад» — уезжает вправо.
                    fromTab == toTab && fromRoute == null && toRoute != null ->
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(180)))
                    fromTab == toTab && fromRoute != null && toRoute == null ->
                        (slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(180)))
                    // Смена вкладки — мягкое проявление с лёгким масштабом.
                    else -> (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith fadeOut(tween(140))
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            label = "screens",
        ) { (tab, route) ->
            holder.SaveableStateProvider("${tab.name}/${route.orEmpty()}") {
                // На планшете и в альбомной ориентации содержимое не растягивается во всю ширину.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth()) {
                    Screen(c, nav, tab, route, listen, recordsSegment.intValue, { recordsSegment.intValue = it },
                        plansSegment.intValue, { plansSegment.intValue = it }, onOpenAuth = { showAuth = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(
    c: AppContainer,
    nav: Navigator,
    tab: Tab,
    route: String?,
    listen: () -> Unit,
    recordsSegment: Int,
    setRecordsSegment: (Int) -> Unit,
    plansSegment: Int,
    setPlansSegment: (Int) -> Unit,
    onOpenAuth: () -> Unit,
) {
    val back: () -> Unit = { nav.back() }
    when (tab) {
        Tab.HOME -> {
            val vm: HomeViewModel = viewModel { HomeViewModel(c) }
            if (route?.startsWith("chat") == true) {
                ai.nova.app.ui.screens.ChatScreen(
                    vm, onMic = listen, onBack = back, keyboard = route == "chat/input",
                    onOpenRecord = { type ->
                        // «Открыть» из ответа ведёт в раздел, где лежит созданная запись.
                        when (type) {
                            ai.nova.core.model.RecordType.EXPENSE -> nav.showRoot(Tab.EXPENSES)
                            ai.nova.core.model.RecordType.TASK, ai.nova.core.model.RecordType.REMINDER -> { setPlansSegment(0); nav.showRoot(Tab.PLANS) }
                            else -> { setRecordsSegment(0); nav.showRoot(Tab.RECORDS) }
                        }
                    },
                )
                return
            }
            HomeScreen(
                vm,
                onMic = listen,
                openRecords = { segment -> setRecordsSegment(segment); nav.showRoot(Tab.RECORDS) },
                openPlans = { segment -> setPlansSegment(segment); nav.showRoot(Tab.PLANS) },
                openExpenses = { nav.showRoot(Tab.EXPENSES) },
                openSettings = { page -> if (page == null) nav.showRoot(Tab.SETTINGS) else { nav.showRoot(Tab.SETTINGS); nav.open(page, Tab.SETTINGS) } },
                openHistory = { nav.open("history", Tab.RECORDS) },
                openChat = { keyboard -> nav.open(if (keyboard) "chat/input" else "chat") },
            )
        }
        Tab.RECORDS -> when (route) {
            "history" -> HistoryScreen(c, back)
            else -> RecordsScreen(c, recordsSegment, setRecordsSegment, openHistory = { nav.open("history") })
        }
        Tab.PLANS -> PlansScreen(c, plansSegment, setPlansSegment)
        Tab.EXPENSES -> ExpensesScreen(c)
        Tab.SETTINGS -> {
            val providerId = route?.takeIf { it.startsWith("settings/ai/") }?.removePrefix("settings/ai/")
            val provider = providerId?.let { ai.nova.core.ai.AIProviderType.fromIdOrNull(it) }
            if (provider != null) {
                ai.nova.app.ui.screens.ProviderScreen(c, provider, back)
            } else {
                val page = route?.let { r -> SettingsPage.entries.firstOrNull { it.route == r } }
                SettingsScreen(c, page, open = { p -> nav.open(p.route) }, openRoute = { nav.open(it) }, onBack = back, onOpenAuth = onOpenAuth)
            }
        }
    }
}

@Composable
private fun BottomBar(nav: Navigator) {
    Column {
        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = ai.nova.app.ui.components.organicPanel(0.90f), tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                val selected = nav.tab == tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { nav.select(tab) },
                    icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(22.dp)) },
                    label = { Text(tab.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/** Экран «Нова заблокирована»: системное окно отпечатка/PIN открывается сразу. */
@Composable
private fun LockGate(c: AppContainer) {
    val activity = LocalActivity.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) c.appLock.markUnlocked()
    }
    val ask = { activity?.let { a -> c.appLock.authenticate(a) { intent -> legacy.launch(intent) } } }
    LaunchedEffect(Unit) { ask() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        ai.nova.app.ui.components.AssistantOrb(ai.nova.app.ui.components.OrbMode.IDLE, 0f, size = 140.dp)
        Text("Нова заблокирована", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text("Подтвердите, что это вы", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ai.nova.app.ui.components.PrimaryButton("Разблокировать", { ask() }, modifier = Modifier.padding(top = 24.dp))
    }
}
