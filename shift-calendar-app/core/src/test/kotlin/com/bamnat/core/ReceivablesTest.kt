package com.bamnat.core

import com.bamnat.core.money.Job
import com.bamnat.core.money.JobPayments
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.Owed
import com.bamnat.core.money.OwedFilter
import com.bamnat.core.money.OwedSort
import com.bamnat.core.money.Receivable
import com.bamnat.core.money.ReceivableAlerts
import com.bamnat.core.money.ReceivableKind
import com.bamnat.core.money.ReceivablePayment
import com.bamnat.core.money.ReceivableStatus
import com.bamnat.core.money.Receivables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [RECV-01][RECV-02][RECV-03] 미수금: what is left, how late, who owes most, and when to remind. */
class ReceivablesTest {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private val today = d(20)

    private fun r(id: Long, who: String, amount: Long, date: LocalDate, due: LocalDate? = null, vararg paid: Long) =
        Receivable(id = id, who = who, amount = amount, date = date, due = due,
            payments = paid.mapIndexed { i, a -> ReceivablePayment(id = i + 1L, receivableId = id, date = date.plusDays(i + 1L), amount = a) })

    @Test
    fun partPaymentsLowerWhatIsLeftAndNeverGoBelowZero() {
        val x = r(1, "가나다화학", 500_000, d(1), null, 200_000)
        assertEquals(300_000, x.left)
        assertEquals(ReceivableStatus.PARTLY, x.status(today))
        assertEquals(300_000, Receivables.clampPayment(x, 900_000))
        val paid = r(2, "가나다화학", 500_000, d(1), null, 300_000, 200_000)
        assertEquals(0, paid.left)
        assertEquals(ReceivableStatus.PAID, paid.status(today))
    }

    @Test
    fun aClosedDebtIsNoLongerOwed() {
        val x = r(1, "민지", 100_000, d(1)).copy(closedOn = d(10))
        assertEquals(0, x.left)
        assertEquals(ReceivableStatus.CLOSED, x.status(today))
        assertTrue(Receivables.owed(listOf(x), emptyList(), today).isEmpty())
    }

    @Test
    fun statusFollowsThePromisedDay() {
        assertEquals(ReceivableStatus.OVERDUE, r(1, "a", 10_000, d(1), d(19)).status(today))
        assertEquals(ReceivableStatus.DUE_SOON, r(1, "a", 10_000, d(1), d(23)).status(today))
        assertEquals(ReceivableStatus.DUE_SOON, r(1, "a", 10_000, d(1), d(20)).status(today))
        assertEquals(ReceivableStatus.OPEN, r(1, "a", 10_000, d(1), d(24)).status(today))
        assertEquals(1, r(1, "a", 10_000, d(1), d(19)).daysWaiting(today))
        assertEquals(19, r(1, "a", 10_000, d(1)).daysWaiting(today))
    }

    @Test
    fun theListPutsOverdueFirstAndTakesInUnpaidJobs() {
        val late = r(1, "라마바전자", 300_000, d(2), d(10))
        val soon = r(2, "사아자물류", 50_000, d(15), d(21))
        val open = r(3, "준호", 80_000, d(5))
        val job = Job(id = 9, date = d(3), title = "욕실 타일", client = "예시정밀", amount = 1_000_000, deposit = 300_000, depositPaid = true)
        val future = Job(id = 10, date = d(25), title = "도배", amount = 500_000)
        val owed = Receivables.owed(listOf(open, soon, late), listOf(job, future), today)
        assertEquals(listOf("라마바전자", "사아자물류", "예시정밀", "준호"), owed.map { it.who })
        assertTrue(owed[2] is Owed.FromJob)
        assertEquals(700_000, owed[2].left)
    }

    @Test
    fun summaryAddsUpByPersonAndByAge() {
        val list = listOf(
            r(1, "가나다화학", 300_000, d(2), d(10)),
            r(2, "가나다화학", 100_000, LocalDate.of(2026, 8, 1)),
            r(3, " ", 50_000, d(18), null, 10_000),
        )
        val s = Receivables.summary(list, emptyList(), today)
        assertEquals(440_000, s.total)
        assertEquals(3, s.count)
        assertEquals(300_000, s.overdue)
        assertEquals(1, s.overdueCount)
        assertEquals(listOf("가나다화학" to 400_000L, Receivables.NO_NAME to 40_000L), s.byWho)
        // 10 days past due and 2 days old in the first bucket; 111 days old in the last.
        assertEquals(listOf(340_000L, 0L, 0L, 100_000L), s.aging)
        assertEquals(10_000, s.receivedThisMonth)
    }

    @Test
    fun onlyIncomeKindsWriteALedgerLine() {
        val work = Receivable(who = "예시정밀", title = "3일치 일당", amount = 450_000, date = d(1), kind = ReceivableKind.WORK)
        val entry = Receivables.incomeEntry(work, 150_000, d(20))!!
        assertEquals(MoneyKind.INCOME, entry.kind)
        assertEquals(150_000, entry.amount)
        assertEquals("부수입", entry.category)
        assertEquals("예시정밀", entry.merchant)
        // A loan coming back is not income; a credit sale is usually already in that day's sales.
        assertNull(Receivables.incomeEntry(work.copy(kind = ReceivableKind.LOAN, countAsIncome = false), 1, d(20)))
        assertNull(Receivables.incomeEntry(Receivable(who = "x", amount = 1, date = d(1), kind = ReceivableKind.SALE), 1, d(20)))
        assertTrue(ReceivableKind.WORK.incomeByDefault && !ReceivableKind.LOAN.incomeByDefault && !ReceivableKind.SALE.incomeByDefault)
    }

    @Test
    fun remindersComeOnThePromisedDayAndAfter() {
        val x = r(4, "사아자물류", 200_000, d(1), d(15))
        assertEquals(listOf(d(15), d(18), d(22)), ReceivableAlerts.of(x).map { it.first })
        assertEquals(listOf(LocalDate.of(2026, 12, 1)), ReceivableAlerts.of(r(5, "a", 1, d(1))).map { it.first })
        assertTrue(ReceivableAlerts.of(r(6, "a", 100, d(1), d(15), 100)).isEmpty())

        val alerts = MoneyAlerts.all(emptyList(), emptyList(), emptyList(), today, listOf(x.copy(title = "10월 납품")))
        assertEquals(listOf("owed-4-0", "owed-4-1", "owed-4-2"), alerts.map { it.key })
        assertEquals("10월 납품 (사아자물류) · 200,000원", alerts.first().text)
        val next = MoneyAlerts.next(emptyList(), emptyList(), emptyList(), LocalDateTime.of(2026, 11, 20, 12, 0), listOf(x))
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 0), next.single().at)
    }

    /** [RECV-05] Chips, search and order on the 받을 돈 list. */
    @Test
    fun theListNarrowsByChipSearchAndOrder() {
        val late = r(1, "라마바전자", 300_000, d(2), d(10))
        val soon = r(2, "민지", 50_000, d(5), d(21)).copy(note = "점심값")
        val part = r(3, "가나다화학", 900_000, d(1), null, 100_000)
        val job = Job(id = 9, date = d(12), title = "욕실", client = "김사장", place = "역삼동", amount = 400_000)
        val owed = Receivables.owed(listOf(late, soon, part), listOf(job), today)
        val ids = { list: List<Owed> -> list.map { (it as? Owed.Written)?.r?.id ?: -(it as Owed.FromJob).job.id } }
        assertEquals(listOf(1L), ids(Receivables.narrow(owed, today, OwedFilter.OVERDUE)))
        assertEquals(listOf(2L), ids(Receivables.narrow(owed, today, OwedFilter.DUE_SOON)))
        assertEquals(listOf(3L), ids(Receivables.narrow(owed, today, OwedFilter.PARTLY)))
        assertEquals(listOf(-9L), ids(Receivables.narrow(owed, today, OwedFilter.JOBS)))
        assertEquals(3, Receivables.narrow(owed, today, OwedFilter.WRITTEN).size)
        // Search looks at who, what and notes, place included.
        assertEquals(listOf(2L), ids(Receivables.narrow(owed, today, query = "점심")))
        assertEquals(listOf(-9L), ids(Receivables.narrow(owed, today, query = "역삼")))
        assertEquals(listOf(1L), ids(Receivables.narrow(owed, today, query = " 라마바 ")))
        assertEquals(listOf(3L, -9L, 1L, 2L), ids(Receivables.narrow(owed, today, sort = OwedSort.AMOUNT)))
        assertEquals(listOf(3L, 1L, -9L, 2L), ids(Receivables.narrow(owed, today, sort = OwedSort.OLDEST)))
        val counts = Receivables.counts(owed, today)
        assertEquals(4, counts[OwedFilter.ALL])
        assertEquals(1, counts[OwedFilter.OVERDUE])
        assertEquals(listOf(-9L), ids(Receivables.of(owed, "김사장")))
    }

    /** [RECV-05] The note sent to whoever owes says what for, how much, and the promised day. */
    @Test
    fun aReminderNoteNamesWhatHowMuchAndWhen() {
        val late = Owed.Written(r(1, "라마바전자", 300_000, d(2), d(10)).copy(title = "10월 납품"))
        val text = Receivables.reminder(late, today)
        assertTrue(text, text.startsWith("라마바전자 님, 안녕하세요."))
        assertTrue(text, text.contains("10월 납품"))
        assertTrue(text, text.contains("11월 10일에 주시기로 한 300,000원"))
        val job = Owed.FromJob(Job(id = 9, date = d(12), title = "욕실", amount = 400_000, deposit = 100_000, depositPaid = true))
        val jobText = Receivables.reminder(job, today)
        assertTrue(jobText, jobText.startsWith("안녕하세요."))
        assertTrue(jobText, jobText.contains("11월 12일 욕실") && jobText.contains("300,000원"))
    }

    /** [RECV-05] Ticking a job paid from the list sets the same switches as the job editor. */
    @Test
    fun aJobIsPaidFromTheList() {
        val job = Job(id = 9, date = d(12), title = "욕실", amount = 400_000, deposit = 100_000)
        assertEquals(300_000, JobPayments.deposit(job)!!.unpaid)
        assertEquals(0, JobPayments.all(job).unpaid)
        assertNull(JobPayments.deposit(job.copy(depositPaid = true)))
        assertNull(JobPayments.deposit(job.copy(deposit = 0)))
        // No deposit: only the one 돈 받음 switch.
        val plain = JobPayments.all(job.copy(deposit = 0))
        assertTrue(plain.balancePaid && !plain.depositPaid)
    }
}
