package ai.nova.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.nova.app.ui.theme.LocalOrbColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Organic Voice Orb — главный символ Нова.
 *
 * Мягкая асимметричная форма из нескольких полупрозрачных слоёв, тонкий внешний контур
 * и короткая звуковая волна внутри. Форма «дышит» даже в покое, при прослушивании
 * подстраивается под громкость голоса, при обработке запроса по контуру бежит волна.
 */
@Composable
fun AssistantOrb(mode: OrbMode, level: Float, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val accent = LocalOrbColor.current ?: MaterialTheme.colorScheme.primary
    val core by animateColorAsState(
        if (mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else accent,
        tween(360), label = "orb-core",
    )
    val layer by animateColorAsState(
        if (mode == OrbMode.ERROR) MaterialTheme.colorScheme.error else accent,
        tween(360), label = "orb-layer",
    )
    // Разные состояния дают разный темп: обработка — живее, покой — почти незаметно.
    val period = when (mode) {
        OrbMode.THINKING -> 3000
        OrbMode.LISTENING -> 5200
        OrbMode.SPEAKING -> 4200
        else -> 9000
    }
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "orb-phase",
    )
    val breath by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Reverse), label = "orb-breath",
    )
    // Громкость голоса сглаживаем: волна должна двигаться плавно, а не дёргаться.
    val voice by animateFloatAsState(
        if (mode == OrbMode.LISTENING) level.coerceIn(0f, 1f) else 0f,
        spring(stiffness = 300f), label = "orb-voice",
    )

    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val compact = this.size.minDimension < 72.dp.toPx()

        // Насколько «живая» форма: в покое почти круглая, при прослушивании и обработке заметнее.
        val wobble = when (mode) {
            OrbMode.IDLE -> 0.020f + 0.010f * breath
            OrbMode.LISTENING -> 0.035f + 0.030f * voice
            OrbMode.THINKING -> 0.055f
            OrbMode.SPEAKING -> 0.040f
            OrbMode.ERROR -> 0.015f
        }
        val pulse = 1f + when (mode) {
            OrbMode.LISTENING -> 0.020f + 0.035f * voice
            OrbMode.SPEAKING -> 0.012f * breath
            else -> 0.008f * breath
        }
        val r = radius * 0.94f * pulse

        // Тонкий внешний контур: держит форму и не даёт слоям «расплываться».
        drawPath(
            organic(cx, cy, r, phase, wobble * 1.4f, seed = 1.7f),
            core.copy(alpha = 0.32f),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        // Полупрозрачные органические слои.
        drawPath(organic(cx, cy, r * 0.88f, phase, wobble * 1.1f, seed = 3.1f), layer.copy(alpha = 0.08f))
        drawPath(organic(cx, cy, r * 0.72f, phase, wobble * 0.9f, seed = 5.3f), layer.copy(alpha = 0.16f))
        // Ядро — насыщенный бирюзовый.
        drawPath(organic(cx, cy, r * 0.56f, phase, wobble * 0.6f, seed = 7.9f), core)

        // Обработка: по контуру бежит светлая дуга — «Нова думает».
        if (mode == OrbMode.THINKING && !compact) {
            val angle = phase * 360f
            drawArc(
                color = Color.White.copy(alpha = 0.55f),
                startAngle = angle,
                sweepAngle = 46f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 1.6.dp.toPx()),
            )
        }

        // Звуковая волна внутри ядра.
        drawWave(coreRadius = r * 0.56f, cx = cx, cy = cy, mode = mode, phase = phase, voice = voice, compact = compact)
    }
}

/** Волна из пяти скруглённых штрихов: читается как голос, но остаётся простой иконкой. */
private fun DrawScope.drawWave(coreRadius: Float, cx: Float, cy: Float, mode: OrbMode, phase: Float, voice: Float, compact: Boolean) {
    val bars = if (compact) 3 else 5
    val unit = coreRadius * (if (compact) 0.20f else 0.15f)
    val barWidth = unit * 0.62f
    val gap = barWidth * 0.75f
    val totalWidth = bars * barWidth + (bars - 1) * gap
    val maxHeight = coreRadius * 1.05f
    val energy = when (mode) {
        OrbMode.IDLE -> 0.42f + 0.06f * sin(phase * 2f * PI).toFloat()
        OrbMode.LISTENING -> 0.42f + 0.58f * voice
        OrbMode.THINKING -> 0.50f
        OrbMode.SPEAKING -> 0.62f
        OrbMode.ERROR -> 0f
    }
    val color = Color.White.copy(alpha = if (mode == OrbMode.ERROR) 0.92f else 0.95f)
    if (mode == OrbMode.ERROR) {
        // Ошибка: ровная короткая линия вместо волны — спокойно и однозначно.
        val h = 2.dp.toPx().coerceAtLeast(1f)
        drawRoundRect(
            color, topLeft = Offset(cx - totalWidth / 2f, cy - h / 2f),
            size = Size(totalWidth, h), cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        return
    }
    for (i in 0 until bars) {
        val t = i.toFloat() / (bars - 1).coerceAtLeast(1)
        // Центральные штрихи выше — простая и понятная «звуковая волна».
        val shape = 0.45f + 0.55f * sin(PI * t).toFloat()
        val ripple = when (mode) {
            OrbMode.LISTENING, OrbMode.SPEAKING ->
                0.72f + 0.28f * abs(sin((phase * 2f + i * 0.22f) * 2f * PI)).toFloat()
            OrbMode.THINKING -> 0.55f + 0.45f * abs(sin((phase * 3f + i * 0.15f) * 2f * PI)).toFloat()
            else -> 1f
        }
        val h = (maxHeight * energy * shape * ripple).coerceAtLeast(barWidth * 0.9f)
        val left = cx - totalWidth / 2f + i * (barWidth + gap)
        drawRoundRect(
            color,
            topLeft = Offset(left, cy - h / 2f),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}

/** Органическая замкнутая форма: сумма трёх волн разной частоты даёт мягкий «камешек», а не круг. */
private fun organic(cx: Float, cy: Float, radius: Float, phase: Float, wobble: Float, seed: Float): Path {
    val path = Path()
    val steps = 96
    for (i in 0..steps) {
        val a = i.toFloat() / steps * 2f * PI.toFloat()
        val k = 1f + wobble * (
            0.55f * sin(3f * a + phase * 2f * PI.toFloat() + seed) +
                0.30f * sin(5f * a - phase * 2f * PI.toFloat() * 0.7f + seed * 1.7f) +
                0.15f * sin(2f * a + phase * 2f * PI.toFloat() * 0.4f + seed * 2.3f)
            )
        val x = cx + radius * k * cos(a)
        val y = cy + radius * k * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
