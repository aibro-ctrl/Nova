package ai.nova.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.ui.components.AssistantOrb
import ai.nova.app.ui.components.OrbMode
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.UpdateCard
import ai.nova.app.ui.components.SetupCard
import ai.nova.app.ui.components.groupColor
import ai.nova.app.ui.components.organicPanel
import ai.nova.app.ui.components.pressScale
import ai.nova.app.ui.theme.successColor
import ai.nova.app.voice.VoiceState
import ai.nova.core.assistant.RuFormat
import ai.nova.core.model.MessageRole
import ai.nova.core.nlp.Money
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import ai.nova.app.ui.components.captionStyle
import ai.nova.app.ui.components.bodySecondaryStyle

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
/** Два примера на главной: погода и расход — самые частые запросы. */
private val suggestions: List<String> by lazy {
    (listOf(allSuggestions.first()) + allSuggestions.drop(1).shuffled().take(7) + "Что ты умеешь?").distinct()
}

/** Состояние голоса для экранов Нова: режим сферы, подпись и услышанный текст. */
private data class VoiceUi(val mode: OrbMode, val status: String, val heard: String, val level: Float) {
    val active get() = mode == OrbMode.LISTENING || mode == OrbMode.THINKING || mode == OrbMode.SPEAKING
}

private fun voiceUi(voice: VoiceState, name: String): VoiceUi {
    val (mode, status) = when (voice) {
        VoiceState.Idle -> OrbMode.IDLE to "Нажмите на сферу или скажите «$name»"
        is VoiceState.Listening -> OrbMode.LISTENING to (voice.hint ?: if (voice.followUp) "Слушаю дальше…" else "Слушаю…")
        is VoiceState.Thinking -> OrbMode.THINKING to "Думаю…"
        is VoiceState.Speaking -> OrbMode.SPEAKING to "Отвечаю… Скажите «стоп», чтобы прервать"
        is VoiceState.Error -> OrbMode.ERROR to voice.message
    }
    val heard = when (voice) {
        is VoiceState.Listening -> voice.partial
        is VoiceState.Thinking -> voice.heard
        else -> ""
    }
    return VoiceUi(mode, status, heard, (voice as? VoiceState.Listening)?.level ?: 0f)
}

/**
 * Главная: сфера, закреплённые переходы в разделы и небольшое окно чата внизу.
 * Нажатие на чат открывает его на весь экран.
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(settings.assistantName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 2.dp))
            }
            val cloud = vm.cloudActive()
            val (icon, label) = when {
                !online && (settings.useAI || cloud) -> Icons.Outlined.CloudOff to "Офлайн"
                cloud -> Icons.Outlined.AutoAwesome to "Облако Нова"
                !settings.useAI -> Icons.Outlined.PhoneAndroid to "На устройстве"
                vm.aiConfigured() -> Icons.Outlined.AutoAwesome to (settings.primaryProvider?.type?.title ?: "AI")
                else -> Icons.Outlined.AutoAwesome to "AI: нет ключа"
            }
            ModeChip(label, icon) { openSettings("settings/ai") }
            IconButton(onClick = openHistory) { Icon(Icons.Outlined.History, contentDescription = "История запросов", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Середина листается: обновление, шаги настройки, сфера.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RecoveryCard()
            MicAccessCard(onEnable = onMic)
            ai.nova.app.ui.components.CrashCard(settings.assistantName)
            ai.nova.app.ui.components.BackupNudgeCard(vm.c, settings.assistantName) { openSettings(SettingsPage.BACKUP.route) }
            UpdateCard(vm.c)
            SetupCard(vm.c, settings.assistantName, onVisible = { setupShown = it })
            Box(
                Modifier.clip(CircleShape).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { if (ui.active) vm.stop() else onMic() },
            ) {
                // Волна подстраивается под высоту экрана: на маленьких телефонах и с крупным шрифтом — меньше.
                val full = (viewport * 0.42f).coerceIn(136.dp, 236.dp)
                AssistantOrb(ui.mode, ui.level, size = if (setupShown) (full * 0.7f).coerceAtLeast(112.dp) else full)
            }
            Text(
                ui.status, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = if (ui.mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 18.dp)
                    .clickable(enabled = ui.mode == OrbMode.ERROR) { vm.clearError() },
            )
            AnimatedVisibility(ui.heard.isNotBlank()) {
                Text(ui.heard, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
            }
            // Пока Нова ждёт — один пример фразы вместо пустоты под волной.
            if (ui.mode == OrbMode.IDLE) {
                Text(
                    "Например: «Расход 500 ₽ на продукты»",
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
                )
            }
        }
        }
        // Одна строка сводки вместо сетки одинаковых карточек.
        SummaryLine(
            summary,
            onTasks = { openPlans(0) },
            onExpenses = openExpenses,
            onReminders = { openPlans(1) },
            onExample = { openChat(true) },
        )
        HomeBottomPanel(
            history = history, name = settings.assistantName, active = ui.active,
            awaitingConfirmation = reply?.awaitingConfirmation == true,
            onOpen = { openChat(false) }, onType = { openChat(true) },
            onSuggestion = { vm.send(it); openChat(false) },
            onConfirm = { vm.confirm(it) },
            onMic = { if (ui.active) vm.stop() else onMic() },
        )
    }
}

/**
 * Быстрые фразы: одно касание — и команда выполнена. Работают и без голоса.
 * Десять частых запросов и только нейтральные формулировки — приложением пользуются и мужчины, и женщины.
 */
private val quickPhrases = listOf(
    "Какая погода?",
    "Мои планы на сегодня",
    "Мои расходы",
    "Курс доллара",
    "Что у меня в календаре завтра?",
    "Напомни через час выпить воды",
    "Добавь в покупки молоко и хлеб",
    "Таймер на 5 минут",
    "Включи радио",
    "Что ты умеешь?",
)

/** Нижняя панель главной: быстрые фразы, последний обмен репликами, поле ввода и отдельная кнопка микрофона. */
@Composable
private fun HomeBottomPanel(
    history: List<ai.nova.core.model.ConversationMessage>,
    name: String,
    active: Boolean,
    awaitingConfirmation: Boolean,
    onOpen: () -> Unit,
    onType: () -> Unit,
    onSuggestion: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onMic: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = organicPanel(0.82f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Попробуйте сказать", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quickPhrases) { s -> QuickPhrase(s) { onSuggestion(s) } }
            }
            val last = history.takeLast(2)
            if (last.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp).padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    last.forEach { m ->
                        val mine = m.role == MessageRole.USER
                        Text(
                            (if (mine) "Вы: " else "$name: ") + m.content,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (mine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text("Открыть чат", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (awaitingConfirmation) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Да, подтверждаю", { onConfirm(true) })
                    SecondaryButton("Отмена", { onConfirm(false) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(interactionSource = interaction, indication = null, onClick = onType)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Спросите или скажите", style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = 1) }
                Spacer(Modifier.width(10.dp))
                RoundButton(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Остановить" else "Говорить", onMic)
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
    // Разговоры по дням: над каждым днём — «Сегодня», «Вчера» или дата.
    val zone = remember { java.time.ZoneId.systemDefault() }
    val byDay = remember(history) { history.groupBy { it.createdAt.atZone(zone).toLocalDate() } }
    // Последний ответ всегда внизу: список вырос, поднялась клавиатура, начали печатать —
    // прокручиваем к концу. Пересчитываем по реальному числу элементов: заголовки дней,
    // строки «Сохранено · Задача» и кнопки подтверждения — это тоже элементы списка.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.viewportSize.height }
            .collectLatest { (count, _) -> if (count > 0) listState.scrollToItem(count - 1) }
    }

    // Фон приложения просвечивает и в чате: чат — окно, а не отдельная плоская страница.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад") }
            AssistantOrb(ui.mode, ui.level, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(settings.assistantName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.mode == OrbMode.IDLE) "На связи" else ui.status, style = MaterialTheme.typography.bodySmall,
                    color = if (ui.mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (history.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Здесь будет ваш разговор с ${settings.assistantName}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text("Напишите или скажите, что нужно сделать", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item(key = "suggest") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions.take(3)) { s -> Suggestion(s) { vm.send(s) } }
                    }
                }
            }
            byDay.forEach { (day, messages) ->
                item(key = "day-$day") { DayHeader(day) }
                items(messages, key = { it.id }) { m ->
                    val time = remember(m.id) { "%02d:%02d".format(m.createdAt.atZone(zone).hour, m.createdAt.atZone(zone).minute) }
                    Box(Modifier.animateItem()) { Message(m.content, m.role == MessageRole.USER, time) }
                }
            }
            reply?.let { r ->
                if (r.awaitingConfirmation || r.provider != null || (r.offline && settings.useAI)) {
                    item(key = "reply-meta") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            if (r.awaitingConfirmation) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PrimaryButton("Да, подтверждаю", { vm.confirm(true) })
                                    SecondaryButton("Отмена", { vm.confirm(false) })
                                }
                            }
                            val ok = r.provider != null
                            val meta = when {
                                r.offline && settings.useAI -> "AI не ответил${r.aiError?.let { " ($it)" } ?: ""} — выполнено на устройстве. Подробности: Настройки → AI"
                                ok -> "Ответ: ${r.provider}"
                                else -> null
                            }
                            // Успех: видно, что именно записалось, и можно сразу открыть раздел.
                            val created = r.outcomes.mapNotNull { it.record }.firstOrNull()
                            if (created != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = successColor(), modifier = Modifier.size(16.dp))
                                    Text(
                                        "Сохранено · ${created.type.titleRu}",
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 6.dp).weight(1f),
                                    )
                                    androidx.compose.material3.TextButton(onClick = { onOpenRecord(created.type) }) {
                                        Text("Открыть", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            meta?.let { text ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    Icon(
                                        if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (ok) successColor() else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(ui.heard.isNotBlank()) {
            Text(ui.heard, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp))
        }
        InputBar(
            value = input, onChange = { input = it }, name = settings.assistantName, active = ui.active, autoFocus = keyboard,
            onSend = { if (input.isNotBlank()) { vm.send(input.trim()); input = "" } },
            onMic = { if (ui.active) vm.stop() else onMic() },
        )
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
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Записи восстановлены", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "После обновления база не открылась, поэтому Нова вернула данные из копии от $when_.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            androidx.compose.material3.TextButton(onClick = { hidden = true }) { Text("Хорошо") }
        }
    }
}

/** Разрешение на микрофон не выдано (например, после переустановки) — предлагаем включить, текст работает всегда. */
@Composable
private fun MicAccessCard(onEnable: () -> Unit) {
    val context = LocalContext.current
    val resume = ai.nova.app.ui.components.rememberResumeTick()
    val granted = remember(resume) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    if (granted) return
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Микрофон выключен", style = MaterialTheme.typography.titleSmall)
            Text(
                "Разрешение на микрофон не выдано, поэтому голосом Нова не услышит. Написать текстом можно в любой момент.",
                style = captionStyle(),
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            androidx.compose.material3.TextButton(onClick = onEnable) { Text("Включить микрофон") }
        }
    }
}

/** Строка сводки: деньги за день, задачи и напоминания. Пустое состояние — пример фразы. */
@Composable
private fun SummaryLine(
    summary: HomeSummary,
    onTasks: () -> Unit,
    onExpenses: () -> Unit,
    onReminders: () -> Unit,
    onExample: () -> Unit,
) {
    val hints = remember { capabilityExamples.ifEmpty { allSuggestions } }
    var hintIndex by remember { mutableIntStateOf(0) }
    // Подсказка меняется каждые 15 секунд: в ней настоящие примеры из «Что умеет Нова».
    LaunchedEffect(hints.size) {
        if (hints.size < 2) return@LaunchedEffect
        while (true) {
            delay(15_000)
            hintIndex = (hintIndex + 1) % hints.size
        }
    }
    val parts = buildList {
        if (summary.todayExpensesMinor > 0) add(SummaryPart("Сегодня ${Money.format(summary.todayExpensesMinor, "RUB")}", onExpenses))
        if (summary.activeTasks > 0) add(SummaryPart(RuFormat.count(summary.activeTasks, "задача", "задачи", "задач"), onTasks))
        if (summary.overdueTasks > 0) add(SummaryPart("просрочено ${summary.overdueTasks}", onTasks, warn = true))
        if (summary.activeReminders > 0) add(SummaryPart(RuFormat.count(summary.activeReminders, "напоминание", "напоминания", "напоминаний"), onReminders))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.isEmpty()) {
            // Пустое состояние — не заглушка, а живая подсказка из «Что умеет Нова», меняется каждые 15 секунд.
            Crossfade(hints[hintIndex % hints.size], animationSpec = androidx.compose.animation.core.tween(280), label = "hint") { hint ->
                Text(
                    "Скажите: «" + hint.removeSuffix("?") + "»",
                    style = bodySecondaryStyle(),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onExample).heightIn(min = 48.dp).padding(vertical = 12.dp),
                )
            }
        } else {
            parts.forEachIndexed { i, part ->
                if (i > 0) {
                    Text("·", style = bodySecondaryStyle(), modifier = Modifier.padding(horizontal = 8.dp))
                }
                Text(
                    part.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (part.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable(onClick = part.action).heightIn(min = 48.dp).padding(vertical = 12.dp),
                )
            }
        }
    }
}

private data class SummaryPart(val text: String, val action: () -> Unit, val warn: Boolean = false)

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
            text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp),
        )
    }
}

/** Компактная быстрая фраза на главной. */
@Composable
private fun QuickPhrase(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressScale(interaction)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = groupColor(), interactionSource = interaction, modifier = Modifier.pressScale(interaction)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 220.dp))
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
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(groupColor()).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Message(text: String, mine: Boolean, time: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (mine) {
            Text(
                text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            Text(
                text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .background(groupColor()).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        time?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, name: String, active: Boolean, autoFocus: Boolean = false, onSend: () -> Unit, onMic: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            kotlinx.coroutines.delay(250) // окно чата ещё выезжает
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(groupColor()).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) Text("Сообщение Нове", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = value, onValueChange = onChange, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (value.isNotBlank()) IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(10.dp))
        RoundButton(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Остановить" else "Говорить", onMic)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction, modifier = Modifier.size(52.dp).pressScale(interaction),
    ) {
        // Микрофон ↔ стоп — плавная смена значка.
        Crossfade(targetState = icon, label = "mic", modifier = Modifier.fillMaxSize()) { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(i, contentDescription = description, modifier = Modifier.size(24.dp)) }
        }
    }
}
