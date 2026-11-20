package ai.nova.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Фон приложения: органические формы — крупные волны, полупрозрачные мятные пятна и тонкие контурные линии.
 *
 * Ничего не перехватывает касания (обычный Canvas), не анимируется и рисуется один раз при изменении
 * размера — на батарею и плавность не влияет. Оттенки берутся из темы, поэтому фон меняется вместе
 * с «Цветом Нова» и с цветами обоев; базовый тёплый молочный тон остаётся узнаваемым.
 */
@Composable
fun OrganicBackground(
    modifier: Modifier = Modifier,
    /** Насколько формы заметны: на рабочих экранах тише, на главной — выразительнее. */
    intensity: Float = 1f,
    /** Сдвиг композиции: у разных экранов фон не выглядит копией. */
    variant: Int = 0,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val palette = remember(scheme.primary, scheme.background, dark, intensity) {
        organicPalette(scheme.primary, scheme.background, dark, intensity)
    }
    Canvas(modifier.fillMaxSize()) { drawOrganic(palette, dark, variant) }
}

/**
 * Цвет панелей поверх фона (нижняя панель, навигация, карточки): тёплый молочный в тон волнам вместо чистого
 * белого — экран не распадается на «белое снизу, цветное сверху». Для списков берите [alpha] = 1f:
 * прозрачные карточки на каждой строке замедляют прокрутку.
 */
@Composable
fun organicPanel(alpha: Float = 0.92f): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.35f
    val palette = remember(scheme.primary, scheme.background, dark) {
        organicPalette(scheme.primary, scheme.background, dark, 1f)
    }
    // В светлой теме панель светлее фона, но не чисто белая — тёплый молочный оттенок с мятным уклоном.
    val tone = if (dark) palette.base else lerp(palette.base, palette.mist, 0.45f)
    return tone.copy(alpha = if (dark) max(alpha, 0.94f) else alpha)
}

/** Оттенки фоновых форм: базовые мятно-шалфейные тона, подкрашенные выбранным цветом. */
data class OrganicPalette(
    val base: Color,
    val mint: Color,
    val sage: Color,
    val deepSage: Color,
    val mist: Color,
    val line: Color,
)

private fun organicPalette(primary: Color, background: Color, dark: Boolean, intensity: Float): OrganicPalette {
    // База фона: тёплый молочный в светлой теме, глубокий зелёно-серый в тёмной.
    val base = if (dark) background else lerp(Color(0xFFF7F5F0), background, 0.35f)
    return if (dark) {
        OrganicPalette(
            base = base,
            mint = lerp(base, primary, 0.16f * intensity),
            sage = lerp(base, primary, 0.22f * intensity),
            deepSage = lerp(base, primary, 0.28f * intensity),
            mist = lerp(base, primary, 0.12f * intensity),
            line = lerp(base, primary, 0.34f * intensity),
        )
    } else {
        OrganicPalette(
            base = base,
            mint = lerp(Color(0xFFDDEBE3), primary, 0.10f * intensity),
            sage = lerp(Color(0xFFC9DDD3), primary, 0.12f * intensity),
            deepSage = lerp(Color(0xFFB8D3C8), primary, 0.14f * intensity),
            mist = lerp(Color(0xFFE8EDE5), primary, 0.08f * intensity),
            line = lerp(Color(0xFFC9DDD3), primary, 0.26f * intensity),
        )
    }
}

private fun DrawScope.drawOrganic(p: OrganicPalette, dark: Boolean, variant: Int) {
    val w = size.width
    val h = size.height
    // Радиусы считаем от меньшей стороны: на планшете пятна не расползаются на пол-экрана.
    val unit = min(w, h)
    val flip = variant % 2 == 1

    drawRect(p.base)

    // 1. Верхний угол — крупное полупрозрачное мятное пятно с мягкими границами.
    softBlob(
        center = Offset(if (flip) w * 0.08f else w * 0.92f, -h * 0.04f),
        radius = unit * 0.72f,
        color = p.mint,
        // Ярче у края экрана и растворяется к центру — так пятно не давит на контент.
        alpha = if (dark) 0.55f else 0.75f,
    )

    // 2. Левая часть — мягкая органическая волна, уходящая вниз.
    wavePath(w, h, flip)
        .let { drawPath(it, Brush.verticalGradient(listOf(p.sage.copy(alpha = if (dark) 0.35f else 0.55f), p.mist.copy(alpha = 0f)))) }

    // 3. Слои вокруг центрального элемента ассистента — два-три полупрозрачных ореола.
    val cx = w * if (flip) 0.62f else 0.46f
    val cy = h * 0.42f
    softBlob(Offset(cx, cy), unit * 0.52f, p.mist, alpha = if (dark) 0.30f else 0.45f)
    softBlob(Offset(cx + unit * 0.06f, cy + unit * 0.02f), unit * 0.34f, p.sage, alpha = if (dark) 0.28f else 0.38f)

    // 4. Нижняя часть — крупная форма цвета шалфея.
    val bottom = Path().apply {
        moveTo(0f, h)
        lineTo(w, h)
        lineTo(w, h * if (flip) 0.78f else 0.84f)
        cubicTo(
            w * 0.72f, h * (if (flip) 0.70f else 0.76f),
            w * 0.42f, h * (if (flip) 0.92f else 0.94f),
            0f, h * if (flip) 0.82f else 0.88f,
        )
        close()
    }
    drawPath(bottom, p.deepSage.copy(alpha = if (dark) 0.45f else 0.55f))

    // 5. Тонкие контурные линии — неполные дуги: поддерживают композицию, но не спорят с текстом.
    val stroke = Stroke(width = max(1f, 1.dp.toPx()), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawArc(
        color = p.line.copy(alpha = if (dark) 0.30f else 0.40f),
        startAngle = if (flip) 200f else 20f,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(-w * 0.35f, h * 0.10f),
        size = Size(w * 0.95f, unit * (if (flip) 1.15f else 0.78f)),
        style = stroke,
    )
    drawArc(
        color = p.line.copy(alpha = if (dark) 0.22f else 0.32f),
        startAngle = if (flip) 30f else 210f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(w * 0.35f, h * 0.62f),
        size = Size(w * 0.9f, unit * (if (flip) 0.9f else 1.2f)),
        style = stroke,
    )
}

/** Мягкое пятно: радиальный переход в прозрачность — границы не читаются как наклейка. */
private fun DrawScope.softBlob(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.55f), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Пологая волна у левого края: плавная кривая от верха к низу. */
private fun wavePath(w: Float, h: Float, flip: Boolean): Path = Path().apply {
    val x = if (flip) w else 0f
    val dir = if (flip) -1f else 1f
    moveTo(x, 0f)
    lineTo(x, h * 0.62f)
    cubicTo(
        x + dir * w * 0.30f, h * 0.48f,
        x + dir * w * 0.34f, h * 0.20f,
        x + dir * w * 0.16f, 0f,
    )
    lineTo(x, 0f)
    close()
}
