package ai.nova.app.assist

import android.content.Intent
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.nova.app.AppContainer
import ai.nova.app.NovaApp
import ai.nova.app.ui.MainActivity
import ai.nova.app.ui.components.AssistantOrb
import ai.nova.app.ui.components.OrbMode
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.SecondaryButton
import ai.nova.app.ui.rememberListenAction
import ai.nova.app.ui.rememberSystemSpeechDialog
import ai.nova.app.ui.theme.NovaTheme
import ai.nova.app.voice.VoiceState
import kotlinx.coroutines.delay

/**
 * Окно ассистента поверх любого приложения: открывается жестом ассистента (долгое «Домой»/питание),
 * плиткой в быстрых настройках или из системной сессии VoiceInteraction. Сразу начинает слушать,
 * отвечает голосом и само закрывается, когда разговор закончен.
 */
class AssistActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as NovaApp).container
    private var startListening by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Окно ассистента доступно и на экране блокировки: что можно делать без разблокировки, решает пользователь.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) startListening++
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            // Защита от скриншотов, записи экрана и показа в «недавних» — по настройке пользователя.
            androidx.compose.runtime.LaunchedEffect(settings.secureScreen) {
                if (settings.secureScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            NovaTheme(settings.themeMode, settings.dynamicColor, settings.accent) {
                AssistPanel(
                    container, startListening, onClose = { finish() },
                    onOpenApp = {
                        // Приложение целиком — только после разблокировки.
                        unlockThen {
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        }
                    },
                    onUnlock = {
                        unlockThen {
                            // Телефон разблокирован, но сама Нова под отпечатком — вход в приложении.
                            if (container.appLocked()) {
                                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    /** Просит систему показать экран разблокировки; после успешной разблокировки выполняет [then]. */
    private fun unlockThen(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { then(); return }
        // Android 7 не умеет показывать экран разблокировки по запросу: открываем приложение, система сама попросит разблокировать.
        if (Build.VERSION.SDK_INT < 26) { then(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startListening++
    }

    override fun onStop() {
        super.onStop()
        // Окно закрыто или ушло в фон — микрофон не должен оставаться включённым.
        if (!isChangingConfigurations) container.voice.stop()
    }

    companion object {
        const val ACTION_ASSIST_SESSION = "ai.nova.action.ASSIST_SESSION"
    }
}

@Composable
private fun AssistPanel(c: AppContainer, startRequest: Int, onClose: () -> Unit, onOpenApp: () -> Unit, onUnlock: () -> Unit) {
    val voice by c.voice.state.collectAsStateWithLifecycle()
    val reply by c.voice.lastReply.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val listen = rememberListenAction(c)
    rememberSystemSpeechDialog(c)
    ai.nova.app.ui.PermissionRequests(c)
    var answered by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val initialReply = remember { reply }

    val locked = remember(voice) { c.isLocked() }
    val blocked = locked && !settings.lockScreenEnabled
    LaunchedEffect(startRequest) {
        // Пользователь запретил Нова на заблокированном экране — сначала разблокировка.
        if (startRequest > 0 && !blocked) { answered = false; listen() }
    }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(reply) { if (reply != null && reply !== initialReply) answered = true }
    // Разговор закончен — окно закрывается само через несколько секунд.
    LaunchedEffect(voice, answered) {
        if (voice == VoiceState.Idle && answered && reply?.awaitingConfirmation != true) {
            delay(4_000)
            onClose()
        }
    }

    val (mode, status) = when (val v = voice) {
        VoiceState.Idle -> OrbMode.IDLE to if (answered) "" else "Нажмите, чтобы говорить"
        is VoiceState.Listening -> OrbMode.LISTENING to (v.hint ?: "Слушаю…")
        is VoiceState.Thinking -> OrbMode.THINKING to "Думаю…"
        is VoiceState.Speaking -> OrbMode.SPEAKING to ""
        is VoiceState.Error -> OrbMode.ERROR to v.message
    }
    val active = mode == OrbMode.LISTENING || mode == OrbMode.THINKING || mode == OrbMode.SPEAKING
    val heard = when (val v = voice) {
        is VoiceState.Listening -> v.partial
        is VoiceState.Thinking -> v.heard
        else -> ""
    }

    // Компактное окно как у Gemini: небольшая карточка поверх экрана, а не огромная панель на весь низ.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(tween(240)) { -it / 3 } + fadeIn(tween(180))) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.widthIn(max = 460.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Орб — кнопка «слушать/стоп», вокруг него мягкие кольца как на главной.
                            Box(
                                Modifier.size(60.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        if (active) c.voice.stop() else listen()
                                    },
                                contentAlignment = Alignment.Center,
                            ) { AssistantOrb(mode, (voice as? VoiceState.Listening)?.level ?: 0f, size = 60.dp) }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(settings.assistantName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                                val sub = when {
                                    blocked -> "Недоступно на блокировке"
                                    status.isNotBlank() -> status
                                    answered -> "Готово"
                                    else -> "Нажмите, чтобы говорить"
                                }
                                Text(
                                    sub, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    color = if (mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = onOpenApp, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.OpenInFull, contentDescription = "Открыть приложение", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Закрыть", modifier = Modifier.size(20.dp))
                            }
                        }

                        if (heard.isNotBlank()) {
                            Text(
                                heard, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                            )
                        }
                        val r = reply
                        if (answered && r != null && heard.isBlank()) {
                            Text(
                                r.text, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 4.dp, top = 8.dp),
                            )
                            if ((locked || c.appLocked()) && r.text.contains("Разблокируйте")) {
                                PrimaryButton("Разблокировать", onUnlock, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                            }
                            if (r.awaitingConfirmation) {
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryButton("Да", { c.voice.confirm(true) }, modifier = Modifier.weight(1f))
                                    SecondaryButton("Отмена", { c.voice.confirm(false) }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (locked && !blocked) {
                            Text(
                                "🔒 Заблокировано — личные данные скрыты",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                            )
                        }
                        if (blocked) {
                            PrimaryButton("Разблокировать", onUnlock, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                    }
                }
            }
        }
    }
}
