package ai.nova.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.nova.app.R
import ai.nova.app.settings.AccentColor
import ai.nova.app.settings.ThemeMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

// ---------------------------------------------------------------------------------------------
// Quiet Utility: спокойная нейтральная основа, один бирюзовый акцент, тонкие линии вместо теней.
// ---------------------------------------------------------------------------------------------

/** Палитра Organic Tech: природные, спокойные тона. */
val QuietBackground = Color(0xFFF7F5F0)
val QuietSurface = Color(0xFFFFFFFF)
val QuietPrimary = Color(0xFF238F83)
val QuietPrimaryDark = Color(0xFF176F67)
val QuietText = Color(0xFF292D30)
val QuietTextSecondary = Color(0xFF85888A)
val QuietError = Color(0xFFC75D50)
val QuietDivider = Color(0xFFE8E6DF)

/** Мягкие природные оттенки для слоёв, плашек и диаграмм. */
val Mint = Color(0xFFDDEBE3)
val Sage = Color(0xFFB8CDC0)

/** Акцент — единственный цвет для главного действия. Остальное — нейтральные тона. */
val Accent = QuietPrimary
val AccentSoft = Color(0xFF6FC8BE)
val Coral = Color(0xFFE0A096)

/** Успех и ошибка: смысл всегда дублируется словом и значком, цвет — только подсказка. */
val Success = Color(0xFF2C7A5B)
val SuccessSoft = Color(0xFF7FCFA4)

/** Цвет подтверждённого действия (запись сохранена, ответ получен). */
@Composable
fun successColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Success else SuccessSoft

/** Цвет ошибки с причиной: используется вместе с текстом, а не вместо него. */
@Composable
fun errorColor(): Color = MaterialTheme.colorScheme.error

/** Manrope: статичные веса из свободного шрифта (лицензия — assets/manrope-OFL.txt). */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = QuietPrimary,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Color(0xFF12453F),
    secondary = Color(0xFF6B7C74),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF3EF),
    onSecondaryContainer = QuietText,
    tertiary = QuietPrimaryDark,
    onTertiary = Color.White,
    background = QuietBackground,
    onBackground = QuietText,
    surface = QuietSurface,
    onSurface = QuietText,
    surfaceVariant = Color(0xFFEFEDE7),
    onSurfaceVariant = QuietTextSecondary,
    surfaceContainerLowest = QuietSurface,
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Color(0xFFF2F0E9),
    surfaceContainerHigh = Color(0xFFEDEBE3),
    surfaceContainerHighest = Color(0xFFE4E1D8),
    outline = Color(0xFFD3D0C6),
    outlineVariant = QuietDivider,
    error = QuietError,
    onError = Color.White,
    errorContainer = Color(0xFFF6E2DE),
    onErrorContainer = Color(0xFF521F19),
    inverseSurface = QuietText,
    inverseOnSurface = QuietBackground,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5CC6BC),
    onPrimary = Color(0xFF00312C),
    primaryContainer = Color(0xFF1F554F),
    onPrimaryContainer = Color(0xFFBBE9E3),
    secondary = Color(0xFFB3B8B0),
    onSecondary = Color(0xFF1B1F1C),
    secondaryContainer = Color(0xFF2A2F2B),
    onSecondaryContainer = Color(0xFFE4E6E0),
    tertiary = Color(0xFF8FD5CD),
    onTertiary = Color(0xFF00332E),
    background = Color(0xFF141715),
    onBackground = Color(0xFFE9E9E3),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE9E9E3),
    surfaceVariant = Color(0xFF232725),
    onSurfaceVariant = Color(0xFF9DA19A),
    surfaceContainerLowest = Color(0xFF101312),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D2120),
    surfaceContainerHigh = Color(0xFF242826),
    surfaceContainerHighest = Color(0xFF2C312E),
    outline = Color(0xFF3B413D),
    outlineVariant = Color(0xFF2A2F2C),
    error = Coral,
    onError = Color(0xFF4C1510),
    errorContainer = Color(0xFF7A2A20),
    onErrorContainer = Color(0xFFF7DED8),
    inverseSurface = Color(0xFFE9E9E3),
    inverseOnSurface = Color(0xFF1B1F1C),
)

private val base = Typography()

/**
 * Иерархия: заголовки экранов 28–32 SemiBold, блоки 18–20 SemiBold, текст 15–16 Regular,
 * подписи 12–13 Medium. Жирный вес — только у заголовков.
 */
private val NovaTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Manrope, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Manrope, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    displaySmall = base.displaySmall.copy(fontFamily = Manrope, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Manrope, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Manrope, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Manrope, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontFamily = Manrope, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontFamily = Manrope, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = base.titleSmall.copy(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = base.bodyMedium.copy(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.bodySmall.copy(fontFamily = Manrope, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.labelLarge.copy(fontFamily = Manrope, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = Manrope, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
)

/** Скругления сдержанные: шаг сетки 4, радиусы кратны 2–4. */
private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun NovaTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, accent: AccentColor = AccentColor.TEAL, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> withAccent(if (dark) Dark else Light, accent, dark)
    }
    // Волна ассистента всегда в «чистом» выбранном цвете (бирюзовая Нова остаётся бирюзовой и в светлой теме).
    val orb = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || accent == AccentColor.TEAL) null else Color(accent.dark)
    androidx.compose.runtime.CompositionLocalProvider(LocalOrbColor provides orb) {
        MaterialTheme(colorScheme = scheme, typography = NovaTypography, shapes = NovaShapes, content = content)
    }
}

/** Основной цвет волны Нова; null — акцент темы. */
val LocalOrbColor = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/** Цвет, выбранный пользователем: акцент, контейнеры и второй цвет волны (tertiary). */
private fun withAccent(base: ColorScheme, accent: AccentColor, dark: Boolean): ColorScheme {
    if (accent == AccentColor.TEAL) return base
    val primary = Color(if (dark) accent.dark else accent.light)
    val glow = Color(accent.glow)
    val onPrimary = if (primary.luminance() > 0.45f) QuietText else Color.White
    val container = primary.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(base.background)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = if (dark) primary.copy(alpha = 0.95f).compositeOver(Color.White) else primary.copy(alpha = 0.9f).compositeOver(Color.Black),
        tertiary = glow,
        onTertiary = if (glow.luminance() > 0.45f) QuietText else Color.White,
    )
}
