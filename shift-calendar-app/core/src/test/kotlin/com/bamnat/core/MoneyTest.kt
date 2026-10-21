package com.bamnat.core

import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyCalculator
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** [WORK-02][WORK-03][WORK-04][LEDG-01] Month totals from the ledger, jobs, sales and the pay engine. */
class MoneyTest {
    private val nov = YearMonth.of(2026, 11)
    private fun d(day: Int) = nov.atDay(day)

    @Test
    fun jobDepositAndBalanceAreTrackedApart() {
        val job = Job(date = d(3), title = "욕실 타일", amount = 1_200_000, deposit = 300_000)
        assertEquals(1_200_000, job.unpaid)
        assertEquals(300_000, job.copy(depositPaid = true).received)
        assertEquals(900_000, job.copy(depositPaid = true).unpaid)
        assertEquals(0, job.copy(depositPaid = true, balancePaid = true).unpaid)
        // Only the rest paid (deposit still owed).
        assertEquals(300_000, job.copy(balancePaid = true).unpaid)
        // A deposit larger than the price never makes money appear.
        assertEquals(500_000, Job(date = d(3), title = "x", amount = 500_000, deposit = 800_000, depositPaid = true).received)
    }

    @Test
    fun monthAddsEverySourceAndSkipsTransfers() {
        val entries = listOf(
            MoneyEntry(date = d(1), kind = MoneyKind.EXPENSE, amount = 4_500, category = "카페"),
            MoneyEntry(date = d(2), kind = MoneyKind.EXPENSE, amount = 32_000, category = "식비"),
            MoneyEntry(date = d(2), kind = MoneyKind.EXPENSE, amount = 5_500, category = "카페"),
            MoneyEntry(date = d(5), kind = MoneyKind.INCOME, amount = 50_000, category = "부수입"),
            MoneyEntry(date = d(6), kind = MoneyKind.TRANSFER, amount = 300_000, category = "저축"),
            // Another month is ignored.
            MoneyEntry(date = d(1).minusDays(1), kind = MoneyKind.EXPENSE, amount = 99_999, category = "식비"),
        )
        val jobs = listOf(
            Job(date = d(3), title = "욕실", amount = 1_200_000, deposit = 300_000, depositPaid = true),
            Job(date = d(20), title = "도배", amount = 800_000),
        )
        val sales = listOf(DailySales(d(4), sales = 420_000, costs = 120_000))
        val m = MoneyCalculator.month(nov, entries, jobs, sales, payEstimate = 2_000_000)
        assertEquals(42_000, m.expenses)
        assertEquals(listOf("식비" to 32_000L, "카페" to 10_000L), m.expenseByCategory.map { it.category to it.amount })
        assertEquals(2, m.expenseByCategory.first { it.category == "카페" }.count)
        // [RECV-04] Only the paid deposit of the jobs came in; the rest is 받을 돈.
        assertEquals(2_000_000 + 300_000 + 300_000 + 50_000L, m.income)
        assertEquals(300_000, m.jobReceived)
        assertEquals(1_700_000, m.jobOwed)
        assertEquals(m.income - 42_000, m.balance)
    }

    /** [RECV-04] A job written with its price is not money in until 돈 받음 is ticked. */
    @Test
    fun aJobCountsAsIncomeOnlyOnceItIsPaid() {
        val job = Job(date = d(3), title = "도배", amount = 800_000, deposit = 200_000)
        val none = MoneyCalculator.month(nov, emptyList(), listOf(job), emptyList())
        assertEquals(0, none.income)
        assertEquals(800_000, none.jobEarned)
        assertEquals(800_000, none.jobOwed)
        assertEquals(200_000, MoneyCalculator.month(nov, emptyList(), listOf(job.copy(depositPaid = true)), emptyList()).income)
        val all = MoneyCalculator.month(nov, emptyList(), listOf(job.copy(depositPaid = true, balancePaid = true)), emptyList())
        assertEquals(800_000, all.income)
        assertEquals(0, all.jobOwed)
        // The calendar cell follows: nothing in until paid, the owed part shown as 받을 돈.
        val day = MoneyCalculator.days(nov, emptyList(), listOf(job), emptyList()).getValue(d(3))
        assertEquals(0, day.income)
        assertEquals(800_000, day.unpaid)
    }

    @Test
    fun recordedSalaryReplacesTheEstimate() {
        val paid = listOf(MoneyEntry(date = d(10), kind = MoneyKind.INCOME, amount = 2_150_000, category = "급여"))
        val m = MoneyCalculator.month(nov, paid, emptyList(), emptyList(), payEstimate = 2_000_000)
        assertEquals(2_150_000, m.payCounted)
        assertEquals(2_150_000, m.income)
        assertEquals(2_000_000, MoneyCalculator.month(nov, emptyList(), emptyList(), emptyList(), 2_000_000).income)
    }

    @Test
    fun daysAndUnpaidJobs() {
        val jobs = listOf(
            Job(id = 1, date = d(3), title = "a", amount = 100_000),
            Job(id = 2, date = d(25), title = "b", amount = 200_000, deposit = 50_000, depositPaid = true),
            Job(id = 3, date = d(1), title = "c", amount = 70_000, depositPaid = true, balancePaid = true),
        )
        val days = MoneyCalculator.days(nov, listOf(MoneyEntry(date = d(3), kind = MoneyKind.EXPENSE, amount = 8_000, category = "식비")),
            jobs, listOf(DailySales(d(9), 0, 0)))
        assertEquals(0, days.getValue(d(3)).income)
        assertEquals(8_000, days.getValue(d(3)).expense)
        assertEquals(70_000, days.getValue(d(1)).income)
        assertEquals(50_000, days.getValue(d(25)).income)
        assertEquals(150_000, days.getValue(d(25)).unpaid)
        assertFalse(d(9) in days)
        assertEquals(listOf(1L), MoneyCalculator.unpaid(jobs, d(20)).map { it.id })
        assertEquals(listOf(1L, 2L), MoneyCalculator.unpaid(jobs, d(30)).map { it.id })
    }

    @Test
    fun withholdingFollowsTheTenWonRule() {
        // 3% then a tenth of it, each cut below 10 won: 1,000,000 → 30,000 + 3,000.
        assertEquals(33_000, com.bamnat.core.money.Withholding.of(1_000_000))
        assertEquals(99_000, com.bamnat.core.money.Withholding.of(3_000_000))
        // 123,456 → 3,703.68 → 3,700; local 370.
        assertEquals(4_070, com.bamnat.core.money.Withholding.of(123_456))
        val lesson = Job(date = d(5), title = "강의", amount = 1_000_000, withholding = true, balancePaid = true)
        assertEquals(967_000, lesson.payable)
        assertEquals(0, lesson.unpaid)
        val m = MoneyCalculator.month(nov, emptyList(), listOf(lesson), emptyList())
        assertEquals(967_000, m.income)
        assertEquals(33_000, m.jobWithheld)
    }

    @Test
    fun jobCostsGoOutWithSpending() {
        val tiles = Job(date = d(3), title = "욕실", amount = 1_200_000, costs = 350_000)
        val m = MoneyCalculator.month(nov, emptyList(), listOf(tiles), emptyList())
        // [RECV-04] Unpaid: nothing in, the materials already out.
        assertEquals(0, m.income)
        assertEquals(350_000, m.spent)
        assertEquals(-350_000, m.balance)
        assertEquals(350_000, m.jobCosts)
        assertEquals(850_000, m.jobProfit)
        val paid = MoneyCalculator.month(nov, emptyList(), listOf(tiles.copy(balancePaid = true)), emptyList())
        assertEquals(1_200_000, paid.income)
        assertEquals(850_000, paid.balance)
        assertEquals(350_000, MoneyCalculator.days(nov, emptyList(), listOf(tiles), emptyList()).getValue(d(3)).expense)
        // Costs do not change what the client still owes.
        assertEquals(1_200_000, tiles.unpaid)
    }

    @Test
    fun aJobOverSeveralDaysMarksEachDayButCountsOnce() {
        val job = Job(id = 7, date = d(28), endDate = d(30).plusDays(2), title = "주방 리모델링", amount = 3_000_000, balancePaid = true)
        assertEquals(5, job.days)
        val book = com.bamnat.core.money.MoneyBook(jobs = listOf(job))
        val days = book.days(nov)
        assertEquals(setOf(d(28), d(29), d(30)), days.keys)
        assertEquals(3_000_000, days.getValue(d(28)).income)
        assertEquals(0, days.getValue(d(29)).income)
        assertEquals(1, days.getValue(d(30)).jobs)
        // December shows the remaining days without the money again.
        val dec = book.days(nov.plusMonths(1))
        assertEquals(setOf(nov.plusMonths(1).atDay(1), nov.plusMonths(1).atDay(2)), dec.keys)
        assertEquals(0, book.month(nov.plusMonths(1)).income)
        assertEquals(listOf(7L), book.on(d(29)).jobs.map { it.id })
        // An end before the start means one day.
        assertEquals(1, job.copy(endDate = d(1)).days)
    }

    @Test
    fun workStylesPickTheirIncome() {
        assertTrue(WorkStyle.SHIFT.usesPattern && WorkStyle.SHIFT.usesPayEngine)
        assertTrue(WorkStyle.HOURLY.usesPayEngine && !WorkStyle.HOURLY.usesPattern)
        assertTrue(WorkStyle.FIELD.usesJobs && !WorkStyle.FIELD.usesPayEngine)
        assertTrue(WorkStyle.BUSINESS.usesSales && !WorkStyle.BUSINESS.usesJobs)
    }

    /** [SHR-11] A link's money follows the work: pay for employees, job income for field work, sales for a shop. */
    @Test
    fun linkMoneyFollowsTheWork() {
        assertEquals(com.bamnat.core.work.MoneyShare.PAY, com.bamnat.core.work.MoneyShare.of(null))
        assertEquals(com.bamnat.core.work.MoneyShare.PAY, com.bamnat.core.work.MoneyShare.of(WorkStyle.HOURLY))
        assertEquals(com.bamnat.core.work.MoneyShare.JOBS, com.bamnat.core.work.MoneyShare.of(WorkStyle.FIELD))
        assertEquals(com.bamnat.core.work.MoneyShare.SALES, com.bamnat.core.work.MoneyShare.of(WorkStyle.BUSINESS))
        assertEquals(com.bamnat.core.work.MoneyShare.SALES, com.bamnat.core.work.MoneyShare.ofKey("sales"))
        assertEquals(com.bamnat.core.work.MoneyShare.PAY, com.bamnat.core.work.MoneyShare.ofKey(null))

        val nov = java.time.YearMonth.of(2026, 11)
        val months = listOf(nov, nov.plusMonths(1), nov.plusMonths(2))
        val f = com.bamnat.core.work.MoneyShareFigures
        val sales = listOf(
            com.bamnat.core.money.DailySales(nov.atDay(1), 600_000, costs = 200_000),
            com.bamnat.core.money.DailySales(nov.atDay(2), 400_000),
            com.bamnat.core.money.DailySales(nov.plusMonths(1).atDay(1), 0, costs = 50_000),
            com.bamnat.core.money.DailySales(nov.minusMonths(1).atDay(30), 999_000),
        )
        // 매출 before costs; a month with only costs, and months outside the link, are left out.
        assertEquals(listOf(com.bamnat.core.work.MonthFigure(nov, 1_000_000, "매출")), f.sales(months, sales))

        val jobs = listOf(
            com.bamnat.core.money.Job(date = nov.atDay(3), endDate = nov.atDay(5), title = "욕실", amount = 900_000, withholding = true),
            com.bamnat.core.money.Job(date = nov.atDay(9), title = "상담", amount = 0),
            com.bamnat.core.money.Job(date = nov.plusMonths(2).atDay(1), title = "도배", amount = 300_000),
        )
        assertEquals(listOf(com.bamnat.core.work.MonthFigure(nov, 870_300, "일감 1건"), com.bamnat.core.work.MonthFigure(nov.plusMonths(2), 300_000, "일감 1건")),
            f.jobs(months, jobs))

        val ledger = listOf(
            com.bamnat.core.money.MoneyEntry(date = nov.atDay(10), kind = com.bamnat.core.money.MoneyKind.INCOME, amount = 2_800_000, category = "급여"),
            com.bamnat.core.money.MoneyEntry(date = nov.atDay(11), kind = com.bamnat.core.money.MoneyKind.INCOME, amount = 50_000, category = "용돈"),
            com.bamnat.core.money.MoneyEntry(date = nov.atDay(12), kind = com.bamnat.core.money.MoneyKind.EXPENSE, amount = 9_000, category = "급여"),
        )
        assertEquals(listOf(com.bamnat.core.work.MonthFigure(nov, 2_800_000, "받은 급여")), f.recordedPay(months, ledger))
        assertTrue(f.sales(months, emptyList()).isEmpty())
    }

    @Test
    fun `recorded pay counts salary-like categories`() {
        val f = com.bamnat.core.work.MoneyShareFigures
        val nov = YearMonth.of(2026, 11)
        for (name in listOf("급여", "월급", "10월 월급", "급여(본사)", "급여 - 알바", "상여", "상여금", "주급", "일당", "알바비", "알바 급여", "임금"))
            assertTrue(name, f.isSalary(name))
        for (name in listOf("부수입", "용돈", "급여통장 이자", "월급날 선물", "환급", "기타", ""))
            assertFalse(name, f.isSalary(name))
        fun income(day: Int, amount: Long, category: String, parts: List<com.bamnat.core.money.EntryPart> = emptyList()) =
            com.bamnat.core.money.MoneyEntry(date = nov.atDay(day), kind = com.bamnat.core.money.MoneyKind.INCOME, amount = amount, category = category, parts = parts)
        val ledger = listOf(
            income(10, 2_000_000, "월급"),
            income(15, 300_000, "상여"),
            income(20, 50_000, "용돈"),
            // A split line: only its salary part.
            income(25, 100_000, "기타", listOf(com.bamnat.core.money.EntryPart("알바비", 70_000), com.bamnat.core.money.EntryPart("기타", 30_000))),
        )
        assertEquals(listOf(com.bamnat.core.work.MonthFigure(nov, 2_370_000, "받은 급여")), f.recordedPay(listOf(nov), ledger))
    }
}

class JobRemindersTest {
    private val day = java.time.LocalDate.of(2026, 11, 3)

    @Test
    fun nextReminderIsTheEarliestStillAhead() {
        val jobs = listOf(
            com.bamnat.core.money.Job(id = 1, date = day, title = "a", start = java.time.LocalTime.of(9, 0), remindMinutes = 60),
            com.bamnat.core.money.Job(id = 2, date = day, title = "b", start = java.time.LocalTime.of(14, 0), remindMinutes = 30),
            com.bamnat.core.money.Job(id = 3, date = day, title = "no time", remindMinutes = 30),
            com.bamnat.core.money.Job(id = 4, date = day, title = "no reminder", start = java.time.LocalTime.of(8, 0)),
        )
        val r = com.bamnat.core.money.JobReminders
        assertEquals(1L, r.next(jobs, day.atTime(7, 0))?.job?.id)
        assertEquals(day.atTime(8, 0), r.next(jobs, day.atTime(7, 0))?.at)
        assertEquals(2L, r.next(jobs, day.atTime(8, 0))?.job?.id)
        assertEquals(null, r.next(jobs, day.atTime(13, 30)))
        assertEquals("하루 전", r.label(1440))
    }
}
