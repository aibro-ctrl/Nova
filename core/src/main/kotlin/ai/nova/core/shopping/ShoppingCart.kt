package ai.nova.core.shopping

/**
 * Границы того, что Нова делает с покупками.
 *
 * Корзина — да. Оформление заказа и оплата — никогда, ни в каком режиме: это деньги человека и его
 * ответственность. Проверка стоит до вызова площадки, а не после, чтобы отказ нельзя было обойти фразой.
 */
object ShoppingActionValidator {

    /** Слова, которые означают деньги, а не корзину. */
    private val FORBIDDEN = listOf(
        "оплат", "заплат", "заплач", "оплач", "купи и оплат", "оформи заказ", "оформить заказ",
        "оформи покупк", "оформить покупк", "подтверди заказ", "подтвердить заказ", "заверши",
        "checkout", "bay", "pay", "спиши", "списание", "переведи деньг", "введи карт", "cvv",
    )

    /** Что человек просит сделать с корзиной. */
    enum class Action { ADD, REMOVE, UNKNOWN }

    data class Verdict(val action: Action, val blocked: Boolean = false, val reason: String? = null)

    fun check(text: String): Verdict {
        val t = text.lowercase()
        val blocked = FORBIDDEN.firstOrNull { t.contains(it) }
        if (blocked != null) {
            return Verdict(
                Action.UNKNOWN,
                blocked = true,
                reason = "Оформление и оплату я не делаю — это только вы сами. Могу положить товар в корзину.",
            )
        }
        return when {
            listOf("убери", "удали", "убрать").any { t.contains(it) } -> Verdict(Action.REMOVE)
            listOf("в корзину", "в корзине", "положи", "добавь", "закажи").any { t.contains(it) } ->
                Verdict(Action.ADD)
            else -> Verdict(Action.UNKNOWN)
        }
    }

    /** «Оплати его» — отказ, даже если до этого Нова сама добавила товар в корзину. */
    fun refusal(text: String): String? = check(text).reason
}
