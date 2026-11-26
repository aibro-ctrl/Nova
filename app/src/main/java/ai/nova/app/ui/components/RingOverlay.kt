package ai.nova.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.nova.app.AppContainer
import ai.nova.app.media.RingService
import ai.nova.core.assistant.RuFormat
import kotlinx.coroutines.delay

/**
 * Пока звенит будильник или таймер, поверх экрана висит карточка со «Стоп».
 * Уведомление не всегда заметно, а голосом глушат не все — кнопка должна быть на виду.
 */
@Composable
fun RingOverlay(c: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ringing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Сигнал") }
    var alarmId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = RingService.isRinging()
            if (now != ringing) ringing = now
            if (now) {
                val alarm = c.alarms.ringing()
                title = if (alarm != null) {
                    val label = alarm.label.trim()
                    "Будильник ${RuFormat.time(alarm.time)}" + if (label.isNotEmpty()) " · $label" else ""
                } else "Звенит сигнал"
                alarmId = alarm?.id
            }
            delay(if (now) 1000 else 2000)
        }
    }
    if (!ringing) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Стоп", {
                        RingService.stop(context)
                        c.alarms.clearRinging()
                        ringing = false
                    })
                    if (alarmId != null) {
                        SecondaryButton("Ещё 5 минут", {
                            c.alarms.snooze(5)
                            RingService.stop(context)
                            ringing = false
                        })
                    }
                }
            }
        }
    }
}
