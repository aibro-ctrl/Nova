package ai.nova.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.nova.app.BuildConfig
import ai.nova.app.ui.Release
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * «Что нового»: список изменений по версиям. Открывается сам после обновления приложения
 * и вручную из «О приложении».
 *
 * Пункты показываются обычным текстом во всю ширину и переносятся на сколько нужно строк:
 * изменения нужно уметь дочитать, а не угадывать по «…».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(releases: List<Release>, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            item(key = "head") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Что нового", style = MaterialTheme.typography.titleLarge)
                        Text(
                            buildString {
                                append(BuildConfig.VERSION_NAME.removeSuffix("-debug"))
                                append(" · установлено на этом телефоне")
                                if (releases.size > 1) append(" · ниже история прошлых обновлений")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            releases.forEach { release ->
                item(key = "title-" + release.version) { ReleaseHeader(release) }
                release.sections.forEach { section ->
                    item(key = release.version + "|h|" + section.title) { SectionHeader(section.title) }
                    items(section.items, key = { release.version + "|" + it }) { change -> ChangeRow(change) }
                }
            }
            item(key = "ok") {
                PrimaryButton("Понятно", onDismiss, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp))
            }
        }
    }
}

/** Заголовок версии: плашка с номером и название релиза. */
@Composable
private fun ReleaseHeader(release: Release) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
            Text(
                release.version,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Text(
            release.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Заголовок раздела внутри версии: «Маршруты», «Здоровье» — чтобы список читался по темам. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** Один пункт изменений: маркер и текст без ограничения строк. */
@Composable
private fun ChangeRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Показывает «Что нового» один раз после обновления приложения — по сравнению с прошлым запуском.
 * Если версия не менялась, ничего не открывает.
 */
@Composable
fun WhatsNewAutoShow() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val notes = androidx.compose.runtime.remember { ai.nova.app.ui.WhatsNew(context) }
    val releases = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Release>?>(null) }
    // Сначала знакомство, потом новости: две подсказки разом — это уже навязчиво.
    val onboarded by ai.nova.app.ui.onboarding.FirstRun.done.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(onboarded) { if (onboarded) releases.value = notes.unseen() }
    val shown = releases.value
    if (shown != null && shown.isNotEmpty()) {
        WhatsNewSheet(shown) {
            notes.markSeen()
            releases.value = null
        }
    }
}
