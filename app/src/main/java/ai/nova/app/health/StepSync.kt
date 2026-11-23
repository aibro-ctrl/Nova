package ai.nova.app.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import ai.nova.core.health.HabitStore
import ai.nova.core.health.Vitals
import ai.nova.core.util.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Шаги с самого телефона: системный счётчик (Sensor.TYPE_STEP_COUNTER), без сети и без сторонних библиотек.
 * Спрашиваем только «Физическая активность» и только когда человек сам включает это в «Здоровье».
 * Значение попадает в журнал привычек — оттуда его читают «Мой день», отчёт о шагах и график.
 */
class StepSync(
    private val context: Context,
    private val store: HabitStore,
    private val time: TimeSource,
) {
    /** Есть ли на телефоне счётчик шагов. */
    fun available(): Boolean = runCatching {
        sensorManager()?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }.getOrDefault(false)

    /** Android 10+ требует разрешение «Физическая активность». */
    fun needsPermission(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED

    fun ready(): Boolean = available() && !needsPermission()

    /**
     * Прочитать счётчик и записать сегодняшние шаги. Возвращает true, если запись обновилась.
     * Ошибки наружу не выходят: без данных Нова просто молчит.
     */
    suspend fun sync(): Boolean {
        if (!ready()) return false
        val total = readTotal() ?: return false
        val today = time.today()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val morning = stepsSinceMorning(total, prefs.getString(KEY_DAY, null), prefs.getFloat(KEY_BASE, -1f), today.toString())
        prefs.edit().putString(KEY_DAY, today.toString()).putFloat(KEY_BASE, morning.second).apply()
        if (morning.first <= 0) return false
        store.putDay(Vitals.STEPS, NAME, morning.first.toDouble(), today.atStartOfDay(time.zone()).toInstant(), time.now())
        return true
    }

    private fun sensorManager(): SensorManager? = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Одно показание счётчика: подписка живёт до первого события или до таймаута. */
    private suspend fun readTotal(): Float? {
        val manager = sensorManager() ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(2_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            manager.unregisterListener(this)
                            if (cont.isActive) cont.resume(event.values.firstOrNull())
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    cont.invokeOnCancellation { manager.unregisterListener(listener) }
                    if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Счётчик отдаёт «сколько прошёл с загрузки телефона», поэтому точку отсчёта запоминаем сами.
         * В первый запуск за день ею становится текущее показание, дальше считаем от него — то есть шаги
         * видны с первого открытия Нова за день (фоновая служба ради этого не нужна и батарею не ест).
         * Возвращает шаги за сегодня и точку отсчёта. Сброс счётчика (перезагрузка) виден по total < base.
         */
        fun stepsSinceMorning(total: Float, savedDay: String?, savedBase: Float, today: String): Pair<Int, Float> {
            val usable = savedDay == today && savedBase >= 0f && total >= savedBase
            if (!usable) return 0 to total
            val steps = (total - savedBase).toInt()
            return (if (steps > 0) steps else 0) to savedBase
        }

        const val NAME = "счётчик телефона"
        const val PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION
        private const val PREFS = "nova_health"
        private const val KEY_DAY = "step_day"
        private const val KEY_BASE = "step_base"
    }
}
