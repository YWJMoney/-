package com.bamnat.core

import com.bamnat.core.money.AccountType
import com.bamnat.core.money.CaptureRoute
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.work.HourlyWeek
import com.bamnat.core.work.NthWeekday
import com.bamnat.core.work.ShopDays
import com.bamnat.core.work.ShopRules
import com.bamnat.core.work.WorkStats
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class WorkKitTest {
    private val oct = YearMonth.of(2026, 10)

    /** [TYPE-01] Each kind of work names its own tab and settings. */
    @Test
    fun eachKindOfWorkHasItsOwnWords() {
        assertEquals("근무·급여", WorkStyle.SHIFT.words().tab)
        assertEquals("근무·급여", (null as WorkStyle?).words().tab)
        assertEquals("출근·시급", WorkStyle.HOURLY.words().tab)
        assertEquals("일감·수입", WorkStyle.FIELD.words().tab)
        assertEquals("매출·영업", WorkStyle.BUSINESS.words().tab)
        assertEquals("영업일·정기휴무", WorkStyle.BUSINESS.words().pattern)
        assertEquals("휴무", WorkStyle.BUSINESS.words().offDay)
    }

    /** [TYPE-03] 매달 둘째·넷째 월요일, the last Sunday, and the weekly days off together. */
    @Test
    fun shopDaysOffByRule() {
        val second = NthWeekday(2, DayOfWeek.MONDAY)
        assertTrue(second.matches(LocalDate.of(2026, 10, 12)))
        assertFalse(second.matches(LocalDate.of(2026, 10, 5)))
        assertTrue(NthWeekday(-1, DayOfWeek.SATURDAY).matches(LocalDate.of(2026, 10, 31)))
        assertFalse(NthWeekday(-1, DayOfWeek.SATURDAY).matches(LocalDate.of(2026, 10, 24)))
        assertEquals(listOf(second, NthWeekday(-1, DayOfWeek.SUNDAY)), NthWeekday.decode(NthWeekday.encode(listOf(second, NthWeekday(-1, DayOfWeek.SUNDAY)))))

        val rules = ShopRules(closedWeekdays = setOf(DayOfWeek.SUNDAY), monthly = listOf(second, NthWeekday(4, DayOfWeek.MONDAY)), holidaysClosed = true)
        val holiday = { d: LocalDate -> d == LocalDate.of(2026, 10, 9) }
        assertTrue(ShopDays.regularlyClosed(rules, LocalDate.of(2026, 10, 4), holiday))
        assertTrue(ShopDays.regularlyClosed(rules, LocalDate.of(2026, 10, 26), holiday))
        assertTrue(ShopDays.regularlyClosed(rules, LocalDate.of(2026, 10, 9), holiday))
        assertFalse(ShopDays.regularlyClosed(rules, LocalDate.of(2026, 10, 13), holiday))
        // Sundays are the weekly order's; only the monthly days and holidays are written as days off.
        assertEquals(
            listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 26)),
            ShopDays.extraClosed(rules, oct.atDay(1), oct.atEndOfMonth(), holiday),
        )
        assertEquals("매주 일요일 · 매달 둘째·넷째 월요일 · 공휴일 휴무", ShopDays.describe(rules))
        assertEquals("정기휴무 없음", ShopDays.describe(ShopRules()))
    }

    /** [TYPE-04] An open day that passed with nothing sold is closed after all, judged once. */
    @Test
    fun anOpenDayWithoutSalesBecomesClosed() {
        val today = LocalDate.of(2026, 10, 20)
        val open = { d: LocalDate -> d.dayOfWeek != DayOfWeek.SUNDAY }
        val sold = setOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17))
        val rules = ShopRules(checkedUntil = LocalDate.of(2026, 10, 15))
        assertEquals(listOf(LocalDate.of(2026, 10, 19)), ShopDays.autoClosed(rules, today, open) { it in sold })
        assertTrue(ShopDays.autoClosed(rules.copy(autoClosed = false), today, open) { false }.isEmpty())
        // Never checked: only yesterday, not a month of days the user never meant to fill in.
        assertEquals(listOf(LocalDate.of(2026, 10, 19)), ShopDays.autoClosed(ShopRules(), today, open) { false })
        assertTrue(ShopDays.autoClosed(rules.copy(checkedUntil = LocalDate.of(2026, 10, 19)), today, open) { false }.isEmpty())
    }

    /** [TYPE-05] A shop's average leaves closed days out and finds the best weekday. */
    @Test
    fun shopMonthAveragesOpenDaysOnly() {
        val sales = listOf(
            DailySales(LocalDate.of(2026, 10, 2), 300_000, 100_000), // Fri
            DailySales(LocalDate.of(2026, 10, 3), 500_000, 100_000), // Sat
            DailySales(LocalDate.of(2026, 10, 9), 100_000), // Fri
        )
        val m = WorkStats.shop(oct, sales) { it.dayOfWeek != DayOfWeek.SUNDAY }
        assertEquals(27, m.openDays)
        assertEquals(4, m.closedDays)
        assertEquals(300_000, m.perOpenDay)
        assertEquals(200_000L, m.byWeekday[DayOfWeek.FRIDAY])
        assertEquals(DayOfWeek.SATURDAY, m.bestWeekday)
        assertEquals(700_000, m.net)
        assertEquals(LocalDate.of(2026, 10, 3), m.best?.date)
        assertEquals(LocalDate.of(2026, 10, 9), m.worst?.date)
    }

    /** [TYPE-06] 주휴수당 needs 15 hours and no absence; its hours are a fifth of the week's, at most 8. */
    @Test
    fun hourlyWeeksAndHolidayPay() {
        val weeks = WorkStats.hourlyWeeks(oct, DayOfWeek.MONDAY, { d -> if (d.dayOfWeek <= DayOfWeek.WEDNESDAY) 5 * 60 else 0 }) { it == LocalDate.of(2026, 10, 14) }
        assertEquals(LocalDate.of(2026, 9, 28), weeks.first().start)
        assertEquals(5, weeks.size)
        assertTrue(weeks[1].holidayPay)
        assertEquals(3 * 60, weeks[1].holidayPayMinutes)
        assertFalse(weeks[2].holidayPay)
        assertEquals(8 * 60, HourlyWeek(oct.atDay(1), 45 * 60, 0).holidayPayMinutes)
        assertEquals(60, HourlyWeek(oct.atDay(1), 14 * 60, 0).shortBy)
    }

    /** [TYPE-07] A freelancer's clients, biggest first, with what is still owed. */
    @Test
    fun clientsAddUp() {
        val jobs = listOf(
            Job(date = oct.atDay(3), title = "욕실", client = "가나다인테리어", amount = 600_000, deposit = 200_000, depositPaid = true),
            Job(date = oct.atDay(9), title = "주방", client = "가나다인테리어", amount = 400_000, balancePaid = true, depositPaid = true),
            Job(date = oct.atDay(12), title = "도배", client = "", amount = 300_000),
        )
        val c = WorkStats.clients(oct, jobs)
        assertEquals(listOf("가나다인테리어", "거래처 미정"), c.map { it.client })
        assertEquals(1_000_000, c[0].earned)
        assertEquals(400_000, c[0].owed)
    }

    /** [TYPE-08] Field and shop calendars start with their own categories; 기타 stays last. */
    @Test
    fun workCategoriesComeFirst() {
        val shop = MoneyCategories.of(MoneyKind.EXPENSE, emptyList(), WorkStyle.BUSINESS)
        assertEquals("재료비", shop.first())
        assertEquals(MoneyCategories.OTHER, shop.last())
        assertTrue("식비" in shop)
        assertEquals("카드매출", MoneyCategories.of(MoneyKind.INCOME, emptyList(), WorkStyle.BUSINESS).first())
        assertEquals("자재비", MoneyCategories.of(MoneyKind.EXPENSE, emptyList(), WorkStyle.FIELD).first())
        assertEquals(MoneyCategories.expense, MoneyCategories.of(MoneyKind.EXPENSE, emptyList(), WorkStyle.SHIFT))
    }

    /** [CALS-04] A payment goes to the calendar that has its card, then the issuer's, the app's, or the chosen one. */
    @Test
    fun paymentsFindTheirCalendar() {
        val places = listOf(
            CaptureRoute.Place("main", listOf(MoneyAccount(id = 1, name = "생활비 통장", type = AccountType.BANK))),
            CaptureRoute.Place("shop", listOf(MoneyAccount(id = 1, name = "신한 사업자카드", type = AccountType.CREDIT, last4 = "1234"))),
        )
        assertEquals("shop", CaptureRoute.pick("1234", null, "x", places, emptyMap(), "main"))
        assertEquals("shop", CaptureRoute.pick(null, "신한카드", "x", places, emptyMap(), "main"))
        assertEquals("shop", CaptureRoute.pick(null, null, "com.kbstar", places, mapOf("com.kbstar" to "shop"), "main"))
        assertEquals("main", CaptureRoute.pick("9999", "삼성카드", "x", places, emptyMap(), "main"))
        assertEquals("shop", CaptureRoute.pick(null, null, "x", places, emptyMap(), "shop"))
        // A calendar that is gone is never picked.
        assertEquals("main", CaptureRoute.pick(null, null, "x", places, mapOf("x" to "gone"), "gone"))
        assertEquals("KB국민", CaptureRoute.issuerKey("KB국민카드"))
    }
}
