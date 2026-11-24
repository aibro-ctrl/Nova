package ai.nova.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaField
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import kotlinx.coroutines.launch
import ai.nova.app.ui.components.captionStyle

/**
 * «Сценарии»: фраза, за которой идёт несколько команд, и всё про то, как Нова разговаривает —
 * диалоговый режим, сводки, быстрый ввод. Раньше это лежало в «Голосе и речи», хотя к голосу
 * не относится: человек искал сценарии в «Ещё» и не находил.
 */
@Composable
internal fun RoutinesPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    NovaScreen(title = "Сценарии", onBack = onBack) {
        item(key = "routines") {
            Hint(
                "Сценарий — одна фраза, за которой идёт несколько команд. Скажите «спокойной ночи» — " +
                    "Нова поставит будильник и включит «не беспокоить».",
                Modifier.padding(bottom = 4.dp),
            )
            RoutinesSection(c)
        }
        item(key = "dialog") {
            SectionLabel("Разговор")
            Group {
                SwitchItem(
                    "Диалоговый режим",
                    "После ответа продолжаю слушать без повторного имени, пока вы не скажете «хватит» или не замолчите",
                    s.dialogModeEnabled,
                ) { v -> scope.launch { c.settings.setDialogMode(v) } }
                GroupDivider()
                // Время сводок раньше было жёстким: человек может вставать и ложиться в разное время.
                var morningAt by remember { mutableStateOf(ai.nova.app.reminders.BriefTimes.morning(context)) }
                var eveningAt by remember { mutableStateOf(ai.nova.app.reminders.BriefTimes.evening(context)) }
                var weeklyAt by remember { mutableStateOf(ai.nova.app.reminders.BriefTimes.weekly(context)) }
                var editing by remember { mutableStateOf<Brief?>(null) }
                SwitchItem(
                    "Утренняя сводка",
                    "В ${ai.nova.app.reminders.BriefTimes.label(morningAt)} — погода, задачи и напоминания на день",
                    s.morningBrief,
                ) { v ->
                    scope.launch { c.settings.setMorningBrief(v); ai.nova.app.reminders.MorningBrief.schedule(context, v, replace = true) }
                }
                if (s.morningBrief) {
                    GroupDivider(inset = 66.dp)
                    RowItem(
                        title = "Время утренней сводки", subtitle = ai.nova.app.reminders.BriefTimes.label(morningAt),
                        trailing = { TextButton(onClick = { editing = Brief.MORNING }) { Text("Изменить") } },
                    )
                }
                GroupDivider()
                SwitchItem(
                    "Вечерняя сводка",
                    "В ${ai.nova.app.reminders.BriefTimes.label(eveningAt)} — итоги дня: настроение, задачи, траты, вода и привычки",
                    s.eveningBrief,
                ) { v ->
                    scope.launch { c.settings.setEveningBrief(v); ai.nova.app.reminders.EveningBrief.schedule(context, v, replace = true) }
                }
                if (s.eveningBrief) {
                    GroupDivider(inset = 66.dp)
                    RowItem(
                        title = "Время вечерней сводки", subtitle = ai.nova.app.reminders.BriefTimes.label(eveningAt),
                        trailing = { TextButton(onClick = { editing = Brief.EVENING }) { Text("Изменить") } },
                    )
                }
                GroupDivider()
                SwitchItem(
                    "Итоги недели",
                    "По воскресеньям в ${ai.nova.app.reminders.BriefTimes.label(weeklyAt)} — траты, задачи, настроение и привычки за неделю",
                    s.weeklyReview,
                ) { v ->
                    scope.launch { c.settings.setWeeklyReview(v); ai.nova.app.reminders.WeeklyReview.schedule(context, v, replace = true) }
                }
                if (s.weeklyReview) {
                    GroupDivider(inset = 66.dp)
                    RowItem(
                        title = "Время итогов недели", subtitle = ai.nova.app.reminders.BriefTimes.label(weeklyAt),
                        trailing = { TextButton(onClick = { editing = Brief.WEEKLY }) { Text("Изменить") } },
                    )
                }
                if (s.morningBrief || s.eveningBrief) {
                    GroupDivider()
                    // Выключенный блок не озвучивается — ни в утренней сводке, ни в итогах дня.
                    ai.nova.core.health.BriefBlocks.ALL.forEachIndexed { i, block ->
                        if (i > 0) GroupDivider(inset = 66.dp)
                        SwitchItem(
                            ai.nova.core.health.BriefBlocks.label(block),
                            when (block) {
                                ai.nova.core.health.BriefBlocks.WEATHER -> "Погода на день в утренней сводке"
                                ai.nova.core.health.BriefBlocks.TASKS -> "Задачи на сегодня и просроченные"
                                ai.nova.core.health.BriefBlocks.REMINDERS -> "Напоминания дня с временем"
                                ai.nova.core.health.BriefBlocks.HEALTH -> "Настроение, вода и привычки в итогах дня"
                                ai.nova.core.health.BriefBlocks.SPEND -> "Траты за день в итогах"
                                else -> "Идеи, записанные за день"
                            },
                            ai.nova.core.health.BriefBlocks.on(s.briefBlocks, block),
                        ) { v ->
                            val next = if (v) s.briefBlocks + block else s.briefBlocks - block
                            scope.launch { c.settings.setBriefBlocks(next) }
                        }
                    }
                }
                editing?.let { which ->
                    val initial = when (which) { Brief.MORNING -> morningAt; Brief.EVENING -> eveningAt; Brief.WEEKLY -> weeklyAt }
                    BriefTimeDialog(initial, onDismiss = { editing = null }) { picked ->
                        editing = null
                        when (which) {
                            Brief.MORNING -> {
                                morningAt = picked; ai.nova.app.reminders.BriefTimes.setMorning(context, picked)
                                ai.nova.app.reminders.MorningBrief.schedule(context, s.morningBrief, replace = true)
                            }
                            Brief.EVENING -> {
                                eveningAt = picked; ai.nova.app.reminders.BriefTimes.setEvening(context, picked)
                                ai.nova.app.reminders.EveningBrief.schedule(context, s.eveningBrief, replace = true)
                            }
                            Brief.WEEKLY -> {
                                weeklyAt = picked; ai.nova.app.reminders.BriefTimes.setWeekly(context, picked)
                                ai.nova.app.reminders.WeeklyReview.schedule(context, s.weeklyReview, replace = true)
                            }
                        }
                    }
                }
                GroupDivider()
                SwitchItem(
                    "Быстрый ввод из шторки",
                    "Постоянное уведомление с полем: напишите «кофе 250» или «купить хлеб» — запишу, не открывая приложение",
                    s.quickInput,
                ) { v ->
                    scope.launch { c.settings.setQuickInput(v); ai.nova.app.quick.QuickInput.sync(context, v) }
                }
            }
        }
        item(key = "search-engine") {
            // Android не даёт узнать поисковик браузера: без выбора «найди в интернете» открывало пустую страницу.
            SectionLabel("Где искать в интернете")
            Group {
                val picked = ai.nova.core.assistant.SearchEngine.fromId(s.searchEngine)
                ai.nova.core.assistant.SearchEngine.entries.forEachIndexed { i, engine ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    RadioRow(
                        engine.title,
                        "Открываю результаты поиска в браузере по умолчанию",
                        picked == engine,
                    ) { scope.launch { c.settings.setSearchEngine(engine.id) } }
                }
            }
        }
        item(key = "maps") {
            // Маршрут можно вести и в конкретных картах: «построй маршрут в 2ГИС до вокзала» — главнее настройки.
            SectionLabel("Карты для маршрутов")
            Group {
                val installed = remember {
                    ai.nova.core.assistant.MapsApp.entries.associateWith {
                        runCatching { context.packageManager.getPackageInfo(it.pkg, 0) }.isSuccess
                    }
                }
                RadioRow(
                    "Как в телефоне",
                    "Открываю то приложение, которое выбрано системой для карт",
                    s.mapsApp.isBlank(),
                ) { scope.launch { c.settings.setMapsApp("") } }
                ai.nova.core.assistant.MapsApp.entries.forEach { app ->
                    GroupDivider(inset = 66.dp)
                    RadioRow(
                        app.title,
                        if (installed[app] == true) "установлено на телефоне" else "не установлено — открою ${app.title} в браузере",
                        s.mapsApp == app.id,
                    ) { scope.launch { c.settings.setMapsApp(app.id) } }
                }
            }
        }
    }
}

/** Сценарии: фраза → набор функций Новы (галочки в конструкторе) плюс свои команды. */
@Composable
internal fun RoutinesSection(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val routines by remember { c.store.routines.observe() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<ai.nova.core.model.Routine?>(null) }
    var creating by remember { mutableStateOf(false) }
    if (creating) RoutineEditorDialog(c, null) { creating = false }
    editing?.let { r -> RoutineEditorDialog(c, r) { editing = null } }
    SectionLabel("Мои сценарии")
    Group {
        if (routines.isEmpty()) {
            Hint("Соберите сценарий из галочек: погода, дела, напоминания — что хотите слышать. Или скажите голосом: «когда я говорю „спокойной ночи“ — поставь будильник на 7».")
        }
        routines.forEachIndexed { i, r ->
            if (i > 0) GroupDivider()
            RowItem(
                title = "«${r.trigger}»", subtitle = r.commands.joinToString(" · "), maxSubtitleLines = 3,
                chevron = true, onClick = { editing = r },
                trailing = {
                    IconButton(onClick = { scope.launch { c.store.routines.delete(r.id) } }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Удалить сценарий", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        if (routines.isNotEmpty()) {
            GroupDivider()
            RowItem(title = "＋ Свой сценарий", subtitle = "Выбрать галочками, что рассказывать", onClick = { creating = true })
        }
    }
    if (routines.isEmpty()) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { creating = true }) { Text("＋ Свой сценарий") }
        }
    }
    val context = LocalContext.current
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = {
            scope.launch {
                val r = ai.nova.app.device.BirthdayImport.run(context, c)
                val msg = when {
                    r.permissionDenied -> "Нужен доступ к контактам, чтобы найти дни рождения"
                    r.imported == 0 -> "Новых дней рождения в контактах не нашлось"
                    else -> "Добавила ${ai.nova.core.assistant.RuFormat.count(r.imported, "день рождения", "дня рождения", "дней рождения")} — напомню накануне и в сам день"
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }) { Text("Дни рождения из контактов") }
    }
    val missing = ROUTINE_TEMPLATES.filter { t -> routines.none { ai.nova.core.model.Routine.normalize(it.trigger) == ai.nova.core.model.Routine.normalize(t.first) } }
    if (missing.isNotEmpty()) {
        SectionLabel("Готовые")
        Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState())) {
            missing.forEach { (trigger, commands) ->
                TextButton(onClick = { scope.launch { c.store.routines.save(trigger, commands) } }) { Text("+ $trigger") }
            }
        }
    }
}

private val ROUTINE_TEMPLATES = listOf(
    "Доброе утро" to listOf("что у меня на сегодня", "какая погода"),
    "Спокойной ночи" to listOf("поставь будильник на 7:00", "включи не беспокоить"),
    "Я за рулём" to listOf("включи не беспокоить", "включи музыку"),
    "Я дома" to listOf("выключи не беспокоить", "что у меня на сегодня"),
)

/**
 * Конструктор сценария: фраза-триггер, галочки функций Новы и свои команды.
 *
 * Одна галочка — одна обычная фраза, поэтому сценарий можно собрать мышкой, а потом проверить голосом,
 * и наоборот: сценарий, созданный голосом, открывается здесь с уже отмеченными галочками.
 */
@Composable
internal fun RoutineEditorDialog(c: AppContainer, editing: ai.nova.core.model.Routine?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val original = editing?.commands.orEmpty()
    var trigger by remember { mutableStateOf(editing?.trigger.orEmpty()) }
    var selected by remember { mutableStateOf(ai.nova.core.assistant.RoutineBlocks.selectedIds(original)) }
    val extras = remember {
        mutableStateListOf<String>().apply { addAll(ai.nova.core.assistant.RoutineBlocks.extras(original)) }
    }
    var newExtra by remember { mutableStateOf("") }
    val commands = ai.nova.core.assistant.RoutineBlocks.compose(original, selected, extras)
    val canSave = trigger.isNotBlank() && commands.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text(if (editing == null) "Новый сценарий" else "Сценарий") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                NovaField(trigger, { trigger = it.take(60) }, "Когда сказать")
                Text(
                    "Скажите эту фразу — Нова выполнит всё отмеченное ниже. По расписанию сценарий запускается как напоминание.",
                    style = captionStyle(),
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                ai.nova.core.assistant.RoutineBlocks.ALL.forEach { b ->
                    val on = b.id in selected
                    RowItem(
                        title = b.title, subtitle = b.hint,
                        onClick = { selected = if (on) selected - b.id else selected + b.id },
                        trailing = { Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - b.id else selected + b.id }) },
                    )
                }
                if (extras.isNotEmpty()) {
                    Text(
                        "Свои команды", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    extras.forEach { cmd ->
                        RowItem(
                            title = cmd,
                            trailing = {
                                IconButton(onClick = { extras.remove(cmd) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Убрать команду", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
                NovaField(newExtra, { newExtra = it.take(120) }, "Своя команда", modifier = Modifier.padding(top = 12.dp))
                TextButton(
                    enabled = newExtra.isNotBlank(),
                    onClick = { extras.add(newExtra.trim()); newExtra = "" },
                ) { Text("Добавить команду") }
                Text(
                    if (commands.isEmpty()) "Пока ничего не отмечено — сценарий пустой." else "Прозвучит: " + commands.joinToString(" · "),
                    style = captionStyle(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    scope.launch {
                        val saved = c.store.routines.save(trigger.trim(), commands)
                        val old = editing
                        // Фраза изменилась — старый сценарий заменяется новым, а его напоминание начинает звать новый.
                        if (old != null && ai.nova.core.model.Routine.normalize(old.trigger) != ai.nova.core.model.Routine.normalize(saved.trigger)) {
                            c.store.routines.delete(old.id)
                            retargetScheduledReminder(c, old.trigger, saved.trigger)
                        }
                        onDismiss()
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Напоминание «Сценарий: старое имя» должно запускать сценарий с новым именем, иначе он пропадёт из расписания. */
private suspend fun retargetScheduledReminder(c: AppContainer, oldTrigger: String, newTrigger: String) {
    val prefix = ai.nova.core.model.Routine.SCHEDULE_PREFIX
    val key = ai.nova.core.model.Routine.normalize(oldTrigger)
    c.store.reminders.active()
        .filter { it.text.startsWith(prefix) && ai.nova.core.model.Routine.normalize(it.text.removePrefix(prefix)) == key }
        .forEach { c.store.reminders.update(it.copy(text = prefix + newTrigger)) }
}

/** Какое время сводки меняем. */
internal enum class Brief { MORNING, EVENING, WEEKLY }

/** Выбор времени сводки: системные часы-циферблат не нужны, хватает компактного ввода. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BriefTimeDialog(initial: java.time.LocalTime, onDismiss: () -> Unit, onPicked: (java.time.LocalTime) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время сводки") },
        text = {
            Column {
                androidx.compose.material3.TimeInput(state = state)
                Text(
                    "Сводка придёт в это время. Если уведомления выключены, ничего не приходит.",
                    style = captionStyle(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(java.time.LocalTime.of(state.hour, state.minute)); }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
