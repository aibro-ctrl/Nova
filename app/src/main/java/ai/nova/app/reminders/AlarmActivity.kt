package ai.nova.app.reminders

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.nova.core.assistant.RuFormat
import ai.nova.app.media.RingService
import ai.nova.app.ui.theme.NovaTheme

/**
 * Критичное напоминание: загорается экран и звучит сигнал, пока не нажали «Готово».
 * Показывается только когда пользователь сам сказал «критично»/«очень важно»; разрешение
 * на полный экран (Android 14+) проверяется в [ReminderReceiver].
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(ReminderReceiver.EXTRA_ID).orEmpty()
        val text = intent?.getStringExtra(ReminderReceiver.EXTRA_TEXT).orEmpty()
        // Поверх блокировки: иначе «полный экран» не имеет смысла.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentId = id
        showing = this
        setContent {
            NovaTheme {
                AlarmScreen(text = text, onDone = { done(id) }, onSnooze = { snooze(id) })
            }
        }
    }

    private fun done(id: String) {
        send(id, ReminderReceiver.ACTION_DONE)
        finish()
    }

    private fun snooze(id: String) {
        send(id, ReminderReceiver.ACTION_SNOOZE)
        finish()
    }

    private fun send(id: String, action: String) {
        runCatching {
            sendBroadcast(
                Intent(this, ReminderReceiver::class.java).setAction(action).setData(Uri.parse("nova://reminder/$id/$action"))
                    .putExtra(ReminderReceiver.EXTRA_ID, id),
            )
        }
        stopService(Intent(this, RingService::class.java))
        currentId = null
        showing = null
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        if (currentId == intent?.getStringExtra(ReminderReceiver.EXTRA_ID)) currentId = null
        super.onDestroy()
    }

    companion object {
        /** Какое критичное напоминание сейчас на экране: «Готово» из шторки закрывает и его. */
        @Volatile private var currentId: String? = null
        @Volatile private var showing: AlarmActivity? = null

        /** «Готово» или «Отложить» в шторке закрывают и полноэкранный будильник — иначе он висел бы с устаревшим текстом. */
        fun finishIfShowing(id: String) {
            if (currentId != id) return
            currentId = null
            val activity = showing
            showing = null
            runCatching { activity?.finish() }
        }

        fun intent(context: Context, id: String, text: String): Intent =
            Intent(context, AlarmActivity::class.java).putExtra(ReminderReceiver.EXTRA_ID, id)
                .putExtra(ReminderReceiver.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
private fun AlarmScreen(text: String, onDone: () -> Unit, onSnooze: () -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Критичное напоминание", color = Color(0xFF8AB4F8), fontSize = 16.sp)
        Text(
            text.ifBlank { "Напоминание Нова" },
            color = Color.White,
            fontSize = 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 20.dp),
        )
        Text(RuFormat.time(java.time.LocalTime.now(zone)), color = Color(0xFFB0B7C0), fontSize = 14.sp)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) { Text("Готово") }
        OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Отложить на 10 минут") }
    }
}
