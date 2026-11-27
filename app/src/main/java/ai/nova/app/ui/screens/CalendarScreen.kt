package ai.nova.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.calendar.CalendarMirror
import ai.nova.app.ui.components.ConfirmDialog
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import ai.nova.app.ui.components.captionStyle
import ai.nova.app.ui.components.rememberResumeTick
import kotlinx.coroutines.launch

/**
 * «Google Календарь»: напоминания Нова попадают в календарь телефона.
 *
 * Отдельного доступа к Google нет: событие пишется в системный календарь, а аккаунт Google
 * синхронизирует его с Google Календарём на всех устройствах. Пользователю нужны только
 * разрешение «Календарь» и включённая синхронизация календаря в настройках телефона.
 */
@Composable
fun CalendarPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()

    var granted by remember { mutableStateOf(c.calendarMirror.hasPermission()) }
    var calendars by remember { mutableStateOf<List<CalendarMirror.NovaCalendar>>(emptyList()) }
    var linked by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    fun report(report: CalendarMirror.Report) {
        message = calendarReportText(report)
        failed = !report.ok
        busy = false
    }

    fun refresh() {
        scope.launch {
            granted = c.calendarMirror.hasPermission()
            calendars = if (granted) c.calendarMirror.calendars() else emptyList()
            linked = c.calendarMirror.count()
        }
    }

    LaunchedEffect(resumeTick, s.calendarMirror) { refresh() }

    fun enable() {
        scope.launch {
            busy = true
            val list = c.calendarMirror.calendars()
            calendars = list
            if (list.isEmpty()) {
                busy = false
                message = "В телефоне нет календаря, доступного для записи. Добавьте аккаунт Google: " +
                    "«Настройки телефона → Аккаунты → Google»."
                failed = true
                return@launch
            }
            val chosen = c.settings.current().calendarId.toLongOrNull()?.let { id -> list.firstOrNull { it.id == id } }
                ?: list.firstOrNull { it.google && it.primary }
                ?: list.firstOrNull { it.google }
                ?: list.first()
            c.settings.setCalendarId(chosen.id.toString())
            c.settings.setCalendarMirror(true)
            val report = c.calendarMirror.reconcile()
            linked = c.calendarMirror.count()
            report(report)
        }
    }

    fun disable() {
        scope.launch {
            busy = true
            c.settings.setCalendarMirror(false)
            val removed = c.calendarMirror.removeAll()
            linked = c.calendarMirror.count()
            busy = false
            message = if (removed > 0) "Дублирование выключено, событий убрано: $removed." else "Дублирование выключено."
            failed = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = c.calendarMirror.hasPermission()
        if (granted && pendingEnable) {
            pendingEnable = false
            enable()
        } else if (!granted) {
            message = "Без разрешения на календарь события добавить нельзя."
            failed = true
        }
    }

    /** Выбор календаря: события из старого календаря убираются, из нового создаются. */
    fun select(calendar: CalendarMirror.NovaCalendar) {
        scope.launch {
            c.settings.setCalendarId(calendar.id.toString())
            if (s.calendarMirror) {
                busy = true
                report(c.calendarMirror.reconcile())
                linked = c.calendarMirror.count()
            }
        }
    }

    val selectedId = s.calendarId.toLongOrNull()
        ?: calendars.firstOrNull { it.google && it.primary }?.id
        ?: calendars.firstOrNull()?.id

    NovaScreen(title = "Google Календарь", subtitle = "Напоминания в календаре телефона", onBack = onBack) {
        item(key = "intro") {
            Group(Modifier.padding(top = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Нова добавит каждое напоминание в календарь телефона. Если в телефоне есть аккаунт Google " +
                            "и включена синхронизация календаря, событие появится в Google Календаре на всех устройствах.",
                        style = captionStyle(),
                    )
                    Text(
                        "Без OAuth, ключей и оплаты: Нова пишет в системный календарь, а дальше его синхронизирует телефон.",
                        style = captionStyle(),
                    )
                }
            }
        }

        item(key = "toggle") {
            SectionLabel("Дублирование")
            Group {
                SwitchItem(
                    "Дублировать напоминания",
                    "Создавать, обновлять и удалять события вместе с напоминаниями",
                    s.calendarMirror,
                    enabled = !busy,
                ) { on ->
                    if (on) {
                        if (!granted) {
                            pendingEnable = true
                            permissionLauncher.launch(CalendarMirror.PERMISSIONS)
                        } else {
                            enable()
                        }
                    } else {
                        confirmOff = true
                    }
                }
                if (!granted) {
                    GroupDivider(inset = 66.dp)
                    RowItem(
                        title = "Нужен доступ к календарю",
                        subtitle = "Только чтение и запись событий — почту и файлы Нова не видит. " +
                            "Если окно не появляется, включите «Календарь» в настройках приложения.",
                        trailing = {
                            Row {
                                TextButton(onClick = { permissionLauncher.launch(CalendarMirror.PERMISSIONS) }) {
                                    Text("Разрешить")
                                }
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }) { Text("Настройки") }
                            }
                        },
                    )
                }
            }
        }

        if (granted && calendars.isEmpty()) {
            item(key = "no-calendar") {
                Hint(
                    "В телефоне нет календаря, доступного для записи. Добавьте аккаунт Google: " +
                        "«Настройки телефона → Аккаунты → Google» — и включите синхронизацию календаря.",
                    Modifier.padding(top = 8.dp),
                )
            }
        }

        if (granted && calendars.isNotEmpty()) {
            item(key = "calendars") {
                SectionLabel("Куда добавлять")
                Group {
                    calendars.forEachIndexed { index, calendar ->
                        if (index > 0) GroupDivider(inset = 52.dp)
                        RadioRow(
                            calendar.name,
                            when {
                                calendar.google && calendar.primary -> "Google, основной"
                                calendar.google -> "Google · ${calendar.account}"
                                calendar.account.isNotBlank() -> calendar.account
                                else -> "Календарь телефона"
                            },
                            calendar.id == selectedId,
                        ) { select(calendar) }
                    }
                }
            }

            item(key = "sync") {
                SectionLabel("Синхронизация")
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Синхронизировать сейчас", {
                                scope.launch {
                                    busy = true
                                    report(c.calendarMirror.reconcile())
                                    linked = c.calendarMirror.count()
                                }
                            }, enabled = !busy && s.calendarMirror)
                        }
                        Text(
                            if (linked > 0) "Напоминаний в календаре: $linked" else "Связанных событий пока нет",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        message?.let { text ->
            item(key = "message") {
                Hint(if (failed) "Ошибка. $text" else text, Modifier.padding(top = 4.dp))
            }
        }

        item(key = "how") {
            SectionLabel("Как это работает")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Событие создаётся без собственного уведомления: напоминает сама Нова. Если Google Календарь " +
                            "присылает второе уведомление, отключите его в настройках календаря.",
                        style = captionStyle(),
                    )
                    Text(
                        "Ежедневные и еженедельные напоминания становятся серией в календаре. Часовые и месячные " +
                            "с 29–31 числом показываются ближайшим событием: Нова переносит такие даты на конец месяца, " +
                            "а правило календаря этого не умеет.",
                        style = captionStyle(),
                    )
                    Text(
                        "Выключение дублирования убирает события, которые добавила Нова. События, созданные вручную, " +
                            "и остальной календарь не трогаются.",
                        style = captionStyle(),
                    )
                }
            }
        }
    }

    if (confirmOff) {
        ConfirmDialog(
            "Выключить дублирование?",
            "События, которые Нова добавила в календарь, будут удалены. Остальной календарь не изменится.",
            "Выключить",
            onConfirm = { confirmOff = false; disable() },
            onDismiss = { confirmOff = false },
        )
    }
}

/** Короткий итог: «Добавлено: 2. Обновлено: 1. Убрано: 0». */
private fun calendarReportText(report: CalendarMirror.Report): String {
    if (!report.ok) return "Не получилось: ${report.error}"
    val parts = buildList {
        add("Добавлено: ${report.added}")
        add("Обновлено: ${report.updated}")
        if (report.removed > 0) add("Убрано: ${report.removed}")
        if (report.failed > 0) add("Не удалось: ${report.failed}")
    }
    return parts.joinToString(". ") + "."
}
