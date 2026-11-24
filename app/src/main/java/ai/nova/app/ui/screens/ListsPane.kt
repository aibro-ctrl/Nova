package ai.nova.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.nova.app.AppContainer
import ai.nova.app.ui.components.EditorSheet
import ai.nova.app.ui.components.EmptyState
import ai.nova.app.ui.components.Group
import ai.nova.app.ui.components.GroupDivider
import ai.nova.app.ui.components.NovaField
import ai.nova.app.ui.components.PrimaryButton
import ai.nova.app.ui.components.RowItem
import ai.nova.app.ui.components.SectionLabel
import ai.nova.core.model.ShoppingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Вкладка «Покупки» в «Планах»: списки с галочками, сначала то, что ещё купить. */
fun LazyListScope.listsPane(c: AppContainer, items: List<ShoppingItem>, name: String, scope: CoroutineScope) {
    if (items.isEmpty()) {
        item(key = "lists-empty") {
            EmptyState(Icons.Rounded.ShoppingCart, "Список покупок пуст", "Скажите: «$name, добавь в покупки молоко, хлеб и яйца». Потом — «что купить?» и «купила молоко».")
        }
        return
    }
    items.groupBy { it.listName }.forEach { (list, rows) ->
        val left = rows.count { !it.done }
        item(key = "list-label-$list") {
            SectionLabel("$list · осталось $left", trailing = if (rows.any { it.done }) ({
                TextButton(onClick = { scope.launch { c.store.shopping.clear(list, onlyDone = true) } }) { Text("Убрать купленное") }
            }) else null)
        }
        item(key = "list-$list") {
            Group {
                rows.sortedWith(compareBy({ it.done }, { it.createdAt })).forEachIndexed { i, it ->
                    if (i > 0) GroupDivider(inset = 60.dp)
                    ShoppingRow(c, it, scope)
                }
            }
        }
    }
}

/**
 * Строка списка. Товар с площадки — это карточка: фотография, цена, магазин и оценка, а касание
 * открывает страницу товара. Обычный пункт («молоко») остаётся строкой — галочка и удаление как раньше.
 */
@Composable
private fun ShoppingRow(c: AppContainer, item: ShoppingItem, scope: CoroutineScope) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val done = item.done
    val open = item.url?.takeIf { it.isNotBlank() }
    RowItem(
        title = item.text,
        subtitle = item.productSubtitle,
        titleColor = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        // Касание по товару открывает карточку: если ссылки нет, отмечаем купленным, как и раньше.
        onClick = if (open != null) ({ runCatching { uri.openUri(open) } }) else ({ scope.launch { c.store.shopping.setDone(item.id, !done) } }),
        leading = {
            if (item.isProduct) {
                ai.nova.app.ui.components.ProductThumb(url = item.imageUrl, size = 44, contentDescription = item.text)
            } else {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (done) "Вернуть" else "Куплено",
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp).size(24.dp),
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isProduct) {
                    // Отметить купленным — здесь же: по самой строке теперь открывается карточка.
                    IconButton(onClick = { scope.launch { c.store.shopping.setDone(item.id, !done) } }) {
                        Icon(
                            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (done) "Вернуть" else "Куплено",
                            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { scope.launch { c.store.shopping.delete(item.id) } }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** «2 889 ₽ · Wildberries · ★ 4,3 · 150 отзывов» — то, по чему товар узнают, без перехода на сайт. */
private val ShoppingItem.productSubtitle: String?
    get() {
        if (!isProduct) return null
        val parts = mutableListOf<String>()
        price?.let { parts += ai.nova.core.shopping.ShoppingProduct.formatMoney(it) + " ₽" }
        marketplace?.let { id -> ai.nova.core.shopping.Marketplace.entries.firstOrNull { it.id == id }?.title }?.let { parts += it }
        rating?.let { r ->
            val text = String.format(java.util.Locale.US, "%.1f", r).replace('.', ',')
            val count = reviews?.takeIf { it > 0 }?.let { " · $it ${ai.nova.core.shopping.ShoppingProduct.reviewsWord(it)}" }.orEmpty()
            parts += "★ $text$count"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

/** Добавление пунктов вручную: через запятую — сразу несколько. */
@Composable
fun ShoppingAdder(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(ShoppingItem.DEFAULT_LIST) }
    EditorSheet("В список", onDismiss = onDismiss) {
        NovaField(text, { text = it }, "Что добавить", placeholder = "Молоко, хлеб, яйца")
        NovaField(list, { list = it }, "Список", supporting = "Например: «Покупки», «В дорогу», «На дачу»")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            PrimaryButton("Добавить", {
                val items = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) scope.launch { c.store.shopping.add(list.trim().ifEmpty { ShoppingItem.DEFAULT_LIST }, items) }
                onDismiss()
            }, enabled = text.isNotBlank())
        }
    }
}
