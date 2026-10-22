package com.bamnat.core

import com.bamnat.core.money.ClientColors
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.DepositMatch
import com.bamnat.core.money.FieldYear
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatement
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.Receivables
import com.bamnat.core.money.WorkCsv
import com.bamnat.core.work.WorkStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** [FLD-02][FLD-03][FLD-05][FLD-06][FLD-07][FLD-09][FLD-10] 현장·프리랜서 work: 공수, job steps, statements, deposits, the year. */
class FieldWorkTest {
    private fun d(day: Int, month: Int = 11) = LocalDate.of(2026, month, day)
    private val nov = YearMonth.of(2026, 11)

    @Test
    fun dayRateTapWritesUnitsTimesRateAndKeepsPaymentsOnChange() {
        val first = DayRate.job(d(3), 1.0, 200_000, "김반장")
        assertEquals(200_000, first.amount)
        assertEquals(1.0, first.units!!, 0.0)
        assertEquals("1공", first.title)
        assertEquals("김반장", first.client)
        val paid = first.copy(id = 7, balancePaid = true)
        val half = DayRate.job(d(3), 0.5, 200_000, "다른 업체", existing = paid)
        assertEquals(7, half.id)
        assertEquals(100_000, half.amount)
        assertEquals("0.5공", half.title)
        assertEquals("김반장", half.client)
        assertTrue(half.balancePaid)
        // More 공수 than was paid for: the rest is owed again, at the job's own 일당.
        val more = DayRate.job(d(3), 1.5, DayRate.rateOf(paid)!!, "", existing = paid)
        assertEquals(300_000, more.amount)
        assertFalse(more.balancePaid)
        assertEquals("1공", DayRate.cell(1.0))
        assertEquals("0.5", DayRate.cell(0.5))
        assertEquals("1.5", DayRate.cell(1.5))
        assertEquals(300_000, DayRate.amount(200_000, 1.5))
    }

    @Test
    fun dayRateMonthAddsUnitsAndLeavesCancelledOut() {
        val jobs = listOf(
            DayRate.job(d(1), 1.0, 200_000, "A"),
            DayRate.job(d(2), 1.5, 200_000, "A"),
            DayRate.job(d(4), 1.0, 200_000, "A").copy(status = JobStatus.CANCELLED),
            Job(date = d(5), title = "촬영", amount = 500_000),
        )
        val m = DayRate.month(nov, jobs)
        assertEquals(2.5, m.units, 0.0)
        assertEquals(500_000, m.amount)
        assertEquals(2, m.days)
    }

    @Test
    fun clientsComeMostUsedFirst() {
        val jobs = listOf(Job(date = d(1), title = "", client = "B"), Job(date = d(2), title = "", client = "A"), Job(date = d(3), title = "", client = "A "), Job(date = d(4), title = "", client = ""))
        assertEquals(listOf("A", "B"), DayRate.clients(jobs))
    }

    @Test
    fun cancelledJobsCountOnlyTheDepositTheyKept() {
        val done = Job(id = 1, date = d(2), title = "웨딩", client = "가", amount = 500_000, balancePaid = true)
        val cancelled = Job(id = 2, date = d(3), title = "돌잔치", client = "나", amount = 300_000, deposit = 100_000, depositPaid = true, status = JobStatus.CANCELLED)
        val cancelledUnpaid = Job(id = 3, date = d(4), title = "행사", client = "나", amount = 200_000, status = JobStatus.CANCELLED)
        val jobs = listOf(done, cancelled, cancelledUnpaid)
        assertEquals(0, cancelled.unpaid)
        assertEquals(100_000, cancelled.earned)
        assertFalse(cancelled.fullyPaid)
        assertTrue(done.fullyPaid)
        val m = MoneyBook(jobs = jobs).month(nov)
        assertEquals(600_000, m.jobEarned)
        assertEquals(600_000, m.jobReceived)
        assertEquals(0, m.jobOwed)
        assertEquals(1, m.jobCount)
        assertEquals(listOf("가"), WorkStats.clients(nov, jobs).map { it.client })
        assertTrue(Receivables.owed(emptyList(), jobs, d(30)).isEmpty())
        val alerts = MoneyAlerts.all(jobs.map { it.copy(start = java.time.LocalTime.of(9, 0), remindMinutes = 60) }, emptyList(), emptyList(), d(1))
        assertTrue(alerts.none { it.key.endsWith("-2") || it.key == "job-2" || it.key == "job-3" || it.key.startsWith("unpaid-2") || it.key.startsWith("unpaid-3") })
    }

    @Test
    fun unpaidAlertsFollowThePromisedDayWhenSet() {
        val job = Job(id = 5, date = d(2), title = "타일", amount = 300_000)
        val plain = MoneyAlerts.all(listOf(job), emptyList(), emptyList(), d(1)).filter { it.key.startsWith("unpaid") }
        assertEquals(listOf(LocalDateTime.of(2026, 11, 3, 10, 0), LocalDateTime.of(2026, 11, 9, 10, 0)), plain.map { it.at })
        val promised = MoneyAlerts.all(listOf(job.copy(due = d(15))), emptyList(), emptyList(), d(1)).filter { it.key.startsWith("unpaid") }
        assertEquals(listOf(LocalDateTime.of(2026, 11, 16, 10, 0), LocalDateTime.of(2026, 11, 22, 10, 0)), promised.map { it.at })
        assertEquals("입금 약속일이 지났어요", promised.first().title)
    }

    @Test
    fun clientColourIsStable() {
        assertEquals(ClientColors.index("김반장", 8), ClientColors.index(" 김반장 ", 8))
        assertEquals(0, ClientColors.index("", 8))
        assertTrue(ClientColors.index("한빛인테리어", 8) in 0 until 8)
    }

    @Test
    fun statementListsOneClientsMonthWithWithholdingAndPaid() {
        val jobs = listOf(
            Job(id = 1, date = d(3), title = "욕실", client = "한빛", amount = 1_000_000, withholding = true, balancePaid = true),
            Job(id = 2, date = d(10), title = "주방", client = "한빛", amount = 500_000),
            Job(id = 3, date = d(12), title = "취소분", client = "한빛", amount = 500_000, status = JobStatus.CANCELLED),
            Job(id = 4, date = d(12), title = "남의 일", client = "다른", amount = 900_000),
            Job(id = 5, date = d(2, 10), title = "지난달", client = "한빛", amount = 900_000),
        )
        val s = JobStatement.of("한빛", nov, jobs)
        assertEquals(listOf("욕실", "주방"), s.lines.map { it.title })
        assertEquals(1_500_000, s.amount)
        assertEquals(33_000, s.withheld)
        assertEquals(1_467_000, s.payable)
        assertEquals(967_000, s.received)
        assertEquals(500_000, s.unpaid)
    }

    @Test
    fun depositMatchesAJobPartOrAReceivable() {
        val job = Job(id = 1, date = d(1), title = "웨딩", client = "가", amount = 500_000, deposit = 100_000)
        val r = Receivable(id = 9, who = "나", amount = 70_000, date = d(2))
        fun income(id: Long, amount: Long, day: Int, source: MoneySource = MoneySource.NOTIFICATION) =
            MoneyEntry(id = id, date = d(day), kind = MoneyKind.INCOME, amount = amount, category = "기타", source = source)
        val t = DepositMatch.target(100_000, listOf(job), listOf(r)) as DepositMatch.Target.ForJob
        assertEquals(DepositMatch.Part.DEPOSIT, t.part)
        assertTrue(DepositMatch.paid(job, t.part).depositPaid)
        val withDeposit = job.copy(depositPaid = true)
        assertEquals(DepositMatch.Part.BALANCE, (DepositMatch.target(400_000, listOf(withDeposit), emptyList()) as DepositMatch.Target.ForJob).part)
        assertEquals(DepositMatch.Part.ALL, (DepositMatch.target(500_000, listOf(job), emptyList()) as DepositMatch.Target.ForJob).part)
        assertTrue(DepositMatch.target(70_000, listOf(job), listOf(r)) is DepositMatch.Target.ForReceivable)
        assertNull(DepositMatch.target(123_000, listOf(job), listOf(r)))
        val entries = listOf(income(1, 100_000, 10), income(2, 100_000, 11), income(3, 70_000, 12, MoneySource.MANUAL), income(4, 70_000, 5), income(5, 70_000, 1, MoneySource.NOTIFICATION).copy(date = d(1, 10)))
        val found = DepositMatch.pending(entries, listOf(job), listOf(r), notMatched = setOf(4L), today = d(12))
        // One offer per job: the newest deposit; a manual line, an answered one and an old one are not asked about.
        assertEquals(listOf(2L), found.map { it.entry.id })
        assertTrue(DepositMatch.pending(entries, listOf(job.copy(status = JobStatus.CANCELLED)), emptyList(), emptySet(), d(12)).isEmpty())
        // 맞아요: a deposit into an account stays as money moved into it, out of the month's income.
        val bank = com.bamnat.core.money.MoneyAccount(id = 3, name = "통장", type = com.bamnat.core.money.AccountType.BANK)
        val kept = DepositMatch.kept(income(2, 100_000, 11).copy(accountId = 3), listOf(bank), "웨딩")!!
        assertEquals(MoneyKind.TRANSFER, kept.kind)
        assertEquals(3L, kept.toAccountId)
        assertEquals(100_000L, com.bamnat.core.money.MoneyAccounts.balances(listOf(bank), listOf(kept))[3])
        assertEquals(0L, MoneyBook(listOf(kept)).month(YearMonth.of(2026, 11)).otherIncome)
        assertNull(DepositMatch.kept(income(2, 100_000, 11), listOf(bank), "웨딩"))
    }

    @Test
    fun yearTotalsAndWorkedDays() {
        val jobs = listOf(
            Job(date = d(3), endDate = d(5), title = "3일 공사", amount = 900_000, withholding = true, costs = 100_000),
            Job(date = d(5), title = "같은 날", amount = 100_000),
            Job(date = d(20), title = "취소", amount = 100_000, status = JobStatus.CANCELLED),
            Job(date = LocalDate.of(2025, 12, 30), title = "작년", amount = 100_000),
        )
        val spent = listOf(MoneyEntry(date = d(4), kind = MoneyKind.EXPENSE, amount = 50_000, category = "유류비"))
        val y = FieldYear.totals(2026, jobs, spent)
        assertEquals(29_700, y.withheld)
        assertEquals(900_000 - 29_700 + 100_000, y.earned)
        assertEquals(150_000, y.expenses)
        assertEquals(2, y.jobs)
        assertEquals(3, FieldYear.workedDays(jobs, nov.atDay(1), nov.atEndOfMonth()))
        assertEquals(1, FieldYear.workedDays(jobs, d(5), d(30)))
    }

    @Test
    fun jobsCsvHasStatusUnitsAndDue() {
        val csv = WorkCsv.jobs(listOf(DayRate.job(d(3), 1.5, 200_000, "=위험").copy(due = d(30))))
        val row = csv.lines()[1]
        assertTrue(row.startsWith("2026-11-03,2026-11-03,1.5공,'=위험,,확정,1.5,300000"))
        assertTrue(row.contains("2026-11-30"))
    }
}
