package ai.nova.app.ui

import android.content.Context
import ai.nova.app.BuildConfig

/** Одна версия в списке «Что нового». */
data class Release(val version: String, val title: String, val changes: List<String>)

/**
 * Что нового по версиям — показывается в «Настройки → О приложении» и один раз после обновления.
 * Новую версию дописываем сверху; версия должна совпадать с APP_VERSION в build.gradle.kts.
 */
object Changelog {
    val releases: List<Release> = listOf(
        Release(
            "2.19.0",
            "Офлайн-модель в одно нажатие",
            listOf(
                "Новая локальная модель Qwen 3.5: скачивается и заменяет старую одной кнопкой — выбирать репозиторий больше не нужно.",
                "Нова перестала «зависать» на сложных вопросах: ответ приходит или честное сообщение, что не успела.",
                "Понятный выбор модели: лёгкая, сбалансированная, умная — с размером и подсказкой, какая подходит вашему телефону.",
                "«Своя модель» с мини-инструкцией: где взять файл .gguf и что вписать в поле.",
                "Кнопка «Что нового» — здесь, чтобы не искать список изменений.",
            ),
        ),
        Release(
            "2.18.3",
            "Виджеты и подсказки",
            listOf(
                "Виджет на рабочем столе в актуальном дизайне: орб «дышит» волной, пока Нова слушает.",
                "Подсказки на главном экране меняются и берутся из «Что умеет Нова».",
                "Планы можно сразу отправить в календарь телефона.",
                "Новая команда «Где я?» — Нова называет адрес по геолокации.",
            ),
        ),
    )

    /** Самая свежая версия — её показываем в «Что нового» по умолчанию. */
    val latest: Release get() = releases.first()

    /** Что появилось после версии [previous] (null — показываем только свежую). */
    fun since(previous: String?): List<Release> {
        if (previous.isNullOrBlank()) return releases.take(1)
        val i = releases.indexOfFirst { it.version == previous }
        return if (i <= 0) releases.take(1) else releases.take(i)
    }
}

/**
 * Помнит, для какой версии «Что нового» уже показали: после обновления окно открывается само,
 * второй раз не мешает.
 */
class WhatsNew(context: Context) {
    private val prefs = context.getSharedPreferences("nova_whats_new", Context.MODE_PRIVATE)
    private val current: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    /** Есть что показать автоматически (приложение обновилось). */
    fun hasUnseen(): Boolean = prefs.getString(KEY_SEEN, null) != current && Changelog.since(prefs.getString(KEY_SEEN, null)).isNotEmpty()

    /** Что показать после обновления (или null, если показывать нечего). */
    fun unseen(): List<Release>? = if (hasUnseen()) Changelog.since(prefs.getString(KEY_SEEN, null)) else null

    /** Для какой версии «Что нового» уже смотрели (null — ни для какой). */
    fun lastSeen(): String? = prefs.getString(KEY_SEEN, null)

    fun markSeen() {
        prefs.edit().putString(KEY_SEEN, current).apply()
    }

    private companion object {
        const val KEY_SEEN = "seen_version"
    }
}
