package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.SectionLabel
import ai.nova.core.assistant.Persona
import kotlinx.coroutines.launch

/**
 * «Характер Нова»: три поведенческих профиля. Это не тембр голоса — меняется сам ответ модели:
 * длина, инициатива, юмор, обращения. Поэтому настройка живёт отдельно от «Голоса и речи».
 */
@Composable
internal fun PersonaPage(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    NovaScreen(title = "Характер Нова", subtitle = "Как разговаривает и что предлагает", onBack = onBack) {
        item(key = "hint") {
            Hint(
                "Характер меняет сами ответы: длину, интонацию, шутки и предложения помощи. Команды, навыки и данные — одни и те же. " +
                    "Сменить можно и голосом: «будь деловой», «будь шутливой», «будь заботливой».",
                Modifier.padding(bottom = 4.dp),
            )
        }
        item(key = "persona") {
            SectionLabel("Выберите характер")
            Group {
                Persona.entries.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(inset = 52.dp)
                    RadioRow(p.title, p.personality.about(), s.persona == p.wire) {
                        scope.launch { c.settings.setPersona(p.wire) }
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                "Характер сохраняется между разговорами и после перезапуска. Новые ответы сразу звучат по-новому.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/** Что именно меняется — короткой строкой под названием характера. */
private fun ai.nova.core.assistant.Personality.about(): String = when (id) {
    "business" -> "Коротко и по делу: 1 предложение, без шуток и лишних советов"
    "playful" -> "Живо и с юмором: ласковые обращения, но факты и действия точные"
    else -> "Тепло и внимательно: замечает поводы и предлагает помощь по делу"
}
