package ai.nova.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.notify.NovaNotificationListener
import ai.nova.app.settings.BankPushMode
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.groupColor
import ai.nova.app.ui.components.rememberResumeTick
import ai.nova.app.ui.screens.isBatteryExempt
import ai.nova.app.ui.screens.requestBatteryExemption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Один слайд знакомства: что даёт разрешение и где потом это выключить. */
private class Slide(
    val icon: ImageVector,
    val title: String,
    val text: String,
    /** Что человек сможет поменять позже — внутри Нова и в настройках телефона. */
    val where: String,
    val allowed: Boolean,
    val allowLabel: String,
    val allow: () -> Unit,
)

/**
 * Первый запуск: слайды про каждое разрешение — зачем оно нужно, что даёт и где его выключить.
 * Раньше на главном экране просто висела кнопка «Настроить всё», и человек не понимал,
 * что именно разрешает. Знакомство показывается один раз, всё включённое здесь можно выключить.
 */
@Composable
fun OnboardingFlow(c: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val name = settings.assistantName
    var refresh by remember { mutableIntStateOf(0) }
    val tick = rememberResumeTick()
    val scope = rememberCoroutineScope()

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // Разрешили микрофон — включаем и ожидание обращения по имени, если модель распознавания готова.
        if (ok) scope.launch { c.settings.setWakeWord(c.voskModels.isObtainable()) }
        refresh++
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { c.settings.setHealthSync(true) }
        refresh++
    }
    val notificationsPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    fun openSystem(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val slides = remember(tick, refresh, settings.wakeWordEnabled, settings.healthSync, settings.bankPushes, name) {
        buildList {
            add(
                Slide(
                    Icons.Rounded.WavingHand,
                    "Привет! Я $name",
                    "Ставлю напоминания, записываю траты, заметки и идеи, строю маршруты, включаю музыку "
                        + "и отвечаю на вопросы — голосом или текстом. Работаю и без интернета, данные храню на телефоне.",
                    "Дальше — пара разрешений. Каждое можно выключить в любой момент.",
                    allowed = true,
                    allowLabel = "",
                    allow = {},
                ),
            )
            add(
                Slide(
                    Icons.Rounded.Mic,
                    "Отвечать голосом, когда руки заняты",
                    "Для этого нужен микрофон: $name слушает команду, пока вы говорите, и может ждать обращение «$name» "
                        + "без нажатия на экран. Распознавание идёт на телефоне — записи никуда не отправляются.",
                    "Выключить: «Настройки» → «Голос и речь» → «Слышать «$name» без нажатия», а ещё в настройках телефона: "
                        + "«Приложения» → «$name» → «Разрешения» → «Микрофон».",
                    allowed = granted(Manifest.permission.RECORD_AUDIO),
                    allowLabel = "Разрешить микрофон",
                ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            if (notificationsPermission != null) add(
                Slide(
                    Icons.Rounded.Alarm,
                    "Напоминания и сводки",
                    "Уведомления нужны, чтобы напоминание прозвучало вовремя, а утренняя и вечерняя сводка пришли сами. "
                        + "Полный текст сводки $name оставляет в истории общения — его можно перечитать.",
                    "Выключить: «Настройки» → «Уведомления» → «Напоминания», а ещё в настройках телефона: "
                        + "«Приложения» → «$name» → «Уведомления».",
                    allowed = granted(notificationsPermission),
                    allowLabel = "Разрешить уведомления",
                ) { notificationsLauncher.launch(notificationsPermission) },
            )
            add(
                Slide(
                    Icons.Rounded.Receipt,
                    "Записывать расходы из пушей банка",
                    "Если разрешить чтение уведомлений, $name увидит пуш банка и предложит записать трату — "
                        + "или запишет сразу, как выберете. Читаются только уведомления банков, всё остаётся на телефоне.",
                    "Выключить: «Настройки» → «Расходы» → «Пуши банков» → «Выключено», а ещё в настройках телефона: "
                        + "«Уведомления» → «Доступ к уведомлениям» → «$name».",
                    allowed = NovaNotificationListener.enabled(context),
                    allowLabel = "Открыть доступ к уведомлениям",
                ) {
                    openSystem(NovaNotificationListener.settingsIntent(context))
                    scope.launch { if (settings.bankPushes == BankPushMode.OFF) c.settings.setBankPushes(BankPushMode.ASK) }
                },
            )
            add(
                Slide(
                    Icons.Rounded.DirectionsWalk,
                    "Шаги с телефона",
                    "Разрешение «Физическая активность» позволяет читать счётчик шагов — тогда в «Мой день» попадут "
                        + "шаги за сегодня. Счётчик читается на телефоне, без сети и без отправки куда-либо.",
                    "Выключить: «Записи» → «Здоровье» → «Шаги с телефона».",
                    allowed = !needsActivityPermission(context) && settings.healthSync,
                    allowLabel = "Разрешить шаги",
                ) {
                    if (needsActivityPermission(context)) activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    else scope.launch { c.settings.setHealthSync(true) }
                },
            )
            add(
                Slide(
                    Icons.Rounded.BatteryChargingFull,
                    "Чтобы напоминания не терялись",
                    "Телефон экономит батарею и иногда усыпляет приложения — тогда будильник молчит. "
                        + "Разрешение работать в фоне убирает эту проблему.",
                    "Выключить: настройки телефона → «Приложения» → «$name» → «Батарея» → «Без ограничений».",
                    allowed = isBatteryExempt(context),
                    allowLabel = "Разрешить работу в фоне",
                ) { requestBatteryExemption(context) },
            )
            add(
                Slide(
                    Icons.Rounded.CheckCircle,
                    "Всё готово",
                    "Разрешения можно менять когда угодно — в «Настройках» Нова и в настройках телефона. "
                        + "Ничего не включено «навсегда» и без вашего ведома.",
                    "Совет: спросите вслух «что ты умеешь» — покажу остальное.",
                    allowed = true,
                    allowLabel = "",
                    allow = {},
                ),
            )
        }
    }

    val pager = rememberPagerState { slides.size }
    val last = pager.currentPage == slides.lastIndex

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Знакомство · ${pager.currentPage + 1} из ${slides.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) { Text("Пропустить") }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val slide = slides[index]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(shape = CircleShape, color = groupColor(), modifier = Modifier.size(96.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(slide.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(slide.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        slide.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = groupColor(), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            slide.where,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    if (slide.allowLabel.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        if (slide.allowed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Разрешено", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            SecondaryButton(slide.allowLabel, slide.allow, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(slides.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == pager.currentPage) 10.dp else 7.dp)
                            .background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
                PrimaryButton(
                    if (last) "Начать" else "Дальше",
                    {
                        if (last) onDone()
                        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Android 10+ требует разрешение «Физическая активность» для счётчика шагов. */
private fun needsActivityPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED

/**
 * Пройдено ли знакомство. Пока нет — «Что нового» не всплывает: при первом запуске человек сначала
 * знакомится с разрешениями, а новости обновления посмотрит уже в приложении.
 */
object FirstRun {
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** Пройдено ли знакомство — читается без подписки, чтобы экран сразу знал ответ. */
    fun seen(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEN, false)

    fun read(context: Context) {
        _done.value = prefs(context).getBoolean(KEY_SEEN, false)
    }

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply()
        _done.value = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences("nova_ui", Context.MODE_PRIVATE)
    private const val KEY_SEEN = "onboarding_seen"
}

/**
 * Показывает знакомство один раз — при самом первом запуске на телефоне.
 * Флаг лежит в обычных настройках экрана: сброс данных приложения возвращает знакомство.
 */
@Composable
fun FirstRunOnboarding(c: AppContainer) {
    val context = LocalContext.current
    var settingsReady by remember { mutableStateOf(false) }
    val done by FirstRun.done.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        FirstRun.read(context)
        c.settings.loaded.first { it }
        settingsReady = true
    }
    if (done || !settingsReady) return
    OnboardingFlow(c) { FirstRun.markSeen(context) }
}
