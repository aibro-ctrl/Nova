package ai.nova.app.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ai.nova.app.settings.AccentColor

/**
 * Иконка приложения в цвете «Нова»: у каждого цвета свой activity-alias с иконкой, активен ровно один.
 *
 * Гасить алиас, через который приложение запущено прямо сейчас, нельзя: система завершает активити,
 * чей компонент выключили, и приложение закрывается само. Поэтому такой алиас откладывается до фона.
 */
object AppIcons {

    /** Компонент-алиас для цвета: `.launcher.IconTeal`, `.launcher.IconGreen` и так далее. */
    fun aliasOf(accent: AccentColor): String = "ai.nova.app.launcher.Icon" + accent.name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Включает иконку нужного цвета. Остальные активные алиасы не гасим сразу, а возвращаем списком:
     * среди них может быть тот, через который приложение запущено, — его выключение закроет приложение.
     * Погасить их нужно, когда приложение уйдёт в фон.
     */
    fun apply(context: Context, accent: AccentColor): List<ComponentName> {
        val pm = context.packageManager
        val chosen = ComponentName(context, aliasOf(accent))
        val deferred = mutableListOf<ComponentName>()
        runCatching {
            // Сначала включаем нужный, потом разбираемся с остальными: иконка в лотке не пропадает ни на миг.
            if (!pm.isActive(chosen)) {
                pm.setComponentEnabledSetting(chosen, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            val active = AccentColor.entries.map { aliasOf(it) }.filter { pm.isActive(ComponentName(context, it)) }
            deferred += aliasesToDefer(accent, active).map { ComponentName(context, it) }
        }
        return deferred
    }

    /**
     * Какие активные алиасы нужно погасить: все, кроме выбранного цвета. Гасить их в момент запуска нельзя —
     * среди них тот, через который приложение открыто: система завершит его и приложение закроется.
     */
    internal fun aliasesToDefer(accent: AccentColor, active: Collection<String>): List<String> =
        active.filter { it != aliasOf(accent) }

    /** Погасить ярлыки, оставленные при запуске: приложение уже в фоне, закрывать нечего. */
    fun disable(context: Context, aliases: List<ComponentName>) {
        aliases.forEach { alias ->
            runCatching {
                context.packageManager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }

    /**
     * Реально ли алиас запускается: `getComponentEnabledSetting` для незатронутых компонентов возвращает
     * DEFAULT, поэтому состояние проверяем разрешением intent'а — выключенный алиас не разрешается.
     */
    private fun PackageManager.isActive(component: ComponentName): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
        resolveActivity(intent, 0) != null
    }.getOrDefault(false)
}
