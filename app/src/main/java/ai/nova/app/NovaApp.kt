package ai.nova.app

import android.app.Application
import android.util.Log
import ai.nova.app.reminders.Notifications
import ai.nova.core.util.LogSink
import ai.nova.core.util.Logger
import kotlinx.coroutines.launch

class NovaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Сбой пишется в файл на телефоне; при следующем запуске Нова предложит отправить отчёт.
        ai.nova.app.diagnostics.Diagnostics.install(this)
        // Логи проходят через Redactor (маскировка ключей и токенов); в release — только предупреждения и ошибки.
        Logger.minLevel = if (BuildConfig.DEBUG) Logger.Level.DEBUG else Logger.Level.WARN
        Logger.sink = object : LogSink {
            override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
                val t = "Nova/$tag"
                when (level) {
                    Logger.Level.DEBUG -> Log.d(t, message, error)
                    Logger.Level.INFO -> Log.i(t, message, error)
                    Logger.Level.WARN -> Log.w(t, message, error)
                    Logger.Level.ERROR -> Log.e(t, message, error)
                }
            }
        }
        Notifications.createChannels(this)
        container = AppContainer(this)
        // Миниатюры товаров в чате качаются тем же клиентом, что и остальные запросы Нова.
        ai.nova.app.ui.components.ProductImages.init(container.http)
        // Траты, которые не дошли до Финансового помощника в прошлый раз.
        container.appScope.launch { container.finance.flush() }
    }
}
