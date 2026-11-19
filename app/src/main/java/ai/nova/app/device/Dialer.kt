package ai.nova.app.device

import android.content.Context

/**
 * Настройка «звонить сразу»: по умолчанию Нова набирает номер и нажимает вызов сама.
 * Отключают те, кто хочет видеть номер перед звонком (или у кого нет разрешения «Звонки»).
 */
object Dialer {
    private const val PREFS = "nova_dialer"
    private const val AUTO = "auto_dial"

    fun autoDial(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO, true)

    fun setAutoDial(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO, on).apply()
    }
}
