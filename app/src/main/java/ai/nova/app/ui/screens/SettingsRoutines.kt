package ai.nova.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import kotlinx.coroutines.launch

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
    }
}

/** Сценарии: фраза → несколько команд. Готовые шаблоны добавляются одним нажатием. */
@Composable
internal fun RoutinesSection(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val routines by remember { c.store.routines.observe() }.collectAsStateWithLifecycle(emptyList())
    SectionLabel("Мои сценарии")
    Group {
        if (routines.isEmpty()) {
            Hint("Скажите: «когда я говорю „спокойной ночи“ — поставь будильник на 7 и включи не беспокоить». Или добавьте шаблон ниже.")
        }
        routines.forEachIndexed { i, r ->
            if (i > 0) GroupDivider()
            RowItem(
                title = "«${r.trigger}»", subtitle = r.commands.joinToString(" · "), maxSubtitleLines = 3,
                trailing = {
                    IconButton(onClick = { scope.launch { c.store.routines.delete(r.id) } }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Удалить сценарий", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
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
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(java.time.LocalTime.of(state.hour, state.minute)); }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
