package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.nova.app.AppContainer
import ai.nova.app.settings.BankPushMode
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.Hint
import ai.nova.app.ui.components.NovaScreen
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.app.ui.components.SwitchItem
import kotlinx.coroutines.launch

/**
 * «Действия»: чем Нова открывает ссылки и что делает с пушами банков.
 *
 * Раньше эти настройки были разбросаны: карты и поиск — в «Сценариях», траты из уведомлений —
 * за колокольчиком на экране расходов (его просто не находили). Человек искал одно место,
 * где сказано «куда открывать», и не находил.
 */
@Composable
internal fun ActionsPage(c: AppContainer, onBack: () -> Unit) {
    val settings by c.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var bankPush by remember { mutableStateOf(settings.bankPushes) }

    NovaScreen(title = "Действия", onBack = onBack) {
        item(key = "link-targets") {
            Hint(
                "Куда Нова открывает ссылки. Если приложение не установлено — открою его сайт в браузере.",
                Modifier.padding(bottom = 4.dp),
            )
            SectionLabel("Поиск в интернете")
            Group {
                // Android не сообщает, какой поисковик выбран в браузере, поэтому выбор хранит Нова.
                val picked = ai.nova.core.assistant.SearchEngine.fromId(settings.searchEngine)
                ai.nova.core.assistant.SearchEngine.entries.forEachIndexed { i, engine ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    RadioRow(engine.title, "Открываю результаты поиска в браузере по умолчанию", picked == engine) {
                        scope.launch { c.settings.setSearchEngine(engine.id) }
                    }
                }
            }
        }

        item(key = "maps") {
            SectionLabel("Карты для маршрутов")
            Group {
                val installed = remember {
                    ai.nova.core.assistant.MapsApp.entries.associateWith {
                        runCatching { context.packageManager.getPackageInfo(it.pkg, 0) }.isSuccess
                    }
                }
                RadioRow(
                    "Как в телефоне",
                    "Открываю то приложение, которое выбрано системой для карт",
                    settings.mapsApp.isBlank(),
                ) { scope.launch { c.settings.setMapsApp("") } }
                ai.nova.core.assistant.MapsApp.entries.forEach { app ->
                    GroupDivider(inset = 66.dp)
                    RadioRow(
                        app.title,
                        if (installed[app] == true) "установлено на телефоне" else "не установлено — открою ${app.title} в браузере",
                        settings.mapsApp == app.id,
                    ) { scope.launch { c.settings.setMapsApp(app.id) } }
                }
            }
        }

        item(key = "music") {
            SectionLabel("Приложение для музыки")
            val picked = ai.nova.core.skills.MusicApp.entries.firstOrNull { it.name == settings.musicApp }
            Group {
                ai.nova.core.skills.MusicApp.PREFERRED.forEachIndexed { i, app ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    val installed = remember(app) { app.packages.any { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess } }
                    RadioRow(
                        app.title,
                        if (installed) "установлено на телефоне" else "не установлено — открою поиск в браузере",
                        picked == app,
                    ) { scope.launch { c.settings.setMusicApp(app.name) } }
                }
                if (picked != null) {
                    GroupDivider(inset = 66.dp)
                    RadioRow(
                        "Спрашивать каждый раз",
                        "Буду уточнять, где включить, — но не чаще одного раза за разговор",
                        false,
                    ) { scope.launch { c.settings.setMusicApp("") } }
                }
            }
            Hint(
                if (picked == null) "Пока не выбрано — спрошу в первый раз и запомню ответ здесь."
                else "Включаю музыку в «${picked.title}». Скажите «включи в Spotify» — разово сделаю иначе.",
            )
        }

        item(key = "video") {
            SectionLabel("Приложение для видео")
            val picked = ai.nova.core.assistant.VideoPlatform.fromId(settings.videoPlatform)
            Group {
                ai.nova.core.assistant.VideoPlatform.entries.forEachIndexed { i, platform ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    RadioRow(platform.title, "Здесь ищу и включаю видео", picked == platform) {
                        scope.launch { c.settings.setVideoPlatform(platform.id) }
                    }
                }
                if (picked != null) {
                    GroupDivider(inset = 66.dp)
                    RadioRow("Спрашивать каждый раз", "Буду уточнять, где искать видео", false) {
                        scope.launch { c.settings.setVideoPlatform("") }
                    }
                }
            }
            Hint(
                if (picked == null) "Пока не выбрано — спрошу в первый раз и запомню ответ здесь."
                else "Видео ищу в «${picked.title}». Скажите «ищи видео в рутубе» — разово сделаю иначе.",
            )
        }

        item(key = "shopping") {
            SectionLabel("Покупки")
            val chosen = settings.shoppingMarketplaces.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            // Пусто в настройках — включены обе площадки; так список и показываем.
            val enabled = if (chosen.isEmpty()) ai.nova.core.shopping.Marketplace.entries.map { it.id } else chosen
            var lastWarning by remember { mutableStateOf(false) }
            Group {
                ai.nova.core.shopping.Marketplace.entries.forEachIndexed { i, marketplace ->
                    if (i > 0) GroupDivider(inset = 66.dp)
                    SwitchItem(
                        marketplace.title,
                        when (marketplace) {
                            ai.nova.core.shopping.Marketplace.WB -> "Ищу товары, показываю цены и фото"
                            ai.nova.core.shopping.Marketplace.OZON -> "Ищу товары, показываю цены и фото"
                        },
                        checked = marketplace.id in enabled,
                    ) { on ->
                        // Последнюю площадку не выключаем: иначе искать будет негде.
                        if (!on && enabled.size == 1) {
                            lastWarning = true
                            return@SwitchItem
                        }
                        lastWarning = false
                        val next = if (on) enabled + marketplace.id else enabled - marketplace.id
                        scope.launch { c.settings.setShoppingMarketplaces(next.joinToString(",")) }
                    }
                }
            }
            if (lastWarning) Hint("Одну площадку оставьте включённой — иначе искать товары негде.")
            SwitchItem(
                "Помнить размеры и бренды",
                "Размер обуви, одежды, вид питомца — чтобы не спрашивать каждый раз. Хранится в «Памяти», только на телефоне.",
                checked = settings.shoppingMemory,
            ) { scope.launch { c.settings.setShoppingMemory(it) } }
            // Каталог площадки может быть закрыт для этого региона или сети — проверка показывает это сразу,
            // а не выясняется уже на просьбе «купи кеды».
            var probe by remember { mutableStateOf<String?>(null) }
            var testing by remember { mutableStateOf(false) }
            GroupDivider(inset = 16.dp)
            RowItem(
                title = if (testing) "Проверяю каталог…" else "Проверить поиск товаров",
                subtitle = probe ?: "Короткий запрос на площадке — видно, работает ли каталог",
                chevron = !testing,
            ) {
                if (testing) return@RowItem
                testing = true
                probe = null
                scope.launch {
                    val found = runCatching {
                        c.skillHost.searchProducts(ai.nova.core.shopping.ShoppingQuery(text = "кеды", marketplaces = ai.nova.core.shopping.Marketplace.entries.toSet()))
                    }.getOrNull()
                    probe = when {
                        found == null -> "Проверить не удалось"
                        found.products.isNotEmpty() -> "Каталог отвечает: товаров — ${found.products.size}"
                        found.problems.isNotEmpty() -> "Каталог не ответил: ${found.problems.first().error ?: "нет связи"}"
                        else -> "Каталог ответил, но товаров нет"
                    }
                    testing = false
                }
            }
            Hint(
                "Нова ищет товары и показывает их списком с фото, ценой и ссылкой. В корзину площадки " +
                    "приложение положить не может: выбранное сохраняется в «Списке покупок», а покупку и оплату " +
                    "делаете вы сами.",
            )
        }

        item(key = "bank-pushes") {
            SectionLabel("Расходы из уведомлений банков")
            Group {
                BankPushMode.entries.forEachIndexed { i, mode ->
                    if (i > 0) GroupDivider(inset = 16.dp)
                    RadioRow(mode.title, mode.hint, bankPush == mode) {
                        bankPush = mode
                        scope.launch { c.settings.setBankPushes(mode) }
                    }
                }
            }
            Hint(
                "Банк присылает пуш «Покупка 850 ₽ · Пятёрочка» — Нова записывает трату в расходы. " +
                    "Данные остаются на телефоне, в банк приложение не входит.",
            )
            if (bankPush != BankPushMode.OFF && !ai.nova.app.notify.NovaNotificationListener.enabled(context)) {
                Hint("Чтобы это работало, разрешите Нова доступ к уведомлениям — кнопка появится ниже.")
            }
            // Тридцать банков одной строкой не помещались и наезжали на соседние подсказки — перечисляем коротко.
            val banks = ai.nova.core.finance.BankPushParser.supportedBanks
            val more = banks.size - 4
            Hint(
                "Поддерживаются банки: ${banks.take(4).joinToString(", ")}" +
                    if (more > 0) " и ещё ${ai.nova.core.assistant.RuFormat.count(more, "банк", "банка", "банков")}." else ".",
            )
        }
    }
}
