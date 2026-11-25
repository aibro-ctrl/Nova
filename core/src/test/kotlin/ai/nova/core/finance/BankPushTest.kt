package ai.nova.core.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор банковских пушей: тексты взяты из настоящих уведомлений банков. */
class BankPushTest {
    private fun parse(title: String, text: String, bank: String? = "Сбер") = BankPushParser.parse(title, text, bank)

    @Test fun sberPurchase() {
        val push = parse("СберБанк", "Покупка 850 ₽\nПЯТЁРОЧКА. Баланс 12 340 ₽") ?: error("не разобрано")
        assertEquals(85_000, push.amountMinor)
        assertFalse(push.isIncome)
        assertEquals("Пятёрочка", push.merchant)
        assertEquals("Продукты", push.category)
    }

    @Test fun tbankPurchaseWithCard() {
        val push = parse("Т-Банк", "Покупка 1 250,50 ₽\nMAGNUM CASH&CARRY\nКарта *5678") ?: error("не разобрано")
        assertEquals(125_050, push.amountMinor)
        assertEquals("5678", push.card)
        assertTrue(push.description.contains("карта 5678"))
    }

    @Test fun alfaPaymentInShop() {
        val push = parse("Альфа-Банк", "Оплата 1 250,00 RUB в YANDEX GO прошла успешно") ?: error("не разобрано")
        assertEquals(125_000, push.amountMinor)
        assertTrue(push.merchant.contains("YANDEX GO", ignoreCase = true))
        assertEquals("Транспорт", push.category)
    }

    @Test fun incomeIsIncome() {
        val push = parse("СберБанк", "Зачисление 50 000 ₽\nЗарплата") ?: error("не разобрано")
        assertTrue(push.isIncome)
        assertEquals("Доходы", push.category)
    }

    @Test fun incomingTransferWithPlusIsIncome() {
        // Раньше «перевод» в тексте делал такое поступление расходом.
        val push = parse("Т-Банк", "Перевод от ИВАН И. +5 000 ₽\nКарта *5678") ?: error("не разобрано")
        assertTrue(push.isIncome)
        assertEquals(500_000, push.amountMinor)
        assertEquals("Доходы", push.category)
    }

    @Test fun plusSignAloneIsIncome() {
        val push = parse("СберБанк", "Поступление на карту +5000 ₽") ?: error("не разобрано")
        assertTrue(push.isIncome)
        assertEquals(500_000, push.amountMinor)
    }

    @Test fun minusSignIsExpense() {
        val push = parse("СберБанк", "Списание −500 ₽ · ПЯТЁРОЧКА") ?: error("не разобрано")
        assertFalse(push.isIncome)
        assertEquals(50_000, push.amountMinor)
        assertEquals("Продукты", push.category)
    }

    @Test fun plusSignIsIncomeForUnknownApp() {
        val push = BankPushParser.parse("Банк", "Зачисление +5000 ₽ · ИВАН И.", null) ?: error("не разобрано")
        assertTrue(push.isIncome)
        assertEquals("Доходы", push.category)
    }

    @Test fun skipsBalanceAndCodes() {
        assertNull(parse("СберБанк", "Код подтверждения 1234. Никому не сообщайте"))
        assertNull(parse("СберБанк", "Баланс 12 340 ₽"))
        assertNull(parse("СберБанк", "Кэшбэк за март: 320 ₽"))
        assertNull(parse("Т-Банк", "Перевод между своими счетами 10 000 ₽"))
    }

    @Test fun secretCodesAreDetectedBeforeParsing() {
        assertTrue(BankPushParser.isSecret("СберБанк", "Код: 4821. Никому не сообщайте"))
        assertTrue(BankPushParser.isSecret("Т-Банк", "Одноразовый пароль 123456 для входа"))
        assertTrue(BankPushParser.isSecret("Google", "Your verification code is 998877"))
        assertTrue(BankPushParser.isSecret("Банк", "Подтвердите вход: 5566. Списание 1 ₽"))
        assertNull(parse("Банк", "Подтвердите вход: 5566. Списание 1 ₽"))
        assertFalse(BankPushParser.isSecret("СберБанк", "Покупка 850 ₽ · ПЯТЁРОЧКА"))
    }

    @Test fun skipsNotificationsWithoutAmount() {
        assertNull(parse("СберБанк", "Успейте оформить карту с кэшбэком 5%"))
    }

    @Test fun unknownPackageNeedsExplicitWord() {
        // Незнакомый пакет: без «покупка/оплата/списание» не пишем ничего.
        assertNull(BankPushParser.parse("Доставка", "Ваш заказ 850 ₽ в пути", null))
        // С явным словом — разбираем, но приложение спросит подтверждение.
        val push = BankPushParser.parse("Магазин", "Списание 850 ₽ · ПЕРВЫЙ МЕБЕЛЬНЫЙ", null) ?: error("не разобрано")
        assertEquals("Первый мебельный", push.merchant)
        assertEquals("Банк", push.bank)
    }

    @Test fun thousandsAreParsed() {
        assertEquals(123_450, BankPushParser.toMinor("1 234,50"))
        assertEquals(1_234_500, BankPushParser.toMinor("12 345"))
        assertEquals(99_900, BankPushParser.toMinor("999"))
        assertEquals(85_000, BankPushParser.toMinor("850,00"))
        assertNull(BankPushParser.toMinor("abc"))
    }

    @Test fun sameKeyForRepeatedPush() {
        val a = parse("СберБанк", "Покупка 850 ₽ · ПЯТЁРОЧКА") ?: error("не разобрано")
        val b = parse("СберБанк", "Покупка 850 ₽ · Пятёрочка. Баланс 100 ₽") ?: error("не разобрано")
        assertEquals(a.dedupKey, b.dedupKey)
    }

    @Test fun knownBanksAreMapped() {
        assertEquals("Сбер", BankPushParser.bank("ru.sberbankmobile"))
        assertEquals("Т-Банк", BankPushParser.bank("com.idamob.tinkoff.android"))
        assertNull(BankPushParser.bank("org.telegram.messenger"))
        assertTrue(BankPushParser.supportedBanks.size >= 10)
    }
}
