package com.bamnat.app.money

import com.bamnat.app.AppContainer
import com.bamnat.app.data.MoneyRepository
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.ShopCapture
import com.bamnat.core.work.CardSettlement
import com.bamnat.core.work.ShopMoney
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SHOP-08] Notifications a shop calendar reads as sales: a delivery app's order or a card
 * terminal's approval goes into that day's sales (배달앱 or 카드), and a card company's deposit ticks
 * the card sales it pays as 입금 확인 instead of being written as new income. Anything else, or any
 * other kind of calendar, is left to the usual payment reading.
 */
object ShopNotices {
    /** Recent texts already taken, so a notification posted again is not added twice. */
    private val seen = ArrayDeque<String>()
    private const val SEEN = 64

    private fun styleOf(c: AppContainer, id: String) = c.calendars.styleOf(id, c.settingsRepository.current.workStyle)

    /** A shop app's notification goes to the shop calendar when there is exactly one. */
    fun route(c: AppContainer, pkg: String, app: String): String? {
        if (ShopCapture.source(pkg, app) == null) return null
        return c.calendars.calendars.value.filter { styleOf(c, it.id) == WorkStyle.BUSINESS }.singleOrNull()?.id
    }

    /** Takes [text] into calendar [id]'s sales when it is a shop's; null when it is not, for the usual reading. */
    suspend fun take(c: AppContainer, id: String, text: String, pkg: String, app: String, postedAt: Long): MoneyRepository.Outcome? {
        if (styleOf(c, id) != WorkStyle.BUSINESS) return null
        val money = c.calendars.money(id)
        val arrived = LocalDateTime.ofInstant(Instant.ofEpochMilli(postedAt), ZoneId.systemDefault())
        val date = arrived.toLocalDate()
        val key = "$id|$date|$text"
        ShopCapture.read(text, pkg, app)?.let { sale ->
            if (!remember(key)) return MoneyRepository.Outcome.DUPLICATE
            val day = money.book.first().sales.firstOrNull { it.date == date }
            val next = ShopCapture.add(day, date, sale)
            money.save(next)
            com.bamnat.app.ui.work.ShopKeeper.salesSaved(c, id, date, next.sales)
            return MoneyRepository.Outcome.SAVED
        }
        val parsed = (PaymentParser.parse(text, arrived) as? PaymentParser.Result.Ok)?.parsed
        val amount = ShopCapture.settlement(text, parsed) ?: return null
        val book = money.book.first()
        val rules = c.calendars.get(id)?.shopMoney ?: ShopMoney()
        val matched = CardSettlement.match(amount, parsed?.date ?: date, CardSettlement.expected(book.sales, rules, KoreanHolidays::isHoliday))
        if (matched.isEmpty()) return null
        if (!remember(key)) return MoneyRepository.Outcome.DUPLICATE
        for (e in matched) book.sales.firstOrNull { it.date == e.saleDate }?.let { money.save(it.copy(cardSettled = true)) }
        return MoneyRepository.Outcome.SAVED
    }

    @Synchronized
    private fun remember(key: String): Boolean {
        if (key in seen) return false
        seen.addLast(key)
        while (seen.size > SEEN) seen.removeFirst()
        return true
    }
}
