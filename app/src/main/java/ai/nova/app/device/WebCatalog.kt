package ai.nova.app.device

import ai.nova.core.shopping.Marketplace
import ai.nova.core.shopping.ShoppingProduct
import ai.nova.core.util.Logger
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Поиск товаров через настоящий браузерный движок телефона.
 *
 * Площадки закрывают свои каталоги для обычных HTTP-запросов (Wildberries отвечает 403, Ozon — проверкой
 * браузера), но сами страницы поиска открываются в браузере нормально. Поэтому, если прямой запрос
 * ничего не дал, Нова открывает ту же страницу в невидимом WebView, ждёт, пока появятся карточки,
 * и читает их со страницы: название, цена, оценка, ссылка и картинка.
 *
 * Ничего не выдумываем: нет карточки на странице — нет товара в списке.
 */
class WebCatalog(private val context: Context) {

    /**
     * Свой WebView на каждую площадку: создание нового на каждый запрос слишком дорого для телефона,
     * а общий не даёт искать в двух магазинах одновременно.
     */
    private val views = java.util.concurrent.ConcurrentHashMap<Marketplace, WebView>()

    /**
     * Товары со страницы поиска площадки. Пусто — страница не открылась или карточек на ней нет.
     * [limit] ограничивает чтение: больше десяти всё равно не показываем.
     */
    suspend fun search(marketplace: Marketplace, url: String, limit: Int): List<ShoppingProduct> =
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) { read(marketplace, url, limit) } ?: emptyList()

    private suspend fun read(marketplace: Marketplace, url: String, limit: Int): List<ShoppingProduct> {
        val view = withContext(Dispatchers.Main) { ensureView(marketplace) } ?: return emptyList()
        withContext(Dispatchers.Main) {
            runCatching { view.loadUrl(url) }
                .onFailure { Logger.w(TAG, "страница не открылась", it) }
        }
        // Ждём не «загрузку», а карточки: сайт дорисовывает их скриптами уже после onPageFinished.
        var waited = 0L
        var scrolled = 0
        while (waited < LOAD_TIMEOUT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            // Ленивые картинки и следующие ряды появляются после прокрутки.
            if (scrolled < 2 && waited > POLL_MS * 4) {
                scrolled++
                withContext(Dispatchers.Main) {
                    runCatching { view.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null) }
                }
            }
            val raw = withContext(Dispatchers.Main) { evaluate(view, scriptFor(marketplace, limit)) } ?: continue
            val products = parse(raw, marketplace)
            if (products.isNotEmpty()) {
                Logger.i(
                    TAG,
                    "${marketplace.title}: карточек со страницы — ${products.size}, с картинкой — ${products.count { it.imageUrl != null }}",
                )
                // Разметку показываем только когда картинок нет — по ней видно, где они лежат.
                if (products.none { it.imageUrl != null }) {
                    Logger.w(TAG, "${marketplace.title}: первая карточка — ${firstHtml(raw)}")
                }
                return products
            }
        }
        // Причина важна для разбора: страница не открылась, открылась не та или карточек на ней нет.
        val state = withContext(Dispatchers.Main) { evaluate(view, DIAGNOSTICS_SCRIPT) }
        Logger.w(TAG, "${marketplace.title}: карточек на странице не дождались — $state")
        return emptyList()
    }

    private fun ensureView(marketplace: Marketplace): WebView? {
        views[marketplace]?.let { return it }
        return try {
            @SuppressLint("SetJavaScriptEnabled")
            val created = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.userAgentString = MOBILE_UA
                webViewClient = WebViewClient()
            }
            // Окно никуда не выводим, но разметку раскладываем руками: без неё страница не отдаёт
            // текст карточек (innerText у неотрисованных элементов пустой).
            runCatching {
                created.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(PHANTOM_WIDTH, android.view.View.MeasureSpec.AT_MOST),
                    android.view.View.MeasureSpec.makeMeasureSpec(PHANTOM_HEIGHT, android.view.View.MeasureSpec.AT_MOST),
                )
                created.layout(0, 0, PHANTOM_WIDTH, PHANTOM_HEIGHT)
            }
            views[marketplace] = created
            created
        } catch (e: Exception) {
            Logger.w(TAG, "WebView недоступен", e)
            null
        }
    }

    private suspend fun evaluate(view: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        try {
            view.evaluateJavascript(js) { value -> if (cont.isActive) cont.resume(value) }
        } catch (e: Exception) {
            Logger.w(TAG, "страница не ответила", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Разбор ответа страницы: массив карточек в JSON, который собрал скрипт. */
    fun parse(raw: String?, marketplace: Marketplace): List<ShoppingProduct> {
        val text = raw?.trim()?.takeIf { it.length > 4 } ?: return emptyList()
        // evaluateJavascript отдаёт строку в кавычках с экранированием — снимаем её, если она есть.
        val json = runCatching { Json.parseToJsonElement(text).jsonPrimitive.content }.getOrElse { return emptyList() }
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<ShoppingProduct>(array.size)
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val id = item.string("id") ?: continue
            val url = item.string("url")?.takeIf { it.startsWith("http") } ?: productUrl(marketplace, id)
            val title = item.string("title")?.trim()?.takeIf { it.length > 2 } ?: continue
            out += ShoppingProduct(
                id = id,
                marketplace = marketplace,
                title = title,
                price = item.int("price"),
                oldPrice = item.int("oldPrice"),
                rating = item.double("rating"),
                reviews = item.int("reviews"),
                brand = item.string("brand"),
                imageUrl = item.string("img")?.takeIf { it.startsWith("http") },
                url = url,
                sizes = emptyList(),
                available = null,
                seller = item.string("seller"),
            )
        }
        return out
    }

    /** Первая карточка как есть — по ней видно, где на странице лежит картинка. */
    private fun firstHtml(raw: String?): String {
        val text = raw?.trim() ?: return "—"
        val json = runCatching { Json.parseToJsonElement(text).jsonPrimitive.content }.getOrElse { return "—" }
        val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return "—"
        val item = array.firstOrNull() as? JsonObject ?: return "—"
        return item.string("html")?.take(500) ?: "—"
    }

    /** Адрес карточки по номеру — на случай, если страница его не отдала. */
    private fun productUrl(marketplace: Marketplace, id: String): String = when (marketplace) {
        Marketplace.WB -> "https://www.wildberries.ru/catalog/$id/detail.aspx"
        Marketplace.OZON -> "https://www.ozon.ru/product/$id/"
    }

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(name: String): Int? =
        runCatching { this[name]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } }.getOrNull()

    private fun JsonObject.double(name: String): Double? =
        runCatching { this[name]?.jsonPrimitive?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() } }.getOrNull()?.takeIf { it > 0 }

    private fun scriptFor(marketplace: Marketplace, limit: Int): String = when (marketplace) {
        Marketplace.WB -> WB_SCRIPT.replace("LIMIT", limit.toString())
        Marketplace.OZON -> OZON_SCRIPT.replace("LIMIT", limit.toString())
    }

    private companion object {
        const val TAG = "ShoppingWeb"
        const val POLL_MS = 500L
        const val LOAD_TIMEOUT_MS = 14_000L
        const val TOTAL_TIMEOUT_MS = 18_000L
        const val PHANTOM_WIDTH = 1080
        const val PHANTOM_HEIGHT = 1920
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** Что на странице на самом деле: адрес, сколько ссылок и текста — для журнала. */
        val DIAGNOSTICS_SCRIPT = """
            (function(){
              return location.href + ' | ссылок: ' + document.querySelectorAll('a').length +
                     ' | текст: ' + ((document.body && document.body.innerText) || '').length +
                     ' | карточек: ' + document.querySelectorAll('a[href*="/catalog/"][href*="detail.aspx"]').length +
                     document.querySelectorAll('a[href*="/product/"]').length;
            })()
        """.trimIndent()

        /**
         * Чтение карточек Wildberries. Классы у сайта меняются, поэтому цепляемся за постоянное:
         * ссылку вида `/catalog/<id>/detail.aspx`, картинку и цену в тексте карточки.
         */
        val WB_SCRIPT = """
            (function(){
              var out = [], seen = {};
              // Название — самая длинная строка без цены, скидки, остатков и оценок: бейджи и служебные
              // подписи («ОСТАЛОСЬ 2 ШТ», «1 ед осталось») короче и не подходят.
              var bestLine = function(txt){
                var lines = txt.split('\n').map(function(s){return s.trim()}).filter(function(s){
                  return s.length > 10 && s.indexOf('₽') < 0 && !/отзыв|оцен|осталось|в корзину|купить|скидк|рассрочк|\u2605/i.test(s);
                });
                lines.sort(function(a, b){ return b.length - a.length });
                return lines.length ? lines[0] : '';
              };
              var imgSrc = function(img){
                if (!img) return '';
                var s = img.currentSrc || img.src || '';
                if (!s || s.indexOf('data:') === 0) {
                  s = img.getAttribute('data-src') || img.getAttribute('data-img') || '';
                }
                if (!s) {
                  var pic = img.closest ? img.closest('picture') : null;
                  var src = pic ? pic.querySelector('source[srcset]') : null;
                  if (src) s = (src.getAttribute('srcset') || '').split(' ')[0];
                }
                if (!s || s.indexOf('http') !== 0) {
                  var holder = img.closest ? img.closest('[style*="background-image"]') : null;
                  var bg = holder ? (holder.getAttribute('style') || '') : '';
                  var mm = bg.match(/url\(["']?(https?:[^"')]+)/);
                  if (mm) s = mm[1];
                }
                return s && s.indexOf('http') === 0 ? s : '';
              };
              var links = document.querySelectorAll('a[href*="/catalog/"][href*="detail.aspx"]');
              for (var i = 0; i < links.length && out.length < LIMIT; i++) {
                var a = links[i], href = a.href || '';
                var m = href.match(/catalog\/(\d+)\//);
                if (!m || seen[m[1]]) continue;
                seen[m[1]] = 1;
                var card = a.closest('article') || a.closest('div[class*="product-card"]') || a.parentElement || a;
                var img = a.querySelector('img') || card.querySelector('img');
                var txt = ((card.innerText || card.textContent || '') + '').replace(/\u00a0/g, ' ');
                var pm = txt.match(/(\d[\d\s]{0,9})\s*₽/);
                var rm = txt.match(/([0-5][.,]\d)\b/);
                var om = txt.match(/([\d\s]+)\s*оцен/);
                var title = (img && img.alt) || a.getAttribute('aria-label') || a.getAttribute('title') || '';
                if (!title) title = bestLine(txt);
                out.push({
                  id: m[1], url: href, title: title, html: out.length ? '' : card.outerHTML.slice(0, 500),
                  img: imgSrc(img),
                  price: pm ? parseInt(pm[1].replace(/\s/g, ''), 10) : null,
                  rating: rm ? parseFloat(rm[1].replace(',', '.')) : null,
                  reviews: om ? parseInt(om[1].replace(/\s/g, ''), 10) : null
                });
              }
              return JSON.stringify(out);
            })()
        """.trimIndent()

        /** Чтение карточек Ozon: постоянная часть ссылки — `/product/<слаг>-<id>/`. */
        val OZON_SCRIPT = """
            (function(){
              var out = [], seen = {};
              // Название — самая длинная строка без цены, скидки, остатков и оценок: бейджи и служебные
              // подписи («ОСТАЛОСЬ 2 ШТ», «1 ед осталось») короче и не подходят.
              var bestLine = function(txt){
                var lines = txt.split('\n').map(function(s){return s.trim()}).filter(function(s){
                  return s.length > 10 && s.indexOf('₽') < 0 && !/отзыв|оцен|осталось|в корзину|купить|скидк|рассрочк|\u2605/i.test(s);
                });
                lines.sort(function(a, b){ return b.length - a.length });
                return lines.length ? lines[0] : '';
              };
              var imgSrc = function(img){
                if (!img) return '';
                var s = img.currentSrc || img.src || '';
                if (!s || s.indexOf('data:') === 0) {
                  s = img.getAttribute('data-src') || img.getAttribute('data-img') || '';
                }
                if (!s) {
                  var pic = img.closest ? img.closest('picture') : null;
                  var src = pic ? pic.querySelector('source[srcset]') : null;
                  if (src) s = (src.getAttribute('srcset') || '').split(' ')[0];
                }
                if (!s || s.indexOf('http') !== 0) {
                  var holder = img.closest ? img.closest('[style*="background-image"]') : null;
                  var bg = holder ? (holder.getAttribute('style') || '') : '';
                  var mm = bg.match(/url\(["']?(https?:[^"')]+)/);
                  if (mm) s = mm[1];
                }
                return s && s.indexOf('http') === 0 ? s : '';
              };
              var links = document.querySelectorAll('a[href*="/product/"]');
              for (var i = 0; i < links.length && out.length < LIMIT; i++) {
                var a = links[i], href = a.href || '';
                var m = href.match(/product\/[^"]*?(\d{6,})\/?(\?|$)/) || href.match(/-(\d{6,})\/?(\?|$)/);
                if (!m || seen[m[1]]) continue;
                seen[m[1]] = 1;
                var card = a.closest('div[class*="tile"]') || a.closest('li') || a.parentElement || a;
                var img = a.querySelector('img') || card.querySelector('img');
                var txt = ((card.innerText || card.textContent || '') + '').replace(/\u00a0/g, ' ');
                var pm = txt.match(/(\d[\d\s]{0,9})\s*₽/);
                var rm = txt.match(/([0-5][.,]\d)\b/);
                var om = txt.match(/([\d\s]+)\s*отзыв/);
                var titleEl = card.querySelector('[class*="tsBody500Medium"], [class*="tsBodyControl500Medium"]');
                var title = titleEl ? (titleEl.textContent || '').trim() : '';
                if (!title) title = (img && img.alt) || a.getAttribute('aria-label') || '';
                if (!title || title.length < 4) title = bestLine(txt);
                out.push({
                  id: m[1], url: href, title: title, html: out.length ? '' : card.outerHTML.slice(0, 500),
                  img: imgSrc(img),
                  price: pm ? parseInt(pm[1].replace(/\s/g, ''), 10) : null,
                  rating: rm ? parseFloat(rm[1].replace(',', '.')) : null,
                  reviews: om ? parseInt(om[1].replace(/\s/g, ''), 10) : null
                });
              }
              return JSON.stringify(out);
            })()
        """.trimIndent()
    }
}
