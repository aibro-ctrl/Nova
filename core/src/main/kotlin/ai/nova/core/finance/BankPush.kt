package ai.nova.core.finance

import ai.nova.core.nlp.ExpenseCategories

/**
 * Покупка, распознанная в уведомлении банковского приложения.
 *
 * Такие пуши приходят почти от всех банков: «Покупка 850 ₽ · ПЯТЁРОЧКА», «Оплата 1 250,00 RUB
 * в YANDEX GO». Нова разбирает их и записывает трату в расходы — без ручного ввода.
 */
data class BankPush(
    /** Сумма в копейках. */
    val amountMinor: Long,
    val currency: String,
    /** Доход это или трата: зачисления и возвраты в расходы не пишем. */
    val isIncome: Boolean,
    /** Магазин или получатель — то, что покажем в записи. */
    val merchant: String,
    /** Последние 4 цифры карты, если банк их прислал. */
    val card: String?,
    /** Банк, от которого пришло уведомление. */
    val bank: String,
    /** Исходный текст — для проверки пользователем. */
    val raw: String,
) {
    /** Категория по названию магазина: «Пятёрочка» → «Продукты». */
    val category: String
        get() = if (isIncome) ExpenseCategories.INCOME
        else ExpenseCategories.categorize(merchant).takeIf { it != ExpenseCategories.OTHER } ?: ExpenseCategories.OTHER

    /** Описание записи: магазин, банк и карта — чтобы потом было понятно, откуда трата. */
    val description: String
        get() = buildString {
            append(merchant.ifBlank { bank })
            card?.let { append(" · карта ").append(it) }
        }

    /**
     * Один и тот же пуш банки часто присылают дважды (приложение и виджет).
     * Ключ без времени: одинаковые сумма и магазин в пределах нескольких минут — дубль.
     */
    val dedupKey: String get() = "$amountMinor|${merchant.lowercase().trim()}|$isIncome"
}

/**
 * Разбор банковских пушей. Только чистая логика: пакеты, тексты и суммы.
 * Всё, что связано с уведомлениями Android, остаётся в приложении.
 */
object BankPushParser {
    /** Банковские приложения, чьи пуши разбираем. Ключ — пакет, значение — название банка. */
    private val BANKS: Map<String, String> = mapOf(
        "ru.sberbankmobile" to "Сбер",
        "ru.sberbank.online" to "Сбер",
        "com.idamob.tinkoff.android" to "Т-Банк",
        "ru.tinkoff.mb" to "Т-Банк",
        "com.tinkoff.tinkoff" to "Т-Банк",
        "ru.tinkoff.investing" to "Т-Банк",
        "ru.alfabank.mobile.android" to "Альфа-Банк",
        "ru.alfabank.oavdo.amobile" to "Альфа-Банк",
        "ru.vtb24.mobilebanking.android" to "ВТБ",
        "ru.raiffeisen.mobile" to "Райффайзен",
        "ru.gazprombank.android.mobilebank" to "Газпромбанк",
        "ru.rosbank.android" to "Росбанк",
        "ru.sovcombank.halva" to "Совкомбанк",
        "ru.sovcombank.mobile" to "Совкомбанк",
        "ru.openbank.mobile" to "Открытие",
        "ru.ozon.ozonbank" to "Озон Банк",
        "ru.ozon.finance" to "Озон Банк",
        "com.yandex.bank" to "Яндекс Банк",
        "ru.yandex.mobilebank" to "Яндекс Банк",
        "ru.psbank.mobile" to "ПСБ",
        "ru.promsvyazbank.mobilebank" to "ПСБ",
        "ru.mts.bank" to "МТС Банк",
        "ru.mts.mtbank" to "МТС Банк",
        "ru.pochtabank.mobile" to "Почта Банк",
        "ru.homecredit.mobile" to "Хоум Банк",
        "ru.uralsib.mobile" to "Уралсиб",
        "ru.akbars.mobile" to "Ак Барс",
        "ru.rncb.mobile" to "РНКБ",
        "ru.bspb.mobile" to "БСПБ",
    )

    /** Название банка по пакету или null, если это не банк. */
    fun bank(pkg: String): String? = BANKS[pkg]

    /** Какие банки уже поддерживаются — показываем в настройках. */
    val supportedBanks: List<String> get() = BANKS.values.distinct().sorted()

    // «850 ₽», «1 234,50 руб», «1250.00 RUB», «1 234 рубля». Флаг u обязателен: без него
    // Java не складывает регистр кириллицы и «Баланс» не совпадёт с «баланс».
    private val AMOUNT = Regex(
        """(\d{1,3}(?:[\s\u00A0\u202F]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(₽|руб(?:\.|лей|ля|ль)?|р\.|rub)""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val INCOME_WORDS = Regex("""(?iu)(?<![а-яёa-z])(зачислен|пополнен|поступлен|поступил|возврат|вернул|перевод от|получен|начислен|доход|пришл)""")
    private val EXPENSE_WORDS = Regex("""(?iu)(?<![а-яёa-z])(покупка|покупку|оплата|оплачен|списание|списано|снятие|снято|платёж|платеж|перевод|перевели|отправлен|расход)""")

    // Служебные пуши: сумма есть, но траты нет. Никогда не пишем.
    private val HARD_SKIP = Regex(
        """(?iu)(код подтвержд|пароль|выписк|кэшбэк|кешбэк|бонус|акци|предложен|тариф|лимит|доступн|рейтинг|страховк|инвест|ставк|одобрен|заявк|подписк|напомин|между своими|между счетами|между вашими|внутрибанковск)""",
    )

    // «Баланс 12 340 ₽» — служебное, но в настоящей покупке банк пишет его рядом: гасим только без признаков операции.
    private val SOFT_SKIP = Regex("""(?iu)(баланс|остаток)""")

    private val CARD = Regex("""[*•·]{1,}\s?(\d{4})""")

    /** Слова, которые не могут быть названием магазина. */
    private val NOT_MERCHANT = setOf(
        "покупка", "покупку", "оплата", "оплачено", "списание", "перевод", "платёж", "платеж", "карта", "картой",
        "счет", "счёт", "успешно", "банк", "операция", "операции", "средства", "sms", "смс", "сообщение",
    )

    /**
     * Разбор уведомления. [knownBank] — название банка, если пакет известен; иначе null,
     * и тогда разбор идёт строже: такое уведомление никогда не пишется без подтверждения.
     */
    fun parse(title: String, text: String, knownBank: String?): BankPush? {
        val raw = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").trim()
        if (raw.isBlank()) return null
        if (HARD_SKIP.containsMatchIn(raw)) return null
        val saysIncome = INCOME_WORDS.containsMatchIn(raw)
        val saysExpense = EXPENSE_WORDS.containsMatchIn(raw)
        val amount = AMOUNT.find(raw) ?: return null
        // Знак перед суммой: «+5000 ₽» — поступление, «−500 ₽» — списание. Банки пишут так почти всегда.
        val sign = signOf(raw, amount.range.first)
        if (SOFT_SKIP.containsMatchIn(raw) && !saysIncome && !saysExpense && sign == 0) return null
        // У незнакомого пакета без явного «покупка/оплата/списание» или знака не рискуем.
        if (knownBank == null && !saysIncome && !saysExpense && sign == 0) return null
        val amountMinor = toMinor(amount.groupValues[1]) ?: return null
        if (amountMinor <= 0) return null
        val merchant = merchant(text.ifBlank { raw }, amount.value).orEmpty()
        if (knownBank == null && merchant.isBlank()) return null
        return BankPush(
            amountMinor = amountMinor,
            currency = "RUB",
            // «Перевод от Ивана +5000 ₽» — это доход, хотя слово «перевод» есть и в тратах:
            // решает знак суммы, а без него — слово «зачисление/поступление/возврат».
            isIncome = when {
                sign > 0 -> true
                sign < 0 -> false
                saysIncome -> true
                else -> false
            },
            merchant = merchant,
            card = CARD.find(raw)?.groupValues?.get(1),
            bank = knownBank ?: "Банк",
            raw = raw,
        )
    }

    /** 1 — перед суммой «+», −1 — «−»/«-», 0 — знака нет. */
    private fun signOf(raw: String, amountStart: Int): Int {
        val before = raw.substring(maxOf(0, amountStart - 5), amountStart)
        val plus = before.contains('+')
        val minus = before.any { it == '-' || it == '−' || it == '–' }
        return when {
            plus && !minus -> 1
            minus && !plus -> -1
            else -> 0
        }
    }

    /** «1 234,50» → 123450 копеек. */
    fun toMinor(value: String): Long? {
        val cleaned = value.replace(Regex("""[\s\u00A0\u202F]"""), "").replace(',', '.')
        val parts = cleaned.split('.')
        val rub = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val kop = parts.getOrNull(1)?.let { (it + "00").take(2).toLongOrNull() } ?: 0L
        return rub * 100 + kop
    }

    /**
     * Магазин из текста. Банки пишут по-разному:
     * «Покупка 850 ₽ · ПЯТЁРОЧКА», «Оплата 1 250 ₽ в YANDEX GO», «Покупка в магазине МАГНИТ: 500 ₽».
     */
    private fun merchant(text: String, amountText: String): String? {
        val segments = text.split('·', '•', '|', '\n', ';', '.')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        // Сумма и магазин в одном куске: «Оплата 1 250 ₽ в YANDEX GO прошла успешно».
        for (segment in segments) {
            if (!segment.contains(amountText)) continue
            Regex("""(?iu)(?<![а-яёa-z])в\s+(\S.*)$""").find(segment)?.let { match ->
                cleanMerchant(match.groupValues[1])?.let { return it }
            }
        }
        // Магазин отдельным куском: «ПЯТЁРОЧКА», «MAGNUM CASH&CARRY».
        for (segment in segments) {
            if (segment.contains(amountText)) continue
            if (Regex("""\d""").containsMatchIn(segment)) continue
            if (HARD_SKIP.containsMatchIn(segment) || SOFT_SKIP.containsMatchIn(segment)) continue
            cleanMerchant(segment)?.let { return it }
        }
        return null
    }

    private fun cleanMerchant(raw: String): String? {
        var v = raw.trim().trim('"', '«', '»', '\'', ':', ',', '—', '-', '.')
        v = Regex("""(?iu)\s+(?:прошл\w*|успешн\w*|выполнен\w*|одобрен\w*|подтвержден\w*|зачислен\w*)(?:\s.*)?$""").replace(v, "")
        v = Regex("""(?iu)^(?:в\s+)?(магазине|магазин|аптеке|аптека|кафе|ресторане|сервисе|сайте)\s+""").replace(v, "")
        v = Regex("""(?iu)\s+(?:по карте|картой|карта)\b.*$""").replace(v, "")
        v = Regex("""\s*[*•·]{1,}\s?\d{4}\s*$""").replace(v, "")
        v = Regex("""\s{2,}""").replace(v.trim(), " ")
        if (v.length < 3) return null
        if (v.lowercase() in NOT_MERCHANT) return null
        // Банки пишут магазины капсом: «ПЯТЁРОЧКА» → «Пятёрочка». Латиницу не трогаем: «MAGNUM CASH&CARRY».
        if (v.length > 4 && v.none { it in 'a'..'z' || it in 'A'..'Z' } && v == v.uppercase()) {
            v = v.lowercase().replaceFirstChar { it.titlecase() }
        }
        return v.take(60)
    }
}
