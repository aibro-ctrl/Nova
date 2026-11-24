package ai.nova.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Токены дизайн-системы Nova.
 *
 * Смысл: экраны не должны спорить друг с другом отступами и радиусами. Всё, что повторяется,
 * живёт здесь — один шаг сетки, один радиус карточки, одна высота кнопки. Числа в экранах
 * остаются только там, где они действительно уникальны (размер сферы, ширина пузыря сообщения).
 */
object NovaSpace {
    /** 4dp — прижатые друг к другу элементы: значок и подпись, метка времени. */
    val xs: Dp = 4.dp

    /** 8dp — внутри строки списка. */
    val s: Dp = 8.dp

    /** 12dp — между связанными блоками. */
    val m: Dp = 12.dp

    /** 16dp — основной отступ экрана и содержимого карточки. */
    val l: Dp = 16.dp

    /** 24dp — между секциями. */
    val xl: Dp = 24.dp

    /** 32dp — перед крупным заголовком и в пустых состояниях. */
    val xxl: Dp = 32.dp

    /** Горизонтальный отступ содержимого экрана. */
    val screen: Dp = 16.dp

    /** Нижний отступ списка без плавающей кнопки. */
    val listBottom: Dp = 28.dp

    /** Нижний отступ списка с плавающей кнопкой: она не должна закрывать последнюю строку. */
    val listBottomFab: Dp = 104.dp
}

object NovaRadius {
    /** 10dp — крупные скругления иконок и мелких элементов управления. */
    val small: Dp = 10.dp

    /** 14dp — поля ввода и кнопки. */
    val field: Dp = 14.dp
    val button: Dp = 14.dp

    /** 16dp — карточки и группы строк. */
    val card: Dp = 16.dp

    /** 18dp — пузыри сообщений. */
    val bubble: Dp = 18.dp

    /** 24dp — нижние панели и диалоги. */
    val sheet: Dp = 24.dp

    /** Полное скругление: таблетки и чипы. */
    val pill: Dp = 999.dp
}

object NovaSize {
    /** Высота основной кнопки: 48–52dp — удобно попасть пальцем, но не громоздко. */
    val button: Dp = 50.dp

    /** Минимальная высота строки списка. */
    val row: Dp = 56.dp

    /** Поле ввода в одну строку. */
    val field: Dp = 52.dp

    /** Плитка значка в строке списка. */
    val iconTile: Dp = 36.dp

    /** Высота нижней навигации без учёта системных отступов. */
    val navBar: Dp = 60.dp
}

/** Тени почти не используем: границы и фон читаются лучше и не выглядят как «карточки из шаблона». */
object NovaElevation {
    val none: Dp = 0.dp
    val sheet: Dp = 3.dp
}

/**
 * Тёплые нейтрали Nova: бумага, чернила, тонкая линия.
 * Оранжевый — второй акцент: только там, где нужно привлечь внимание к сегодняшнему дню и к записи голоса.
 */
object NovaPalette {
    /** Тёплая бумага — фон светлой темы. */
    val Paper = Color(0xFFF7F5F0)
    val PaperCard = Color(0xFFFFFFFF)

    /** Чернила: чуть теплее чистого серого, чтобы текст не выглядел синеватым. */
    val Ink = Color(0xFF24211C)
    val InkSecondary = Color(0xFF6F6B64)
    val Rule = Color(0xFFE6E3DB)

    /** Бирюза Nova — основное действие. */
    val Teal = Color(0xFF238F83)
    val TealDeep = Color(0xFF176F67)

    /** Янтарный акцент: «сегодня», активная запись голоса. Используется редко. */
    val Amber = Color(0xFFB4690E)
    val AmberSoft = Color(0xFFF0E0C8)
}

/** Цвет янтарного акцента, согласованный с текущей темой. */
@Composable
fun amberColor(): Color =
    if (MaterialTheme.colorScheme.background.luminanceIsLight()) NovaPalette.Amber else Color(0xFFE8B778)

/** Светлая ли тема — по luminance фона. */
fun Color.luminanceIsLight(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f

/** Начертание для чисел-итогов: чуть плотнее, чтобы цифры читались как в выписке банка. */
@Composable
fun amountStyle(): androidx.compose.ui.text.TextStyle =
    MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp)
