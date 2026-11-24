package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.ui.components.ConfirmDialog
import ai.nova.app.ui.components.EditorSheet
import ai.nova.app.ui.components.EmptyState
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaFab
import ai.nova.app.ui.components.NovaField
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.Pills
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SearchBox
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.Segmented
import ai.nova.app.ui.components.groupColor
import ai.nova.app.ui.components.messageLink
import ai.nova.app.ui.components.messageText
import ai.nova.app.ui.components.pressScale
import ai.nova.core.model.MemoryItem
import ai.nova.core.model.MessageRole
import ai.nova.core.model.Note
import ai.nova.core.model.NoteKind
import ai.nova.core.search.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault())

private val memoryCategories = listOf("other" to "Разное", "preference" to "Предпочтение", "fact" to "Факт", "goal" to "Цель", "person" to "Люди")
private fun categoryRu(c: String) = memoryCategories.firstOrNull { it.first == c }?.second ?: "Разное"

/** Вкладка «Записи»: заметки, идеи и то, что ассистент помнит о пользователе, плюс поиск по смыслу. */
@Composable
fun RecordsScreen(c: AppContainer, segment: Int, onSegment: (Int) -> Unit, openHistory: () -> Unit) {
    val notes by remember { c.store.notes.observe(NoteKind.NOTE) }.collectAsStateWithLifecycle(emptyList())
    val ideas by remember { c.store.notes.observe(NoteKind.IDEA) }.collectAsStateWithLifecycle(emptyList())
    val memories by remember { c.store.memories.observe() }.collectAsStateWithLifecycle(emptyList())
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var editNote by remember { mutableStateOf<Note?>(null) }
    var editMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = runCatching { c.search.search(query, limit = 30, minScore = 0.25) }.getOrDefault(emptyList())
    }

    NovaScreen(
        title = "Записи",
        subtitle = "${ai.nova.core.assistant.RuFormat.count(notes.size, "заметка", "заметки", "заметок")} · ${ai.nova.core.assistant.RuFormat.count(ideas.size, "идея", "идеи", "идей")} · ${memories.size} в памяти",
        actions = { IconButton(onClick = openHistory) { Icon(Icons.Rounded.History, contentDescription = "История разговоров") } },
        fab = { if (query.isBlank()) NovaFab(Icons.Rounded.Add, "Добавить") { creating = true } },
    ) {
        item(key = "search") { SearchBox(query, { query = it }, "Найти по смыслу во всех записях") }
        if (query.isNotBlank()) {
            item(key = "hits-label") { SectionLabel(if (hits.isEmpty()) "Ничего не найдено" else "Найдено: ${hits.size}") }
            if (hits.isNotEmpty()) item(key = "hits") {
                Group {
                    hits.forEachIndexed { i, h ->
                        if (i > 0) GroupDivider()
                        RowItem(title = h.doc.title, subtitle = listOf(h.doc.type.titleRu, h.doc.body.take(120)).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
            }
            return@NovaScreen
        }
        item(key = "segments") {
            Segmented(listOf("Заметки", "Идеи", "Память", "Здоровье"), segment, onSegment, Modifier.padding(top = 16.dp, bottom = 8.dp))
        }
        when (segment) {
            0, 1 -> {
                if (segment == 0) item(key = "secret") { SecretNotesEntry(c) }
                val list = if (segment == 0) notes else ideas
                // Заголовок секции нужен только вместе со списком: пустое состояние говорит само за себя.
                if (list.isNotEmpty()) item(key = "list-label") {
                    SectionLabel(
                        if (segment == 0) "Заметки · ${list.size}" else "Идеи · ${list.size}",
                        trailing = {
                            TextButton(onClick = {
                                val csv = ai.nova.core.export.CsvExport.notes(list, c.time.zone())
                                scope.launch {
                                    c.device.perform(
                                        ai.nova.core.assistant.DeviceCommand.ShareFile(
                                            ai.nova.core.export.CsvExport.fileName(if (segment == 0) "notes" else "ideas", null, null),
                                            "text/csv", csv,
                                        ),
                                    )
                                }
                            }) { Text("Выгрузить", style = MaterialTheme.typography.labelLarge) }
                        },
                    )
                }
                if (list.isEmpty()) item(key = "empty") {
                    EmptyState(
                        if (segment == 0) Icons.Rounded.Description else Icons.Rounded.Lightbulb,
                        if (segment == 0) "Заметок пока нет" else "Идей пока нет",
                        if (segment == 0) "Скажите: «$name, создай заметку список покупок: молоко, хлеб»" else "Скажите: «$name, у меня идея — …»",
                    )
                }
                item(key = "list") {
                    // Плотный список строк вместо карточек: заголовок, начало текста, дата.
                    Group {
                        list.forEachIndexed { i, n ->
                            if (i > 0) GroupDivider()
                            val preview = n.content.replace('\n', ' ').trim().take(140)
                            RowItem(
                                title = n.title.ifBlank { preview.take(60).ifBlank { "Без названия" } },
                                subtitle = preview.takeIf { it.isNotBlank() },
                                maxSubtitleLines = 2,
                                onClick = { editNote = n },
                                trailing = {
                                    Text(
                                        dateFmt.format(n.updatedAt), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
                if (list.isNotEmpty()) item(key = "storage") {
                    Hint("Записи хранятся в зашифрованной базе на телефоне.")
                }
            }
            3 -> item(key = "health") { HealthPane(c, name) }
            else -> {
                if (memories.isEmpty()) item(key = "empty") {
                    EmptyState(Icons.Rounded.Psychology, "Пока ничего не запомнила", "Скажите: «$name, запомни, что я люблю зелёный чай»")
                } else item(key = "memories") {
                    Group {
                        memories.forEachIndexed { i, m ->
                            if (i > 0) GroupDivider()
                            RowItem(title = m.content, subtitle = categoryRu(m.category), onClick = { editMemory = m })
                        }
                    }
                }
            }
        }
    }

    if (creating || editNote != null) {
        val kind = editNote?.kind ?: if (segment == 1) NoteKind.IDEA else NoteKind.NOTE
        if (segment == 2 && editNote == null) {
            MemoryEditor(null, onDismiss = { creating = false }, onSave = { text, cat -> scope.launch { c.store.memories.create(text, cat) }; creating = false }, onDelete = {})
        } else {
            NoteEditor(
                initial = editNote, kind = kind,
                onDismiss = { creating = false; editNote = null },
                onSave = { title, content ->
                    val e = editNote
                    scope.launch { if (e == null) c.store.notes.create(kind, title, content) else c.store.notes.update(e.copy(title = title, content = content)) }
                    creating = false; editNote = null
                },
                onDelete = { id -> scope.launch { c.store.notes.delete(id) }; editNote = null },
            )
        }
    }
    editMemory?.let { m ->
        MemoryEditor(
            m, onDismiss = { editMemory = null },
            onSave = { text, cat -> scope.launch { c.store.memories.update(m.copy(content = text, category = cat)) }; editMemory = null },
            onDelete = { scope.launch { c.store.memories.delete(m.id) }; editMemory = null },
        )
    }
}

@Composable
private fun NoteEditor(initial: Note?, kind: NoteKind, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    var askDelete by remember { mutableStateOf(false) }
    val idea = kind == NoteKind.IDEA
    EditorSheet(if (initial == null) (if (idea) "Новая идея" else "Новая заметка") else (if (idea) "Идея" else "Заметка"), onDismiss) {
        NovaField(title, { title = it }, "Название", placeholder = "Например: Рецепт борща")
        NovaField(content, { content = it }, "Текст", placeholder = "Голосом: «запиши рецепт борща»", singleLine = false, minLines = 5)
        // Голосовая заметка: запись хранится только на этом телефоне.
        val audio = ai.nova.app.voice.VoiceMemoRecorder.audioName(content)
        val app = LocalContext.current.applicationContext as ai.nova.app.NovaApp
        if (audio != null && java.io.File(app.container.voiceMemo.dir, audio).exists()) {
            var playing by remember { mutableStateOf(false) }
            SecondaryButton(if (playing) "Остановить" else "Прослушать запись", { playing = app.container.voiceMemo.toggle(java.io.File(app.container.voiceMemo.dir, audio)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null) SecondaryButton("Удалить", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Сохранить", { onSave(title.ifBlank { content.lineSequence().firstOrNull().orEmpty().take(60) }, content) }, enabled = title.isNotBlank() || content.isNotBlank())
        }
    }
    if (askDelete && initial != null) {
        ConfirmDialog("Удалить?", "«${initial.title}» будет удалена на всех устройствах.", "Удалить", onConfirm = { onDelete(initial.id) }, onDismiss = { askDelete = false })
    }
}

@Composable
private fun MemoryEditor(initial: MemoryItem?, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.content.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category ?: "other") }
    var askDelete by remember { mutableStateOf(false) }
    EditorSheet(if (initial == null) "Запомнить" else "Память", onDismiss) {
        NovaField(text, { text = it }, "Что запомнить", singleLine = false, minLines = 2)
        Pills(memoryCategories.map { it.second }, memoryCategories.indexOfFirst { it.first == category }.coerceAtLeast(0), { category = memoryCategories[it].first })
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null) SecondaryButton("Забыть", { askDelete = true }, danger = true)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Сохранить", { onSave(text.trim(), category) }, enabled = text.isNotBlank())
        }
    }
    if (askDelete) ConfirmDialog("Забыть?", "Ассистент забудет: «${initial?.content?.take(60)}».", "Забыть", onConfirm = onDelete, onDismiss = { askDelete = false })
}

@Composable
fun HistoryScreen(c: AppContainer, onBack: () -> Unit) {
    val all by remember { c.store.conversations.observeRecent(300) }.collectAsStateWithLifecycle(emptyList())
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    val fmt = remember { DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")).withZone(ZoneId.systemDefault()) }
    var query by rememberSaveable { mutableStateOf("") }
    // Поиск по своим разговорам: «маршрут», «аптека», «ремонт» — в любом месте сообщения.
    val messages = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter { messageText(it.content).contains(q, ignoreCase = true) }
    }
    NovaScreen(title = "История", subtitle = "Разговоры с ассистентом", onBack = onBack) {
        item(key = "search") {
            SearchBox(query, { query = it }, "Поиск по разговорам")
            Spacer(Modifier.height(6.dp))
        }
        if (messages.isEmpty()) {
            item {
                if (query.isBlank()) EmptyState(Icons.Rounded.History, "История пуста", "Здесь появятся ваши разговоры с ассистентом")
                else EmptyState(Icons.Rounded.Search, "Ничего не нашлось", "Попробуйте другое слово — искать можно по любому месту сообщения")
            }
        } else {
            item {
                Group {
                    messages.forEachIndexed { i, m ->
                        if (i > 0) GroupDivider()
                        // Ссылку из ответа можно открыть ещё раз — она хранится вместе с сообщением.
                        val link = if (m.role == MessageRole.USER) null else messageLink(m.content)
                        RowItem(
                            title = messageText(m.content),
                            subtitle = (if (m.role == MessageRole.USER) "Вы" else name) + " · " + fmt.format(m.createdAt),
                            maxSubtitleLines = 1,
                            trailing = link?.let { url ->
                                {
                                    androidx.compose.material3.TextButton(onClick = { uri.openUri(url) }) {
                                        Text("Открыть", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
