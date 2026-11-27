package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.security.KeystoreSecretStore
import ai.nova.app.sync.SheetsScript
import ai.nova.app.sync.SheetsSyncEngine
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.MoreToggle
import ai.nova.app.ui.components.NovaField
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import ai.nova.app.ui.components.captionStyle
import ai.nova.core.sync.SheetsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * «Google Таблицы»: все записи Нова — напоминания, заметки, задачи, покупки, траты, сценарии и память —
 * в собственной таблице пользователя. Прямого доступа к Sheets API нет, поэтому посредником работает
 * веб-приложение Apps Script: его код Нова копирует в буфер, а взамен просит адрес развёртывания.
 *
 * Это же решает переустановку: после ввода тех же трёх значений таблица отдаёт всё, что в ней накопилось.
 */
@Composable
fun SheetsPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val loaded by c.settings.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    // Код доступа уже сохранён в Keystore: в поле его не показываем, только спрашиваем заново при смене.
    var savedToken by remember { mutableStateOf("") }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var scriptOpen by remember { mutableStateOf(false) }

    // Настройки читаются с диска асинхронно: поля заполняем, когда значения уже пришли,
    // иначе первое же «Сохранить» затёрло бы ссылку и адрес пустыми строками.
    LaunchedEffect(loaded, s.sheetsId, s.sheetsWebAppUrl) {
        if (loaded && !prefilled) {
            id = s.sheetsId
            url = s.sheetsWebAppUrl
            prefilled = true
        }
    }
    // Расшифровка секрета может быть небыстрой — не держим на ней главный поток.
    LaunchedEffect(Unit) {
        savedToken = withContext(Dispatchers.IO) {
            c.secrets.get(KeystoreSecretStore.SHEETS_TOKEN)
        }.orEmpty()
    }
    val configured = SheetsSync.spreadsheetId(id) != null && url.isNotBlank() && savedToken.isNotBlank()
    val lastSync = remember(s.sheetsLastSyncAt) {
        s.sheetsLastSyncAt.takeIf { it > 0 }
            ?.let { DateTimeFormatter.ofPattern("d MMMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
    }

    fun done(text: String, error: Boolean) { message = text; failed = error; busy = false }

    fun copy(text: String, label: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Сохранение полей: код доступа уходит в Keystore, остальное — в обычные настройки. */
    fun save(thenSync: Boolean = false) {
        scope.launch {
            busy = true
            val cleanId = SheetsSync.spreadsheetId(id)
            if (cleanId == null) { done("Не понял ссылку на таблицу. Скопируйте её из адресной строки Google Таблиц.", true); return@launch }
            c.settings.setSheetsId(cleanId)
            c.settings.setSheetsWebAppUrl(url.trim())
            val typedToken = token.trim()
            if (typedToken.isNotEmpty()) {
                withContext(Dispatchers.IO) { c.secrets.put(KeystoreSecretStore.SHEETS_TOKEN, typedToken) }
                savedToken = typedToken
                token = ""
            }
            if (!thenSync) {
                val hint = if (savedToken.isBlank()) " Осталось придумать код доступа и вставить его в скрипт." else ""
                done("Сохранено.$hint", false)
                return@launch
            }
            val report = c.sheetsSync.sync()
            if (report.pulled > 0) c.rescheduleReminders()
            done(reportText(report), !report.ok)
        }
    }

    NovaScreen(title = "Google Таблицы", subtitle = "Все записи в вашей таблице", onBack = onBack) {
        item(key = "intro") {
            Group(Modifier.padding(top = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Нова будет складывать напоминания, заметки, задачи, покупки, траты, сценарии и память в вашу таблицу. " +
                            "Данные идут только в ваш Google-аккаунт: у Нова нет ни доступа к нему, ни своего сервера.",
                        style = captionStyle(),
                    )
                    Text(
                        "Таблица же работает и как резервная копия: переустановили приложение — ввели те же три значения, " +
                            "и всё вернулось.",
                        style = captionStyle(),
                    )
                }
            }
        }

        item(key = "setup") {
            SectionLabel("Подключение")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovaField(
                        value = id, onChange = { id = it },
                        label = "Ссылка или ID таблицы",
                        placeholder = "https://docs.google.com/spreadsheets/d/…",
                        keyboardType = KeyboardType.Uri,
                        supporting = "Создайте пустую таблицу и скопируйте ссылку из адресной строки",
                    )
                    NovaField(
                        value = url, onChange = { url = it },
                        label = "Адрес веб-приложения",
                        placeholder = "https://script.google.com/macros/s/…/exec",
                        keyboardType = KeyboardType.Uri,
                        supporting = "Появится после развёртывания скрипта — шаг 3 ниже",
                    )
                    NovaField(
                        value = token, onChange = { token = it },
                        label = if (savedToken.isBlank()) "Код доступа" else "Код доступа (уже сохранён)",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        supporting = "Придумайте сами и вставьте тот же код в скрипт — без него таблица не примет записи",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Сохранить и синхронизировать", { save(thenSync = true) }, enabled = !busy)
                    }
                }
            }
        }

        if (savedToken.isNotBlank()) item(key = "sync") {
            SectionLabel("Синхронизация")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (lastSync != null) "Последняя синхронизация: $lastSync" else "Ещё не синхронизировались",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Синхронизировать", {
                            scope.launch {
                                busy = true
                                val report = c.sheetsSync.sync()
                                if (report.pulled > 0) c.rescheduleReminders()
                                done(reportText(report), !report.ok)
                            }
                        }, enabled = !busy && configured)
                        SecondaryButton("Проверить связь", {
                            scope.launch {
                                busy = true
                                val report = c.sheetsSync.test()
                                done(if (report.ok) "Связь есть: скрипт отвечает." else reportText(report), !report.ok)
                            }
                        }, enabled = !busy && configured)
                    }
                    GroupDivider()
                    SwitchItem(
                        "Синхронизировать сама",
                        "Раз в 15 минут и сразу после изменений. Выключено — только по кнопке",
                        s.sheetsAutoSync,
                    ) { v ->
                        scope.launch {
                            c.settings.setSheetsAutoSync(v)
                            if (v) { c.sheetsScheduler.schedulePeriodic(); c.sheetsScheduler.requestSoon(1) }
                            else c.sheetsScheduler.cancelAll()
                        }
                    }
                }
            }
        }

        message?.let { text ->
            item(key = "message") {
                Hint(text, Modifier.padding(top = 4.dp))
            }
        }

        item(key = "more") { MoreToggle(advanced, { advanced = !advanced }) }

        if (advanced) item(key = "steps") {
            SectionLabel("Как настроить таблицу")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Step(1, "Создайте пустую Google Таблицу и скопируйте ссылку из адресной строки в поле выше.")
                    Step(2, "В таблице: Расширения → Apps Script. Удалите пример и вставьте код Нова.")
                    Step(3, "Сохраните, затем «Развернуть» → «Новое развёртывание» → тип «Веб-приложение». «Запуск от имени» — вы, «Доступ» — все. Скопируйте адрес /exec в поле выше.")
                    Step(4, "Придумайте код доступа, вставьте его и в поле выше, и в скрипт (переменная TOKEN), затем сохраните развёртывание заново.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Открыть код скрипта", onClick = { scriptOpen = true })
                    }
                }
            }
        }

        item(key = "privacy") {
            Hint(
                "Ключи AI, пароль базы и сессия аккаунта в таблицу не попадают никогда. История чата — только если вы сами " +
                    "включили её синхронизацию в «Аккаунте».",
                Modifier.padding(top = 8.dp),
            )
        }
    }

    if (scriptOpen) {
        val tokenForScript = token.ifBlank { savedToken }.ifBlank { "Придумайте-код-доступа" }
        val code = remember(tokenForScript) { SheetsScript.text(tokenForScript) }
        AlertDialog(
            onDismissRequest = { scriptOpen = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Код для Apps Script") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Вставьте это в редактор Apps Script целиком. Код доступа уже подставлен — он должен совпадать с полем выше.",
                        style = captionStyle(),
                    )
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            code,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copy(code, "Apps Script")
                    scriptOpen = false
                }) { Text("Скопировать") }
            },
            dismissButton = { TextButton(onClick = { scriptOpen = false }) { Text("Закрыть") } },
        )
    }
}

/** Пункт инструкции: номер в кружке и текст. */
@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

/** Человеческий итог синхронизации: сколько уехало, сколько вернулось и что не влезло. */
private fun reportText(report: SheetsSyncEngine.Report): String {
    if (!report.ok) return "Не получилось: ${report.error}"
    val parts = mutableListOf<String>()
    parts += "Отправлено записей: ${report.sent}"
    parts += "Получено новых: ${report.pulled}"
    if (report.tooLong > 0) parts += "Не влезли в ячейку (больше 45 000 символов): ${report.tooLong}"
    return parts.joinToString(". ") + "."
}
