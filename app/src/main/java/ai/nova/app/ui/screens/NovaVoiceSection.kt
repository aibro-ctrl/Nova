package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.voice.NovaVoiceModels
import ai.nova.core.nlp.Gender
import kotlinx.coroutines.launch

/**
 * «Кто говорит»: встроенный «Голос Нова» (одинаково по-русски на любом телефоне)
 * или синтезатор телефона. Встроенные голоса скачиваются один раз (~65 МБ каждый).
 */
@Composable
fun NovaVoiceSection(c: AppContainer) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sample = "Привет! Я ${s.assistantName}. Так звучит мой голос."

    SectionLabel("Кто говорит")
    Group {
        val modes = listOf(
            Triple("auto", "Автоматически", "Голос Нова, если скачан; иначе — синтезатор телефона, если он точно говорит по-русски"),
            Triple("nova", "Голос Нова", "Встроенный: одинаково по-русски на любом телефоне, без интернета"),
            Triple("system", "Синтезатор телефона", "Google, Samsung и др. На некоторых телефонах читает русский неправильно"),
        )
        modes.forEachIndexed { i, (id, title, sub) ->
            if (i > 0) GroupDivider(inset = 52.dp)
            RadioRow(title, sub, s.voiceMode == id) { scope.launch { c.settings.setVoiceMode(id) } }
        }
    }

    // Род имени ассистента решает, какие голоса предлагать: мужское имя — мужские голоса, женское — женские.
    val gender = Gender.ofAssistant(s.assistantName)
    val voices = c.novaVoiceModels.forGender(gender)
    SectionLabel("Голос Нова")
    Group {
        voices.forEachIndexed { i, v ->
            if (i > 0) GroupDivider(inset = 16.dp)
            val state by c.novaVoiceModels.state(v.id).collectAsStateWithLifecycle()
            val selected = c.novaVoiceModels.effectiveFor(s.novaVoice, gender) == v.id
            val status = when (val st = state) {
                NovaVoiceModels.State.Ready -> if (selected) "Выбран · работает без интернета" else "Скачан"
                NovaVoiceModels.State.Missing -> "скачать ${v.bytes / 1_000_000} МБ"
                is NovaVoiceModels.State.Downloading -> "Скачиваю… ${(st.progress * 100).toInt()}% · можно закрыть, продолжится с этого места"
                NovaVoiceModels.State.Installing -> "Устанавливаю…"
                is NovaVoiceModels.State.Failed -> "Не скачался: ${st.message}. Нажмите, чтобы повторить"
            }
            RowItem(
                title = v.title + if (selected) " ✓" else "",
                subtitle = "${v.subtitle} · $status",
                onClick = {
                    scope.launch {
                        c.settings.setNovaVoice(v.id)
                        if (s.voiceMode == "system") c.settings.setVoiceMode("auto")
                        if (c.novaVoiceModels.download(v.id)) c.novaVoice.speak(sample, voice = v.id)
                    }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (state) {
                            is NovaVoiceModels.State.Downloading, NovaVoiceModels.State.Installing ->
                                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
                            NovaVoiceModels.State.Ready -> Row {
                                TextButton(onClick = { scope.launch { c.novaVoice.speak(sample, voice = v.id) } }) { Text("Слушать") }
                                if (!selected) TextButton(onClick = {
                                    scope.launch {
                                        c.novaVoiceModels.delete(v.id)
                                        c.settings.setNovaVoice(c.novaVoiceModels.effective(s.novaVoice))
                                    }
                                }) { Text("Удалить") }
                            }
                            else -> TextButton(onClick = {
                                scope.launch {
                                    c.settings.setNovaVoice(v.id)
                                    if (c.novaVoiceModels.download(v.id)) c.novaVoice.speak(sample, voice = v.id)
                                }
                            }) { Text("Скачать") }
                        }
                    }
                },
            )
        }
    }
    Hint(
        "Голоса подбираются под имя ассистента: имя «Нова» или любое женское — женские голоса, мужское имя — мужские. " +
            "Чтобы список изменился, поменяйте имя выше.",
    )
    // Голос другого рода может звучать, пока подходящий не скачан — об этом честно говорим.
    val actual = c.novaVoiceModels.voice(c.novaVoiceModels.effectiveFor(s.novaVoice, gender))
    if (c.novaVoiceModels.isReady(actual.id) && !c.novaVoiceModels.matches(actual.id, gender)) {
        Hint(
            "Сейчас звучит «${actual.title}» — ${actual.gender.label} голос при имени «${s.assistantName}». " +
                "Скачайте голос нужного рода выше, и он заменит его сам.",
        )
    }
    Hint(
        "Голоса скачиваются один раз из релизов Нова на GitHub (оттуда же приходят обновления) и проверяются по контрольной сумме. " +
            "Лицензии: Денис и Дмитрий — CC0, Вера, Соня и Ася — Apache-2.0 (свободно), Ирина — датасет RHVoice, Руслан — только некоммерческое использование. " +
            "После скачивания голос работает без интернета: текст ответов никуда не отправляется.",
    )
    Text(
        "Стили звучания и скорость ниже работают и для голоса Нова.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}
