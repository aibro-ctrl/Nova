package ai.nova.app.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ai.nova.app.settings.AccentColor

/**
 * Иконка приложения в цвете «Нова»: у каждого цвета свой activity-alias с иконкой, активен ровно один.
 *
 * Гасить алиас, через который приложение запущено прямо сейчас, нельзя: система завершает активити,
 * чей компонент выключили, и приложение закрывается само. Поэтому такой алиас откладывается до фона,
 * а все остальные гасятся сразу — иначе каждый новый цвет добавлял в лоток ещё одну иконку.
 */
object AppIcons {

    /** Компонент-алиас для цвета: `.launcher.IconTeal`, `.launcher.IconGreen` и так далее. */
    fun aliasOf(accent: AccentColor): String = "ai.nova.app.launcher.Icon" + accent.name.lowercase().replaceFirstChar { it.uppercase() }

    /** Все ярлыки-иконки, которые бывают у приложения. */
    private val ALL: List<String> = AccentColor.entries.map { aliasOf(it) }

    /**
     * Включает иконку нужного цвета и сразу гасит все остальные ярлыки, кроме [keep] — того,
     * через который приложение открыто сейчас. Его нужно погасить, когда приложение уйдёт в фон:
     * вернём его списком, а [disable] вызовем в `onStop`.
     */
    fun apply(context: Context, accent: AccentColor, keep: ComponentName? = null): List<ComponentName> {
        val pm = context.packageManager
        val chosen = aliasOf(accent)
        runCatching {
            // Сначала включаем нужный: иконка в лотке не пропадает ни на миг. Повторное включение безвредно.
            pm.setComponentEnabledSetting(ComponentName(context, chosen), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        val keeping = keep?.takeIf { it.packageName == context.packageName }?.className?.takeIf { it in ALL }
        // Все прочие ярлыки гасим не спрашивая, включены ли они: состояние компонента врёт (DEFAULT),
        // а выключение уже выключенного ярлыка ничего не делает.
        disable(context, aliasesToDisable(accent, listOfNotNull(keeping)).map { ComponentName(context, it) })
        return listOfNotNull(keeping).filter { it != chosen }.map { ComponentName(context, it) }
    }

    /** Какие ярлыки нужно погасить: все, кроме выбранного цвета и того, через который приложение открыто. */
    internal fun aliasesToDisable(accent: AccentColor, keep: Collection<String> = emptyList()): List<String> =
        ALL.filter { it != aliasOf(accent) && it !in keep }

    /** Погасить ярлыки: сразу — лишние, из `onStop` — тот, через который приложение было открыто. */
    fun disable(context: Context, aliases: List<ComponentName>) {
        aliases.forEach { alias ->
            runCatching {
                context.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
