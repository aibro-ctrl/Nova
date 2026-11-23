package ai.nova.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import ai.nova.app.AppContainer
import ai.nova.app.security.KeystoreSecretStore
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaField
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SearchBox
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import ai.nova.app.ui.components.rememberResumeTick
import ai.nova.core.ai.AIProviderFactory
import ai.nova.core.ai.AIProviderType
import ai.nova.core.ai.ModelInfo
import kotlinx.coroutines.launch

/** Короткое пояснение к провайдеру — чтобы было понятно, что выбрать. */
private fun AIProviderType.about(): String = when (this) {
    AIProviderType.OPENAI -> "GPT нового поколения и o-серия · platform.openai.com"
    AIProviderType.GEMINI -> "Gemini Pro и Flash · есть бесплатный лимит · aistudio.google.com"
    AIProviderType.ANTHROPIC -> "Claude Opus, Sonnet, Haiku · console.anthropic.com"
    AIProviderType.OPENROUTER -> "Сотни моделей разных компаний по одному ключу · openrouter.ai"
    AIProviderType.DEEPSEEK -> "DeepSeek Chat и Reasoner · недорого · platform.deepseek.com"
    AIProviderType.MISTRAL -> "Mistral Small/Medium/Large · console.mistral.ai"
    AIProviderType.GROQ -> "Llama и другие открытые модели, очень быстро · console.groq.com"
    AIProviderType.XAI -> "Grok, актуальное поколение · console.x.ai"
    AIProviderType.CUSTOM -> "Ollama, LM Studio, vLLM на вашем компьютере — без облака"
    AIProviderType.NOVA_CLOUD -> "Сервер Нова: работает после входа в аккаунт, без своего ключа"
}

@Composable
fun AiPage(c: AppContainer, onBack: () -> Unit, openProvider: (AIProviderType) -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val resumeTick = rememberResumeTick()
    NovaScreen(title = "AI", subtitle = "Необязательно: команды и так понимаются на устройстве", onBack = onBack) {
        if (c.supabaseConfig().isConfigured) item(key = "cloud") { NovaCloudSection(c) }
        item(key = "offline") { OfflineModelSection(c) }
        item(key = "use") {
            SectionLabel("Облачный AI")
            Group(Modifier.padding(top = 4.dp)) {
                SwitchItem(
                    "Облачный AI", if (s.useAI) "Подключается, когда модель на телефоне не справилась. Нужен интернет и API-ключ" else "Выключен: отвечает модель на телефоне, команды выполняются на устройстве",
                    s.useAI, icon = Icons.Rounded.AutoAwesome,
                ) { v -> scope.launch { c.settings.setUseAI(v) } }
            }
            Hint(
                "Подключите один или несколько провайдеров. Запрос уходит первому включённому; если он не отвечает (нет ключа, лимит, сбой) — " +
                    "следующему, а без интернета команда выполнится на устройстве.",
            )
        }
        val active = s.providers.filter { it.enabled }
        if (active.isNotEmpty()) item(key = "active") {
            SectionLabel("Подключены · по приоритету")
            Group(Modifier.animateContentSize()) {
                active.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 60.dp)
                    val hasKey = remember(p.type, resumeTick) { c.secrets.contains(KeystoreSecretStore.aiKey(p.type.id)) }
                    RowItem(
                        title = p.type.title,
                        subtitle = p.effectiveModel.ifBlank { "модель не выбрана" } + if (!hasKey && p.type != AIProviderType.CUSTOM) " · нужен ключ" else "",
                        onClick = { openProvider(p.type) }, chevron = true,
                        leading = {
                            Column {
                                IconButton(onClick = { scope.launch { moveAmongEnabled(c, active.map { it.type }, i, -1) } }, enabled = i > 0, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Выше")
                                }
                                IconButton(onClick = { scope.launch { moveAmongEnabled(c, active.map { it.type }, i, 1) } }, enabled = i < active.lastIndex, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Ниже")
                                }
                            }
                        },
                        trailing = { StatusDot(hasKey || p.type == AIProviderType.CUSTOM) },
                    )
                }
            }
        }
        item(key = "all") {
            SectionLabel(if (active.isEmpty()) "Выберите провайдера" else "Другие провайдеры")
            Group {
                val others = s.providers.filter { !it.enabled }
                others.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    RowItem(title = p.type.title, subtitle = p.type.about(), onClick = { openProvider(p.type) }, chevron = true)
                }
            }
        }
        item(key = "emb") {
            SectionLabel("Поиск")
            Group {
                SwitchItem("Поиск по смыслу через AI", "Тексты ваших заметок, задач и напоминаний отправляются провайдеру AI (OpenAI или Gemini, если подключены). Выключено — работает локальный поиск с синонимами, ничего не уходит наружу.",
                    s.embeddingsEnabled) { v -> scope.launch { c.settings.setEmbeddings(v) } }
            }
            SectionLabel("Облако")
            Group {
                SwitchItem("Синхронизировать историю чата", "Заметки, задачи и расходы синхронизируются при входе в аккаунт. Переписка с Нова — только если включить это: она хранится на сервере открытым текстом.",
                    s.syncChat) { v -> scope.launch { c.settings.setSyncChat(v) } }
            }
        }
    }
}

/** Перестановка внутри включённых: меняем местами соседей в общем порядке. */
private suspend fun moveAmongEnabled(c: AppContainer, enabled: List<AIProviderType>, index: Int, delta: Int) {
    val other = enabled.getOrNull(index + delta) ?: return
    val order = c.settings.current().aiOrder
    val from = order.indexOf(enabled[index])
    val to = order.indexOf(other)
    c.settings.moveProvider(enabled[index], to - from)
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) Color(0xFF22C55E) else MaterialTheme.colorScheme.error))
}

/** Страница одного провайдера: ключ, модель (со списком с сервера), адрес, проверка. */
@Composable
fun ProviderScreen(c: AppContainer, type: AIProviderType, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val p = s.provider(type)
    val scope = rememberCoroutineScope()
    var keyVersion by remember { mutableIntStateOf(0) }
    val hasKey = remember(type, keyVersion) { c.secrets.contains(KeystoreSecretStore.aiKey(type.id)) }
    var key by remember(type) { mutableStateOf("") }
    var endpoint by remember(type, p.endpoint) { mutableStateOf(p.endpoint) }
    var picker by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val needsKey = type != AIProviderType.CUSTOM

    NovaScreen(title = type.title, subtitle = type.about(), onBack = onBack) {
        item(key = "enable") {
            Group(Modifier.padding(top = 4.dp)) {
                SwitchItem(
                    "Использовать", if (p.enabled) "Участвует в ответах по приоритету" else "Выключен",
                    p.enabled, enabled = hasKey || !needsKey,
                ) { v -> scope.launch { c.settings.setProviderEnabled(type, v); if (v && !s.useAI) c.settings.setUseAI(true) } }
            }
            if (needsKey && !hasKey) Hint("Сначала сохраните API-ключ — после этого провайдер включится сам.")
        }
        if (needsKey) item(key = "key") {
            SectionLabel("API-ключ")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(
                            if (hasKey) "Ключ сохранён в зашифрованном виде" else "Ключ не задан",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    NovaField(key, { key = it }, if (hasKey) "Новый ключ" else "Вставьте ключ", keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Сохранить", {
                            c.secrets.put(KeystoreSecretStore.aiKey(type.id), ai.nova.core.ai.AIConfig.cleanApiKey(key)); key = ""; keyVersion++; result = null
                            testing = true
                            scope.launch {
                                c.settings.setProviderEnabled(type, true); c.settings.setUseAI(true)
                                // Сразу после сохранения: если выбранной модели у этого ключа нет — подбираем подходящую, затем проверяем связь.
                                val ids = runCatching { ai.nova.core.ai.AIProviderFactory.listModels(c.http, c.aiConfig(type)) }.getOrNull()?.map { it.id }.orEmpty()
                                if (ids.isNotEmpty() && p.effectiveModel !in ids) {
                                    val pick = type.suggestedModels.firstOrNull { it in ids } ?: ids.first()
                                    c.settings.setProviderConfig(type, pick, p.endpoint)
                                    kotlinx.coroutines.delay(150)
                                }
                                result = testAiConnection(c, type).fold({ it }, { "Ошибка: ${it.message}" })
                                testing = false
                            }
                        }, enabled = key.isNotBlank())
                        if (hasKey) SecondaryButton("Удалить", {
                            c.secrets.put(KeystoreSecretStore.aiKey(type.id), null); keyVersion++
                            scope.launch { c.settings.setProviderEnabled(type, false) }
                        }, danger = true)
                    }
                }
            }
            Hint("Ключ хранится только на этом телефоне (Android Keystore), не синхронизируется и не показывается повторно.")
        }
        item(key = "model") {
            SectionLabel("Модель")
            Group {
                RowItem(
                    title = p.effectiveModel.ifBlank { "Не выбрана" },
                    subtitle = if (p.model.isBlank() && type.defaultModel.isNotBlank()) "по умолчанию · нажмите, чтобы выбрать другую" else "нажмите, чтобы выбрать другую",
                    icon = Icons.Rounded.Memory, chevron = true, onClick = { picker = true },
                )
            }
        }
        if (type.endpointEditable) item(key = "endpoint") {
            SectionLabel("Адрес API")
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovaField(endpoint, { endpoint = it.trim() }, "Адрес", placeholder = type.defaultEndpoint, keyboardType = KeyboardType.Uri,
                        supporting = if (type == AIProviderType.CUSTOM) "Например, http://192.168.1.10:11434/v1 для Ollama. http:// — только в локальной сети" else null)
                    if (endpoint != p.endpoint) PrimaryButton("Сохранить адрес", { scope.launch { c.settings.setProviderConfig(type, p.model, endpoint) } })
                }
            }
        }
        item(key = "test") {
            Group(Modifier.padding(top = 16.dp)) {
                RowItem(
                    title = if (testing) "Проверяю…" else "Проверить подключение",
                    subtitle = result,
                    icon = Icons.Rounded.Link,
                    onClick = if (!testing && (hasKey || !needsKey)) ({
                        testing = true
                        scope.launch {
                            result = testAiConnection(c, type).fold({ it }, { "Ошибка: ${it.message}" })
                            testing = false
                        }
                    }) else null,
                    trailing = { if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                )
            }
        }
        item(key = "last-failure") {
            val tick = ai.nova.app.ui.components.rememberResumeTick()
            val failure = remember(tick, result) { c.engine.lastAiFailure }
            failure?.let { f ->
                val at = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale("ru")).format(f.at.atZone(java.time.ZoneId.systemDefault()))
                Hint("Последний сбой AI ($at): ${f.message}\nИз-за него команда была выполнена на устройстве. Нажмите «Проверить подключение» — проверка делает такой же запрос, как при работе.")
            }
        }
    }

    if (picker) {
        ModelPicker(c, type, current = p.effectiveModel, canLoad = hasKey || !needsKey, onDismiss = { picker = false }) { model ->
            scope.launch { c.settings.setProviderConfig(type, model, p.endpoint) }
            picker = false
        }
    }
}

/** Выбор модели: список с сервера провайдера (по вашему ключу) + известные модели + своя. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(c: AppContainer, type: AIProviderType, current: String, canLoad: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf<List<ModelInfo>?>(c.modelCache[type]) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { AIProviderFactory.listModels(c.http, c.aiConfig(type)) }
                .onSuccess { loaded = it; c.modelCache[type] = it }
                .onFailure { error = it.message ?: "не удалось загрузить список" }
            loading = false
        }
    }
    LaunchedEffect(type) { if (canLoad && loaded == null) load() }

    // Модель по умолчанию — первой, дальше каталог от новых к старым, потом список с сервера:
    // иначе свежие модели теряются в алфавитном списке из сотни id.
    val known = (listOf(type.defaultModel) + type.suggestedModels).distinct().map { ModelInfo(it) }
    val all = (known + (loaded ?: emptyList())).distinctBy { it.id }
    val shown = if (query.isBlank()) all else all.filter { it.id.contains(query, true) || it.name.contains(query, true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Модель ${type.title}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            loading -> "Загружаю список с сервера…"
                            loaded != null -> "Доступно по вашему ключу: ${loaded?.size ?: 0}"
                            !canLoad -> "Сохраните ключ, чтобы увидеть все модели"
                            else -> error ?: "Известные модели"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canLoad) IconButton(onClick = { load() }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, contentDescription = "Обновить список")
                }
            }
            SearchBox(query, { query = it }, "Найти или ввести свою модель", Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (query.isNotBlank() && all.none { it.id.equals(query.trim(), true) }) {
                    item(key = "custom") {
                        RowItem(title = "Использовать «${query.trim()}»", subtitle = "Своя модель — как она называется в API провайдера", onClick = { onPick(query.trim()) })
                    }
                }
                items(shown, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when {
                                m.name != m.id -> Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                m.id == type.defaultModel -> Text("по умолчанию", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        if (m.id == current) Icon(Icons.Rounded.Check, contentDescription = "Выбрана", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                item(key = "bottom") { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
            if (!canLoad) TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)) { Text("Закрыть") }
        }
    }
}


/**
 * Офлайн-модель: свободный разговор без интернета и ключей.
 * Главный путь — одна кнопка: Нова сама подбирает модель под этот телефон и заменяет старую.
 * Выбор репозитория вручную остался для тех, кому нужен особый файл.
 */
@Composable
private fun OfflineModelSection(c: AppContainer) {
    val state by c.offlineLlm.state.collectAsStateWithLifecycle()
    val notice by c.offlineLlm.notice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(c.offlineLlm.enabled) }
    var picking by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val metered = remember {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
    }
    SectionLabel("Офлайн-модель · без интернета")
    Group {
        when (val st = state) {
            ai.nova.app.llm.OfflineLlm.State.Ready -> {
                SwitchItem(
                    "Отвечать на любые вопросы",
                    "${c.offlineLlm.modelLabel} на телефоне: «почему небо голубое», «посоветуй фильм». Отвечает первой; облачный AI — только если не справилась",
                    enabled, icon = Icons.Rounded.AutoAwesome,
                ) { v -> enabled = v; c.offlineLlm.enabled = v }
                GroupDivider()
                RowItem(
                    title = "Обновить модель",
                    subtitle = "Сейчас ${c.offlineLlm.modelLabel}. Нажмите — подберу модель под этот телефон, скачаю и заменю. Выбирать ничего не нужно",
                    icon = Icons.Rounded.SystemUpdate,
                    onClick = { scope.launch { c.offlineLlm.update() } },
                    trailing = { TextButton(onClick = { scope.launch { c.offlineLlm.update() } }) { Text("Обновить") } },
                )
                if (!c.offlineLlm.isDefaultModel) {
                    GroupDivider()
                    RowItem(
                        title = "Вернуться к встроенной Gemma 3 1B",
                        subtitle = "Скачивать заново не нужно, если файл остался на телефоне",
                        onClick = { c.offlineLlm.useDefault() },
                    )
                }
                GroupDivider()
                RowItem(title = "Удалить модель", subtitle = "Освободит ${ai.nova.core.ai.LocalModels.sizeText(c.offlineLlm.sizeBytes)}", onClick = { c.offlineLlm.delete() })
            }
            ai.nova.app.llm.OfflineLlm.State.Missing, is ai.nova.app.llm.OfflineLlm.State.Failed -> RowItem(
                title = "Скачать офлайн-модель",
                subtitle = (st as? ai.nova.app.llm.OfflineLlm.State.Failed)?.let { "Не получилось: ${it.message}" }
                    ?: ("Подберу модель под этот телефон — от 0,8 до 2,5 ГБ, смотря сколько памяти в телефоне. Свободный разговор без интернета и без ключей" +
                        if (metered) ". Сейчас мобильный интернет — лучше по Wi‑Fi" else ""),
                icon = Icons.Rounded.AutoAwesome,
                trailing = { TextButton(onClick = { scope.launch { c.offlineLlm.update() } }) { Text(if (st is ai.nova.app.llm.OfflineLlm.State.Failed) "Ещё раз" else "Скачать") } },
            )
            ai.nova.app.llm.OfflineLlm.State.Resolving -> RowItem(
                title = "Подбираю модель…",
                subtitle = "Проверяю каталог моделей — пара секунд",
                icon = Icons.Rounded.SystemUpdate,
                trailing = { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            )
            is ai.nova.app.llm.OfflineLlm.State.Downloading -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Скачиваю ${c.offlineLlm.modelLabel} · ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Text("Можно закрыть экран — при обрыве загрузка продолжится с того же места.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            ai.nova.app.llm.OfflineLlm.State.Verifying -> RowItem(title = "Проверяю файл…", subtitle = "Контрольная сумма, несколько секунд", icon = Icons.Rounded.AutoAwesome)
            is ai.nova.app.llm.OfflineLlm.State.Unsupported -> RowItem(title = "Недоступна на этом телефоне", subtitle = st.reason.replaceFirstChar { it.uppercase() }, icon = Icons.Rounded.AutoAwesome)
        }
    }
    if (notice != null) {
        Text(
            notice.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
    }
    val idle = state == ai.nova.app.llm.OfflineLlm.State.Ready
    if (!idle && state !is ai.nova.app.llm.OfflineLlm.State.Downloading && state !is ai.nova.app.llm.OfflineLlm.State.Resolving) {
        Group(Modifier.padding(top = 12.dp)) {
            RowItem(
                title = "Выбрать модель",
                subtitle = "Лёгкая, сбалансированная или умная — с размером и подсказкой, какая подходит телефону",
                icon = Icons.Rounded.SystemUpdate,
                onClick = { picking = true },
            )
        }
    }
    if (idle) {
        Group(Modifier.padding(top = 12.dp)) {
            RowItem(
                title = "Сменить модель",
                subtitle = "Лёгкая, сбалансированная, умная или свой файл .gguf с Hugging Face",
                icon = Icons.Rounded.SystemUpdate,
                onClick = { picking = true },
            )
        }
    }
    Hint(
        "Модель работает прямо на телефоне: вопросы никуда не отправляются. Спрашиваем её первой, а облачный AI — только если он включён " +
            "и модель не справилась.",
    )
    if (picking) OfflineModelPicker(c, onDismiss = { picking = false })
}


/** «Облако Нова»: умный разговор через сервер Нова, без своего ключа — нужен только вход в аккаунт. */
@Composable
private fun NovaCloudSection(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signedIn = auth is ai.nova.core.auth.AuthState.SignedIn
    SectionLabel("Облако ${s.assistantName}")
    Group(Modifier.padding(top = 4.dp)) {
        SwitchItem(
            "Облако ${s.assistantName}",
            when {
                !signedIn -> "Войдите в аккаунт («Настройки → Аккаунт») — и ${s.assistantName} ответит на любые вопросы без своего ключа"
                s.novaCloud && !s.useAI -> "Включено, но «Облачный AI» ниже выключен — сейчас отвечает модель на телефоне"
                s.novaCloud -> "Включено: любые вопросы и сложные фразы через сервер ${s.assistantName}. Есть дневной лимит"
                else -> "Выключено"
            },
            s.novaCloud && signedIn, enabled = signedIn, icon = Icons.Rounded.AutoAwesome,
        ) { v -> scope.launch { c.settings.setNovaCloud(v); if (v && !s.useAI) c.settings.setUseAI(true) } }
    }
}

/** Готовые варианты модели: простые названия вместо репозиториев Hugging Face. */
private data class ModelOption(val kind: String, val repo: String, val note: String) {
    val title: String get() = "$kind · ${ai.nova.core.ai.LocalModels.friendlyName(repo)}"
}

private val MODEL_OPTIONS = listOf(
    ModelOption("Лёгкая", "unsloth/gemma-3-1b-it-GGUF", "самая быстрая и точная на бытовых вопросах"),
    ModelOption("Сбалансированная", "Qwen/Qwen2.5-3B-Instruct-GGUF", "заметно умнее, но и отвечает вдвое дольше"),
    ModelOption("Умная", "unsloth/Qwen3-4B-Instruct-2507-GGUF", "лучше всех разбирается в сложном, нужен телефон от 12 ГБ"),
)

/**
 * Выбор модели: три понятных варианта под телефон (размер и рекомендация считаются сами)
 * и, если нужно, свой файл .gguf с короткой инструкцией. Скачивание видно прямо в листе.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineModelPicker(c: AppContainer, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val state by c.offlineLlm.state.collectAsStateWithLifecycle()
    val notice by c.offlineLlm.notice.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val totalRam = remember {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        if (am != null) am.getMemoryInfo(info)
        info.totalMem
    }
    val suitable = remember(totalRam) { ai.nova.core.ai.LocalModels.recommended(totalRam) }
    val recommendedRepo = suitable.firstOrNull()

    // Размеры подтягиваем заранее: пользователь должен видеть «1,3 ГБ», а не гадать.
    var files by remember { mutableStateOf<Map<String, ai.nova.core.ai.ModelFile?>>(emptyMap()) }
    var preparing by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var customFiles by remember { mutableStateOf<List<ai.nova.core.ai.ModelFile>?>(null) }
    var customLoading by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<ai.nova.core.ai.ModelFile?>(null) }

    fun start(file: ai.nova.core.ai.ModelFile) {
        retry = file
        scope.launch { c.offlineLlm.download(file) }
    }

    LaunchedEffect(Unit) {
        // Три запроса разом: иначе список размеров собирается заметно дольше.
        files = kotlinx.coroutines.coroutineScope {
            val deferred = MODEL_OPTIONS.map { o ->
                async {
                    o.repo to runCatching { ai.nova.core.ai.LocalModels.pick(c.modelCatalog.files(o.repo)) }.getOrNull()
                }
            }
            deferred.map { it.await() }.toMap()
        }
        preparing = false
    }

    fun showFiles(query: String) {
        val repo = query.trim().removePrefix("https://huggingface.co/").trim('/').removeSuffix(".git")
        if (repo.isBlank() || !repo.contains('/')) {
            customError = "Нужно название вида «автор/модель», например unsloth/Qwen2.5-3B-Instruct-GGUF"
            return
        }
        customLoading = true; customError = null; customFiles = null
        scope.launch {
            val list = c.modelCatalog.files(repo)
            customFiles = list
            customError = if (list.isEmpty()) "В «$repo» нет готовых файлов .gguf. Нужна сборка со словом GGUF в названии" else null
            customLoading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 660.dp).imePadding()) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    Text("Модель на телефоне", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Отвечает на вопросы без интернета и ключей, а если правила не поняли команду — переписывает фразу в команду. Выберите одну — Нова скачает её сама",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Что происходит сейчас: скачивание, проверка, ошибка или готово.
            item(key = "status") {
                when (val st = state) {
                    is ai.nova.app.llm.OfflineLlm.State.Downloading -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Скачиваю ${c.offlineLlm.modelLabel} · ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                        androidx.compose.material3.LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        Text(
                            "Лист можно закрыть: загрузка продолжится в фоне и докачается с того же места",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    ai.nova.app.llm.OfflineLlm.State.Verifying, ai.nova.app.llm.OfflineLlm.State.Resolving -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            if (st == ai.nova.app.llm.OfflineLlm.State.Verifying) "Проверяю файл — несколько секунд" else "Ищу свежую модель в каталоге",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    is ai.nova.app.llm.OfflineLlm.State.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Не получилось скачать: ${st.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Проверьте интернет и свободное место, затем попробуйте ещё раз — докачка начнётся с того же места",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        retry?.let { f -> PrimaryButton("Повторить", { start(f) }, modifier = Modifier.padding(top = 10.dp)) }
                    }
                    ai.nova.app.llm.OfflineLlm.State.Ready -> RowItem(
                        title = "Модель готова",
                        subtitle = "${c.offlineLlm.modelLabel} уже работает — можно закрывать",
                        icon = Icons.Rounded.Check,
                        trailing = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
                    )
                    else -> Unit
                }
            }

            item(key = "pick") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { SectionLabel("Подобрать для телефона") }
            }
            item(key = "options") {
                Group {
                    MODEL_OPTIONS.forEachIndexed { i, option ->
                        if (i > 0) GroupDivider()
                        val file = files[option.repo]
                        val size = file?.let { ai.nova.core.ai.LocalModels.sizeText(it.size) }
                        val subtitle = listOfNotNull(
                            size,
                            if (option.repo == recommendedRepo) "рекомендуем вашему телефону"
                            else if (option.repo !in suitable) "может не хватить памяти" else null,
                            option.note,
                        ).joinToString(" · ")
                        RowItem(
                            title = option.title,
                            subtitle = subtitle,
                            icon = if (option.repo == recommendedRepo) Icons.Rounded.Check else Icons.Rounded.Memory,
                            onClick = file?.let { f -> { start(f) } },
                            trailing = {
                                when {
                                    preparing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    file != null -> TextButton(onClick = { start(file) }) { Text("Скачать") }
                                    else -> TextButton(onClick = { retry = null; scope.launch { c.offlineLlm.update() } }) { Text("Скачать") }
                                }
                            },
                        )
                    }
                }
            }
            item(key = "pick-hint") {
                Hint("Все три — разговорные модели для телефона: понимают вопрос, помнят диалог, работают без интернета. Картинки и код они не делают — на телефоне это лишнее. Крупнее модель — умнее ответы, но и ждать дольше: на телефоне 8 ГБ 4B думает по 30–40 секунд.")
            }

            item(key = "advanced") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { SectionLabel("Своя модель") }
            }
            item(key = "advanced-row") {
                Group {
                    RowItem(
                        title = "Файл .gguf с Hugging Face",
                        subtitle = if (advanced) "Свернуть инструкцию" else "Если нужна конкретная модель — покажу, где взять название",
                        icon = Icons.Rounded.Link,
                        onClick = { advanced = !advanced },
                        trailing = { Icon(if (advanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = null) },
                    )
                }
            }
            if (advanced) {
                item(key = "advanced-help") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            ".gguf — это сама модель одним файлом: работает на телефоне без интернета. Где взять название:\n" +
                                "1. Откройте huggingface.co и найдите модель со словом GGUF, например «Qwen2.5 3B Instruct GGUF».\n" +
                                "2. На странице модели нажмите «⋯» → «Copy model name» — получится «автор/название».\n" +
                                "3. Вставьте это в поле ниже. Подходящий файл Нова выберет сама — для телефона это обычно Q4_K_M.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NovaField(
                            value = custom, onChange = { custom = it }, label = "Репозиторий",
                            placeholder = "unsloth/Qwen2.5-3B-Instruct-GGUF",
                            supporting = "Можно вставить ссылку на страницу модели целиком",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        PrimaryButton(
                            text = "Показать файлы",
                            onClick = { showFiles(custom) },
                            enabled = custom.isNotBlank() && !customLoading,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (customLoading) {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                        customError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                val list = customFiles.orEmpty()
                if (list.isNotEmpty()) {
                    item(key = "custom-list") {
                        Group(Modifier.padding(horizontal = 12.dp)) {
                            list.forEachIndexed { i, f ->
                                if (i > 0) GroupDivider()
                                val quant = f.quant.ifBlank { f.title }
                                RowItem(
                                    title = "$quant · ${ai.nova.core.ai.LocalModels.sizeText(f.size)}",
                                    subtitle = listOfNotNull(
                                        ai.nova.core.ai.LocalModels.quantNote(f.quant).takeIf { it.isNotBlank() },
                                        f.fileName,
                                    ).joinToString(" · "),
                                    maxSubtitleLines = 2,
                                    trailing = { TextButton(onClick = { start(f) }) { Text("Скачать") } },
                                )
                            }
                        }
                    }
                }
            }

            if (notice != null) {
                item(key = "notice") {
                    Text(notice.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                }
            }
            item(key = "bottom") {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 24.dp)) { Text("Закрыть") }
            }
        }
    }
}