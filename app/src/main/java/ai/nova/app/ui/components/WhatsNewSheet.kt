package ai.nova.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.nova.app.BuildConfig
import ai.nova.app.ui.Release

/**
 * «Что нового»: список изменений по версиям. Открывается сам после обновления приложения
 * и вручную из «О приложении».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(releases: List<Release>, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    Text("Что нового", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${BuildConfig.VERSION_NAME.removeSuffix("-debug")} · установлено на этом телефоне",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            releases.forEach { release ->
                item(key = "v" + release.version) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        SectionLabel("Версия ${release.version} — ${release.title}")
                    }
                }
                item(key = "list" + release.version) {
                    Group {
                        release.changes.forEachIndexed { i, change ->
                            if (i > 0) GroupDivider()
                            RowItem(
                                title = change,
                                leading = { IconTile(Icons.Rounded.Check) },
                                maxSubtitleLines = 3,
                            )
                        }
                    }
                }
            }
            item(key = "ok") {
                PrimaryButton("Понятно", onDismiss, modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp))
            }
        }
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
    androidx.compose.runtime.LaunchedEffect(Unit) { releases.value = notes.unseen() }
    val shown = releases.value
    if (shown != null && shown.isNotEmpty()) {
        WhatsNewSheet(shown) {
            notes.markSeen()
            releases.value = null
        }
    }
}
