package ai.nova.app.reminders

import android.content.Context

/**
 * Чьи будильники и таймеры использовать: свои (уведомление и сигнал внутри Нова)
 * или телефона (штатное приложение «Часы» — тогда они видны в общем списке будильников).
 */
object Clocks {
    const val APP = "app"
    const val SYSTEM = "system"

    private const val PREFS = "nova_clocks"
    private const val MODE = "mode"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, APP) ?: APP

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, mode).apply()
    }

    fun useSystem(context: Context): Boolean = mode(context) == SYSTEM
}
