package ai.nova.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.ui.components.BackupNudgeCard
import ai.nova.app.ui.components.CrashCard
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.IconTile
import ai.nova.app.ui.components.LinkText
import ai.nova.app.ui.components.MarkdownText
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.SetupCard
import ai.nova.app.ui.components.UpdateCard
import ai.nova.app.ui.components.bodySecondaryStyle
import ai.nova.app.ui.components.captionStyle
import ai.nova.app.ui.components.groupColor
import ai.nova.app.ui.components.messageText
import ai.nova.app.ui.components.pressScale
import ai.nova.app.ui.components.rememberResumeTick
import ai.nova.app.ui.theme.NovaRadius
import ai.nova.app.ui.theme.NovaSize
import ai.nova.app.ui.theme.NovaSpace
import ai.nova.app.ui.theme.amberColor
import ai.nova.app.ui.theme.successColor
import ai.nova.app.voice.VoiceState
import ai.nova.core.assistant.RuFormat
import ai.nova.core.model.MessageRole
import ai.nova.core.nlp.Money
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Примеры команд: нажатие выполняет команду. Порядок меняется при каждом запуске — так человек узнаёт новое. */
private val allSuggestions = listOf(
    "Какая погода?",
    "Что у меня на сегодня?",
    "Напомни через 10 минут выключить плиту",
    "Расход 500 ₽ на продукты",
    "Курс доллара",
    "Включи радио",
    "Таймер на 5 минут",
    "Добавь в покупки молоко и хлеб",
    "Новости",
    "Давай в города",
    "Расскажи сказку",
    "Кто такой Гагарин?",
    "Что ты умеешь?",
)

/** Четыре примера на главной: разные по смыслу и без повторов. */
private val suggestions: List<String> by lazy {
    (allSuggestions.shuffled().take(3) + "Что ты умеешь?").distinct()
}

/**
 * Состояние голосового помощника — то, что видит человек.
 *
 * Шесть состояний вместо одного пульсирующего шара: понятно, слушают вас сейчас,
 * распознают сказанное, думают или уже отвечают.
 */
private enum class VoiceStage { IDLE, LISTENING, RECOGNISING, PROCESSING, ANSWERING, ERROR }

private data class VoiceUi(
    val stage: VoiceStage,
    val title: String,
    val hint: String,
    val heard: String,
    val level: Float,
) {
    val active get() = stage == VoiceStage.LISTENING || stage == VoiceStage.RECOGNISING ||
        stage == VoiceStage.PROCESSING || stage == VoiceStage.ANSWERING
}

private fun voiceUi(voice: VoiceState, name: String): VoiceUi {
    val stage = when (voice) {
        VoiceState.Idle -> VoiceStage.IDLE
        is VoiceState.Listening -> if (voice.partial.isBlank()) VoiceStage.LISTENING else VoiceStage.RECOGNISING
        is VoiceState.Thinking -> VoiceStage.PROCESSING
        is VoiceState.Speaking -> VoiceStage.ANSWERING
        is VoiceState.Error -> VoiceStage.ERROR
    }
    val title = when (voice) {
        VoiceState.Idle -> "Готов слушать"
        is VoiceState.Listening -> when {
            voice.followUp -> "Слушаю дальше"
            voice.hint != null -> voice.hint
            else -> "Слушаю"
        }
        is VoiceState.Thinking -> "Думаю"
        is VoiceState.Speaking -> "Отвечаю"
        is VoiceState.Error -> "Не получилось"
    }
    val hint = when (voice) {
        VoiceState.Idle -> "Нажмите микрофон или скажите «$name»"
        is VoiceState.Listening -> "Говорите — я запишу"
        is VoiceState.Thinking -> "Разбираю запрос"
        is VoiceState.Speaking -> "Скажите «стоп», чтобы прервать"
        is VoiceState.Error -> voice.message
    }
    val heard = when (voice) {
        is VoiceState.Listening -> voice.partial
        is VoiceState.Thinking -> voice.heard
        else -> ""
    }
    return VoiceUi(stage, title, hint, heard, (voice as? VoiceState.Listening)?.level ?: 0f)
}

/**
 * Главная: приветствие, голос, сводка дня и вход в разговор.
 *
 * Порядок сверху вниз отвечает на три вопроса: кто передо мной, что можно сказать,
 * что у меня сегодня. Всё редкое (обновления, подсказки настройки) появляется только по поводу.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onMic: () -> Unit,
    openRecords: (Int) -> Unit,
    openPlans: (Int) -> Unit,
    openExpenses: () -> Unit,
    openSettings: (String?) -> Unit,
    openHistory: () -> Unit,
    openChat: (keyboard: Boolean) -> Unit,
) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui = voiceUi(voice, settings.assistantName)
    var setupShown by remember { mutableStateOf(false) }

    // Клавиатура сжимает весь экран, а не только поле ввода: строка с текстом остаётся над ней,
    // а голосовой блок при этом не уезжает под клавиатуру.
    Column(Modifier.fillMaxSize().imePadding()) {
        HomeHeader(
            name = settings.assistantName,
            cloud = vm.cloudActive(),
            online = online,
            useAI = settings.useAI,
            aiLabel = if (vm.aiConfigured()) settings.primaryProvider?.type?.title ?: "AI" else "AI: нет ключа",
            onMode = { openSettings("settings/ai") },
            onHistory = openHistory,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                    .padding(bottom = NovaSpace.l),
            ) {
                // Предупреждения — только по факту: восстановление базы, микрофон, сбой, обновление.
                RecoveryCard()
                MicAccessCard(onEnable = onMic)
                CrashCard(settings.assistantName)
                BackupNudgeCard(vm.c, settings.assistantName) { openSettings(SettingsPage.BACKUP.route) }
                UpdateCard(vm.c)
                SetupCard(vm.c, settings.assistantName, onVisible = { setupShown = it })

                VoicePanel(
                    ui = ui,
                    compact = setupShown,
                    onMic = { if (ui.active) vm.stop() else onMic() },
                    onClearError = { vm.clearError() },
                )
                SuggestionRow { vm.send(it); openChat(false) }
                TodaySection(
                    summary = summary,
                    onExpenses = openExpenses,
                    onTasks = { openPlans(0) },
                    onReminders = { openPlans(1) },
                    onExample = { openChat(true) },
                )
                LocationSection(onOpen = { openSettings(SettingsPage.ACCESS.route) })
                if (history.isNotEmpty()) {
                    ConversationTeaser(history, settings.assistantName, onOpen = { openChat(false) })
                }
            }
        }
        if (reply?.awaitingConfirmation == true) {
            ConfirmPrompt(onConfirm = { vm.confirm(it) })
        }
        HomeInputBar(onSend = { vm.send(it); openChat(false) }, onOpenChat = { openChat(true) })
    }
}

/** Верхняя строка: приветствие, имя, режим работы и история запросов. */
@Composable
private fun HomeHeader(
    name: String,
    cloud: Boolean,
    online: Boolean,
    useAI: Boolean,
    aiLabel: String,
    onMode: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = NovaSpace.screen, end = NovaSpace.s, top = NovaSpace.s, bottom = NovaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                name,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val (icon, label) = when {
            !online && (useAI || cloud) -> Icons.Outlined.CloudOff to "Офлайн"
            cloud -> Icons.Outlined.AutoAwesome to "Облако Нова"
            !useAI -> Icons.Outlined.PhoneAndroid to "На устройстве"
            else -> Icons.Outlined.AutoAwesome to aiLabel
        }
        ModeChip(label, icon, onMode)
        IconButton(onClick = onHistory) {
            Icon(Icons.Outlined.History, contentDescription = "История запросов", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Голосовой блок: один микрофон, состояние словами и уровень записи.
 *
 * Человеку не нужно угадывать, что означает пульсирующий круг: подпись говорит прямо,
 * слушают его или уже отвечают, а кнопка в любой момент останавливает запись.
 */
@Composable
private fun VoicePanel(ui: VoiceUi, compact: Boolean, onMic: () -> Unit, onClearError: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(NovaRadius.sheet),
        color = groupColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.m),
    ) {
        Column(Modifier.padding(NovaSpace.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(ui.stage, onMic)
                Column(Modifier.weight(1f).padding(start = NovaSpace.l)) {
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ui.stage == VoiceStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ui.hint,
                        style = bodySecondaryStyle(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(enabled = ui.stage == VoiceStage.ERROR, onClick = onClearError),
                    )
                }
                if (ui.stage == VoiceStage.LISTENING || ui.stage == VoiceStage.RECOGNISING) {
                    LevelMeter(ui.level, Modifier.padding(start = NovaSpace.m))
                }
            }
            AnimatedVisibility(ui.heard.isNotBlank()) {
                Text(
                    ui.heard,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = NovaSpace.m),
                )
            }
            // Подсказка о том, что можно сказать, — но не в момент, когда и так всё понятно.
            if (ui.stage == VoiceStage.IDLE && !compact) {
                Text(
                    "Например: «Расход 500 ₽ на продукты»",
                    style = captionStyle(),
                    modifier = Modifier.padding(top = NovaSpace.m),
                )
            }
        }
    }
}

/** Кнопка записи: цвет и значок говорят, что произойдёт при нажатии. */
@Composable
private fun MicButton(stage: VoiceStage, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    val (background, content) = when (stage) {
        VoiceStage.ERROR -> c.errorContainer to c.onErrorContainer
        VoiceStage.PROCESSING -> c.surfaceContainerHigh to c.onSurfaceVariant
        VoiceStage.ANSWERING -> c.primaryContainer to c.onPrimaryContainer
        else -> c.primary to c.onPrimary
    }
    // Кольцо записи: единственная постоянная анимация блока, и она означает «микрофон включён».
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "mic-pulse",
    )
    val recording = stage == VoiceStage.LISTENING || stage == VoiceStage.RECOGNISING
    Box(contentAlignment = Alignment.Center) {
        if (recording) {
            Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, amberColor().copy(alpha = pulse), CircleShape))
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = background,
            contentColor = content,
            interactionSource = interaction,
            modifier = Modifier.size(56.dp).pressScale(interaction),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (stage) {
                    VoiceStage.PROCESSING -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = c.primary)
                    VoiceStage.ANSWERING, VoiceStage.LISTENING, VoiceStage.RECOGNISING ->
                        Icon(Icons.Rounded.Stop, contentDescription = "Остановить", modifier = Modifier.size(24.dp))
                    VoiceStage.ERROR -> Icon(Icons.Rounded.ErrorOutline, contentDescription = "Ошибка", modifier = Modifier.size(24.dp))
                    VoiceStage.IDLE -> Icon(Icons.Rounded.Mic, contentDescription = "Говорить", modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/** Уровень записи: пять столбиков реагируют на голос, чтобы было видно, что микрофон слышит. */
@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "level").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "level-phase",
    )
    val color = amberColor()
    val base = 5f + level.coerceIn(0f, 1f) * 12f
    Canvas(modifier.size(width = 30.dp, height = 28.dp)) {
        val bars = 5
        val gap = size.width / (bars * 2f - 1f)
        repeat(bars) { i ->
            val wave = 0.55f + 0.45f * sin(phase + i * 0.9f)
            val h = (base * wave).coerceIn(4f, size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap * 2f, (size.height - h) / 2f),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2f),
            )
        }
    }
}

/** Примеры фраз: одно касание — и команда выполнена. Работают и без голоса. */
@Composable
private fun SuggestionRow(onSend: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = NovaSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(NovaSpace.s),
    ) {
        items(suggestions) { s -> Suggestion(s) { onSend(s) } }
    }
}

/**
 * Сводка дня: деньги, задачи, напоминания — одной строкой цифр.
 *
 * Пустое состояние — не заглушка, а живая подсказка из «Что умеет Нова», меняется каждые 15 секунд.
 */
@Composable
private fun TodaySection(
    summary: HomeSummary,
    onExpenses: () -> Unit,
    onTasks: () -> Unit,
    onReminders: () -> Unit,
    onExample: () -> Unit,
) {
    val hints = remember { capabilityExamples.ifEmpty { allSuggestions } }
    var hintIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(hints.size) {
        if (hints.size < 2) return@LaunchedEffect
        while (true) {
            delay(15_000)
            hintIndex = (hintIndex + 1) % hints.size
        }
    }
    val empty = summary.todayExpensesMinor == 0L && summary.activeTasks == 0 && summary.activeReminders == 0
    Text(
        "Сегодня",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = NovaSpace.screen, end = NovaSpace.screen, top = NovaSpace.xl, bottom = NovaSpace.s),
    )
    if (empty) {
        Surface(
            shape = RoundedCornerShape(NovaRadius.card),
            color = groupColor(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen).clickable(onClick = onExample),
        ) {
            Crossfade(hints[hintIndex % hints.size], animationSpec = tween(280), label = "hint") { hint ->
                Text(
                    "Скажите: «" + hint.removeSuffix("?") + "»",
                    style = bodySecondaryStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(NovaSpace.l),
                )
            }
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(NovaRadius.card),
        color = groupColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen),
    ) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    caption = "Расходы",
                    value = Money.format(summary.todayExpensesMinor, "RUB"),
                    accent = summary.todayExpensesMinor > 0,
                    onClick = onExpenses,
                    modifier = Modifier.weight(1f),
                )
                StatCell("Задачи", summary.activeTasks.toString(), onTasks, Modifier.weight(1f))
                StatCell("Напоминания", summary.activeReminders.toString(), onReminders, Modifier.weight(1f))
            }
            if (summary.monthExpensesMinor > 0 || summary.overdueTasks > 0) {
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = NovaSpace.l, vertical = NovaSpace.m)) {
                    if (summary.monthExpensesMinor > 0) {
                        Text(
                            "За месяц ${Money.format(summary.monthExpensesMinor, "RUB")}",
                            style = bodySecondaryStyle(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (summary.overdueTasks > 0) {
                        Text(
                            "Просрочено: ${summary.overdueTasks}",
                            style = bodySecondaryStyle(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(caption: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = NovaSpace.l, vertical = NovaSpace.m)) {
        Text(caption, style = captionStyle(), maxLines = 1)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (accent) amberColor() else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Местоположение: включено оно или нет и где это поменять. Разрешение спрашиваем в настройках доступа. */
@Composable
private fun LocationSection(onOpen: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val granted = remember(resume) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    Text(
        "Местоположение",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = NovaSpace.screen, end = NovaSpace.screen, top = NovaSpace.xl, bottom = NovaSpace.s),
    )
    Group {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen)
                .heightIn(min = NovaSize.row)
                .padding(horizontal = NovaSpace.l, vertical = NovaSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                Icons.Outlined.LocationOn,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = NovaSpace.m)) {
                Text(if (granted) "Включено" else "Выключено", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (granted) "Маршруты и напоминания по месту работают" else "Разрешите доступ — Нова подскажет, что рядом",
                    style = bodySecondaryStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Последний обмен репликами: короткий вход в разговор, не занимая пол-экрана. */
@Composable
private fun ConversationTeaser(history: List<ai.nova.core.model.ConversationMessage>, name: String, onOpen: () -> Unit) {
    Text(
        "Разговор",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = NovaSpace.screen, end = NovaSpace.screen, top = NovaSpace.xl, bottom = NovaSpace.s),
    )
    Surface(
        shape = RoundedCornerShape(NovaRadius.card),
        color = groupColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(NovaSpace.l)) {
            history.takeLast(2).forEach { m ->
                val mine = m.role == MessageRole.USER
                Row(Modifier.padding(bottom = NovaSpace.xs)) {
                    Text(
                        if (mine) "Вы" else name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 44.dp),
                    )
                    Text(
                        messageText(m.content),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = NovaSpace.s),
                    )
                }
            }
            Text(
                "Открыть разговор",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = NovaSpace.xs),
            )
        }
    }
}

/** Подтверждение действия до его выполнения: без него Нова не удаляет и не отправляет. */
@Composable
private fun ConfirmPrompt(onConfirm: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.s),
        horizontalArrangement = Arrangement.spacedBy(NovaSpace.s),
    ) {
        PrimaryButton("Да, подтверждаю", { onConfirm(true) }, Modifier.weight(1f))
        SecondaryButton("Отмена", { onConfirm(false) }, Modifier.weight(1f))
    }
}

/** Поле ввода на главной: текст можно отправить сразу, микрофон — один, в голосовом блоке. */
@Composable
private fun HomeInputBar(onSend: (String) -> Unit, onOpenChat: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = NovaSpace.screen, end = NovaSpace.screen, top = NovaSpace.s, bottom = NovaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = NovaSize.field)
                .clip(RoundedCornerShape(NovaRadius.field))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = NovaSpace.l, end = NovaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (input.isEmpty()) {
                    Text(
                        "Спросите или скажите",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) { onSend(input.trim()); input = "" }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (input.isNotBlank()) {
                IconButton(onClick = { onSend(input.trim()); input = "" }) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Длинный разговор удобнее вести в чате: клавиатура и история сообщений на весь экран.
                IconButton(onClick = onOpenChat) {
                    Icon(Icons.Outlined.History, contentDescription = "Открыть чат", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Чат на весь экран: вся переписка, подтверждения, ввод текстом и голосом. */
@Composable
fun ChatScreen(vm: HomeViewModel, onMic: () -> Unit, onBack: () -> Unit, keyboard: Boolean, onOpenRecord: (ai.nova.core.model.RecordType) -> Unit = {}) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val history by vm.fullHistory.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui = voiceUi(voice, settings.assistantName)
    var input by rememberSaveable { mutableStateOf("") }
    // Ярлык с значка («Расход», «Задача», «Заметка») подставляет начало фразы.
    LaunchedEffect(Unit) { vm.c.quickDraft.value?.let { input = it; vm.c.quickDraft.value = null } }
    val listState = rememberLazyListState()
    val zone = remember { java.time.ZoneId.systemDefault() }
    // Разговоры по дням: над каждым днём — «Сегодня», «Вчера» или дата.
    val byDay = remember(history) { history.groupBy { it.createdAt.atZone(zone).toLocalDate() } }
    // Последний ответ всегда внизу: список вырос, поднялась клавиатура, начали печатать.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.viewportSize.height }
            .collectLatest { (count, _) -> if (count > 0) listState.scrollToItem(count - 1) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ChatHeader(settings.assistantName, ui, onBack)
        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = NovaSpace.m, bottom = NovaSpace.s),
        ) {
            if (history.isEmpty()) {
                item(key = "empty") { ChatEmpty(settings.assistantName) { vm.send(it) } }
            }
            byDay.forEach { (day, messages) ->
                item(key = "day-$day") { DayHeader(day) }
                items(messages, key = { it.id }) { m ->
                    val z = m.createdAt.atZone(zone)
                    val time = remember(m.id) { "%02d:%02d".format(z.hour, z.minute) }
                    MessageBubble(m.content, m.role == MessageRole.USER, time)
                }
            }
            if (ui.stage == VoiceStage.PROCESSING) {
                item(key = "typing") { TypingBubble() }
            }
            reply?.let { r ->
                val created = r.outcomes.mapNotNull { it.record }.firstOrNull()
                if (r.awaitingConfirmation || r.provider != null || (r.offline && settings.useAI) || created != null) {
                    item(key = "reply-meta") {
                        ReplyMeta(r, settings.useAI, onConfirm = { vm.confirm(it) }, onOpenRecord = onOpenRecord)
                    }
                }
            }
        }
        AnimatedVisibility(ui.heard.isNotBlank()) {
            Text(
                ui.heard,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.xl, vertical = NovaSpace.s),
            )
        }
        ChatInputBar(
            value = input,
            onChange = { input = it },
            active = ui.active,
            autoFocus = keyboard,
            onSend = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
            onMic = { if (ui.active) vm.stop() else onMic() },
        )
    }
}

/** Шапка чата: имя, живой статус и возврат. */
@Composable
private fun ChatHeader(name: String, ui: VoiceUi, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = NovaSpace.xs, end = NovaSpace.l, top = NovaSpace.s, bottom = NovaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
        val dot = when (ui.stage) {
            VoiceStage.ERROR -> MaterialTheme.colorScheme.error
            VoiceStage.LISTENING, VoiceStage.RECOGNISING, VoiceStage.PROCESSING, VoiceStage.ANSWERING -> amberColor()
            VoiceStage.IDLE -> successColor()
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        }
        Column(Modifier.weight(1f).padding(start = NovaSpace.m)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (ui.stage == VoiceStage.IDLE) "На связи" else ui.title,
                style = captionStyle(),
                color = if (ui.stage == VoiceStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Пустой чат: вместо тишины — готовые фразы. */
@Composable
private fun ChatEmpty(name: String, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpace.xl, vertical = NovaSpace.l)) {
        Text("Разговор с $name", style = MaterialTheme.typography.titleLarge)
        Text(
            "Напишите или скажите, что нужно сделать: «расход 500 ₽ на продукты», «что у меня завтра», «включи радио».",
            style = bodySecondaryStyle(),
            modifier = Modifier.padding(top = NovaSpace.s),
        )
        Column(Modifier.padding(top = NovaSpace.l), verticalArrangement = Arrangement.spacedBy(NovaSpace.s)) {
            suggestions.forEach { s -> Suggestion(s, Modifier.fillMaxWidth()) { onSend(s) } }
        }
    }
}

/** Служебная строка под ответом: что записано, где ответ, можно ли открыть раздел. */
@Composable
private fun ReplyMeta(
    r: ai.nova.core.assistant.AssistantReply,
    useAI: Boolean,
    onConfirm: (Boolean) -> Unit,
    onOpenRecord: (ai.nova.core.model.RecordType) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NovaSpace.xl, vertical = NovaSpace.s)) {
        if (r.awaitingConfirmation) {
            Row(horizontalArrangement = Arrangement.spacedBy(NovaSpace.s)) {
                PrimaryButton("Да, подтверждаю", { onConfirm(true) })
                SecondaryButton("Отмена", { onConfirm(false) })
            }
        }
        val ok = r.provider != null
        val meta = when {
            r.offline && useAI -> "AI не ответил${r.aiError?.let { " ($it)" } ?: ""} — выполнено на устройстве. Подробности: Настройки → AI"
            ok -> "Ответ: ${r.provider}"
            else -> null
        }
        val created = r.outcomes.mapNotNull { it.record }.firstOrNull()
        if (created != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = NovaSpace.s)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = successColor(), modifier = Modifier.size(16.dp))
                Text(
                    "Сохранено · ${created.type.titleRu}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                TextButton(onClick = { onOpenRecord(created.type) }) { Text("Открыть", style = MaterialTheme.typography.labelLarge) }
            }
        }
        meta?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = NovaSpace.xs)) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) successColor() else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(text, style = captionStyle(), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..16 -> "Добрый день"
    in 17..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

/** Данные вернули из отложенной копии: говорим об этом прямо, а не молчим. */
@Composable
private fun RecoveryCard() {
    val stamp by ai.nova.app.data.DatabaseFactory.recovered.collectAsStateWithLifecycle()
    var hidden by remember { mutableStateOf(false) }
    val at = stamp ?: return
    if (hidden) return
    // «18 ноября, 15:19» — дата по-русски, чтобы не было «18 ноябрь».
    val when_ = remember(at) {
        val zone = java.time.ZoneId.systemDefault()
        val time = java.time.Instant.ofEpochMilli(at.toLong()).atZone(zone)
        RuFormat.dateFull(time.toLocalDate(), java.time.LocalDate.now(zone)).replaceFirstChar { it.uppercase() } +
            ", " + RuFormat.time(time.toLocalTime())
    }
    Surface(
        shape = RoundedCornerShape(NovaRadius.card),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.xs),
    ) {
        Column(Modifier.padding(start = NovaSpace.l, end = NovaSpace.s, top = NovaSpace.m, bottom = NovaSpace.xs)) {
            Text("Записи восстановлены", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "После обновления база не открылась, поэтому Нова вернула данные из копии от $when_.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 2.dp, end = NovaSpace.s),
            )
            TextButton(onClick = { hidden = true }) { Text("Хорошо") }
        }
    }
}

/** Разрешение на микрофон не выдано (например, после переустановки) — предлагаем включить, текст работает всегда. */
@Composable
private fun MicAccessCard(onEnable: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val granted = remember(resume) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) return
    Surface(
        shape = RoundedCornerShape(NovaRadius.card),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.xs),
    ) {
        Column(Modifier.padding(start = NovaSpace.l, end = NovaSpace.s, top = NovaSpace.m, bottom = NovaSpace.xs)) {
            Text("Микрофон выключен", style = MaterialTheme.typography.titleSmall)
            Text(
                "Разрешение на микрофон не выдано, поэтому голосом Нова не услышит. Написать текстом можно в любой момент.",
                style = captionStyle(),
                modifier = Modifier.padding(top = 2.dp, end = NovaSpace.s),
            )
            TextButton(onClick = onEnable) { Text("Включить микрофон") }
        }
    }
}

/** Индикатор режима работы: «На устройстве», «Облако Нова» — тонкая обводка, без заливки. */
@Composable
private fun ModeChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 124.dp),
        )
    }
}

/** Готовая фраза: нажатие выполняет команду. */
@Composable
private fun Suggestion(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(NovaRadius.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = NovaSpace.l, vertical = 10.dp).widthIn(max = 260.dp),
        )
    }
}

/** Подпись дня над сообщениями: «Сегодня», «Вчера», «Пятница, 12 сентября». */
@Composable
private fun DayHeader(day: java.time.LocalDate) {
    val today = java.time.LocalDate.now()
    val ru = java.util.Locale("ru")
    val text = when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> {
            val pattern = if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
            java.time.format.DateTimeFormatter.ofPattern(pattern, ru).format(day).replaceFirstChar { it.uppercase() }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = NovaSpace.m), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Сообщение: свой ответ справа с заливкой, ответ Новы слева на карточке.
 * Ответ Новы разбирается как Markdown, поэтому списки и ссылки приходят читаемыми.
 */
@Composable
private fun MessageBubble(text: String, mine: Boolean, time: String?) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 },
        exit = fadeOut(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.xs),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (mine) {
                LinkText(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(NovaRadius.bubble, NovaRadius.bubble, 6.dp, NovaRadius.bubble))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                MarkdownText(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(NovaRadius.bubble, NovaRadius.bubble, NovaRadius.bubble, 6.dp))
                        .background(groupColor())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            time?.let {
                Text(it, style = captionStyle(), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
    }
}

/** Ответ готовится: три точки вместо пустого места. */
@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen, vertical = NovaSpace.xs),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(NovaRadius.bubble, NovaRadius.bubble, NovaRadius.bubble, 6.dp))
                .background(groupColor())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(560, delayMillis = i * 140), RepeatMode.Reverse),
                    label = "dot-$i",
                )
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onChange: (String) -> Unit,
    active: Boolean,
    autoFocus: Boolean = false,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(250) // окно чата ещё выезжает
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NovaSpace.screen).padding(top = NovaSpace.xs, bottom = NovaSpace.m),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = NovaSize.field)
                .clip(RoundedCornerShape(24.dp))
                .background(groupColor())
                .padding(start = NovaSpace.l, end = NovaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) {
                    Text("Сообщение Нове", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (value.isNotBlank()) {
                IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        Spacer(Modifier.width(NovaSpace.s))
        RoundButton(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Остановить" else "Говорить", onMic)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val stop = icon == Icons.Rounded.Stop
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (stop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (stop) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = Modifier.size(52.dp).pressScale(interaction),
    ) {
        // Микрофон ↔ стоп — плавная смена значка.
        Crossfade(targetState = icon, label = "mic", modifier = Modifier.fillMaxSize()) { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(i, contentDescription = description, modifier = Modifier.size(24.dp))
            }
        }
    }
}
