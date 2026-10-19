package com.bamnat.core

import com.bamnat.core.money.AccountType
import com.bamnat.core.money.BudgetLevel
import com.bamnat.core.money.Budgets
import com.bamnat.core.money.CardCycle
import com.bamnat.core.money.CategoryEdit
import com.bamnat.core.money.EntryPart
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.Job
import com.bamnat.core.money.Ledger
import com.bamnat.core.money.LedgerCsv
import com.bamnat.core.money.LedgerFilter
import com.bamnat.core.money.LedgerPeriod
import com.bamnat.core.money.LedgerSort
import com.bamnat.core.money.MoneyAccount
import com.bamnat.core.money.MoneyAccounts
import com.bamnat.core.money.MoneyAlerts
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyBudget
import com.bamnat.core.money.MoneyCalculator
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.Recurring
import com.bamnat.core.money.Repeat
import com.bamnat.core.money.Reports
import com.bamnat.core.money.UpcomingPayments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** [LEDG-06]..[LEDG-14] Budgets, search, accounts and card bills, repeating lines, reports, splits, CSV and categories. */
class LedgerTest {
    private val nov = YearMonth.of(2026, 11)
    private fun d(day: Int) = nov.atDay(day)
    private fun spend(day: Int, amount: Long, category: String, merchant: String = "", id: Long = 0, account: Long? = null) =
        MoneyEntry(id = id, date = d(day), kind = MoneyKind.EXPENSE, amount = amount, category = category, merchant = merchant, accountId = account)

    // ---- [LEDG-11] Split payments and tags ----

    @Test fun aSplitPaymentCountsUnderEachCategory() {
        val mart = spend(3, 68_400, "장보기·쇼핑", "이마트").copy(parts = listOf(EntryPart("장보기·쇼핑", 50_000), EntryPart("생활", 18_400)))
        assertEquals(listOf("장보기·쇼핑" to 50_000L, "생활" to 18_400L), mart.shares())
        val m = MoneyCalculator.month(nov, listOf(mart, spend(4, 10_000, "생활")), emptyList(), emptyList())
        assertEquals(78_400L, m.expenses)
        assertEquals(listOf("장보기·쇼핑" to 50_000L, "생활" to 28_400L), m.expenseByCategory.map { it.category to it.amount })
        // Parts that no longer add up leave the rest with the main category; a cancellation is negative in each part.
        assertEquals(listOf("장보기·쇼핑" to 60_000L, "생활" to 18_400L), mart.copy(amount = 78_400).shares())
        assertEquals(listOf("장보기·쇼핑" to -50_000L, "생활" to -18_400L), mart.copy(amount = -68_400).shares())
        assertEquals(listOf("여행", "회식", "출장"), Ledger.parseTags("#여행 회식, #출장 여행"))
    }

    // ---- [LEDG-14] The user's own categories ----

    @Test fun ownCategoriesComeInTheirOrderAndOtherStaysLast() {
        val edits = listOf(
            CategoryEdit(MoneyKind.EXPENSE, "술", 0), CategoryEdit(MoneyKind.EXPENSE, "식비", 1),
            CategoryEdit(MoneyKind.EXPENSE, "카페", 2, hidden = true), CategoryEdit(MoneyKind.EXPENSE, "기타", 3, hidden = true),
        )
        val list = MoneyCategories.of(MoneyKind.EXPENSE, edits)
        assertEquals(listOf("술", "식비", "편의점"), list.take(3))
        assertFalse("카페" in list)
        assertEquals("기타", list.last())
        assertEquals(MoneyCategories.income, MoneyCategories.of(MoneyKind.INCOME, edits))
        assertEquals("술 안주", MoneyCategories.clean("  술,\t안주 "))
    }

    // ---- [LEDG-08] Accounts, transfers and card bills ----

    private val bank = MoneyAccount(id = 1, name = "월급 통장", type = AccountType.BANK, opening = 1_000_000)
    private val check = MoneyAccount(id = 2, name = "체크카드", type = AccountType.CHECK, last4 = "5678", payFromId = 1)
    private val credit = MoneyAccount(id = 3, name = "신한카드", type = AccountType.CREDIT, last4 = "1234", payDay = 14, payFromId = 1)
    private val accounts = listOf(bank, check, credit)

    @Test fun balancesFollowChecksAndTransfersButNotCreditSpending() {
        val lines = listOf(
            spend(1, 30_000, "식비", account = 2),
            spend(2, 50_000, "식비").copy(cardLast4 = "1234"),
            MoneyEntry(date = d(3), kind = MoneyKind.INCOME, amount = 2_000_000, category = "급여", accountId = 1),
            MoneyEntry(date = d(14), kind = MoneyKind.TRANSFER, amount = 50_000, category = "카드값", accountId = 1, toAccountId = 3),
        )
        // A text's last four digits find the card without an account picked.
        assertEquals(3L, MoneyAccounts.of(lines[1], accounts))
        assertEquals(2L, MoneyAccounts.match("5678", accounts))
        assertNull(MoneyAccounts.match("0000", accounts))
        val b = MoneyAccounts.balances(accounts, lines)
        assertEquals(1_000_000 - 30_000 + 2_000_000 - 50_000L, b[1])
        assertNull(b[3])
    }

    @Test fun aCardBillCoversItsCycleAndSpreadsInstallments() {
        // 결제일 14일, 마감 말일: November's bill is October 1..31.
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), CardCycle.period(credit, nov))
        assertEquals(YearMonth.of(2026, 12), CardCycle.billMonth(credit, d(30)))
        // 결제일 25일, 마감 10일: the same month's 10th closes it.
        val early = credit.copy(payDay = 25, closeDay = 10)
        assertEquals(LocalDate.of(2026, 10, 11) to LocalDate.of(2026, 11, 10), CardCycle.period(early, nov))
        assertEquals(nov, CardCycle.billMonth(early, d(10)))
        assertEquals(YearMonth.of(2026, 12), CardCycle.billMonth(early, d(11)))
        val oct = { day: Int, amount: Long, n: Int -> MoneyEntry(date = LocalDate.of(2026, 10, day), kind = MoneyKind.EXPENSE, amount = amount,
            category = "식비", accountId = 3, installments = n) }
        val lines = listOf(oct(5, 40_000, 1), oct(20, 100_000, 3), oct(31, -10_000, 1), spend(2, 7_000, "카페", account = 3))
        val bill = CardCycle.bill(credit, nov, lines, accounts)
        // 40,000 + the first of three (33,334 with the remainder) - 10,000 cancelled.
        assertEquals(40_000 + 33_334 - 10_000L, bill.amount)
        assertEquals(LocalDate.of(2026, 11, 14), bill.payDate)
        assertEquals(33_333 + 7_000L, CardCycle.bill(credit, YearMonth.of(2026, 12), lines, accounts).amount)
        assertEquals(33_333L, CardCycle.bill(credit, YearMonth.of(2027, 1), lines, accounts).amount)
        // The bill is due soon, listed once even with an old 카드값 fixed cost of the same name, and gone once paid.
        val old = FixedCost(id = 9, name = "신한카드", amount = 600_000, day = 14, kind = MoneyKind.TRANSFER, category = "카드값")
        val up = UpcomingPayments.within(listOf(old), lines, d(10), 7, accounts)
        assertEquals(listOf(3L), up.map { it.cardId })
        assertEquals(63_334L, up.single().cost.amount)
        val paid = UpcomingPayments.entry(up.single(), d(14))
        assertEquals(3L, paid.toAccountId)
        assertEquals(1L, paid.accountId)
        assertTrue(CardCycle.paid(credit, nov, lines + paid))
        assertTrue(UpcomingPayments.within(listOf(old), lines + paid, d(10), 7, accounts).isEmpty())
        val alerts = MoneyAlerts.all(emptyList(), emptyList(), lines, d(10), accounts = accounts)
        assertEquals(listOf(LocalDateTime.of(2026, 11, 11, 9, 0), LocalDateTime.of(2026, 11, 14, 9, 0)),
            alerts.filter { it.key.startsWith("card-3-2026-11-14") }.map { it.at })
    }

    @Test fun parsedInstallmentsAreKept() {
        val p = (PaymentParser.parse("현대카드(3456)승인 홍*동 45,000원 3개월 11/14 19:20 쿠팡", LocalDateTime.of(2026, 11, 15, 9, 0)) as PaymentParser.Result.Ok).parsed
        assertEquals(3, PaymentParser.toEntry(p, d(15), MoneySource.PASTE)!!.installments)
    }

    // ---- [LEDG-09] Repeating income and spending ----

    @Test fun repeatingLinesFallOnTheirDaysAndWriteThemselves() {
        val weekly = FixedCost(id = 1, name = "주급", amount = 300_000, day = 5, kind = MoneyKind.INCOME, category = "급여", repeat = Repeat.WEEK, auto = true,
            start = d(1))
        assertEquals(listOf(d(6), d(13), d(20), d(27)), weekly.occurrences(d(1), d(30)))
        assertEquals("매주 금요일", weekly.schedule)
        val yearly = FixedCost(id = 2, name = "자동차보험", amount = 900_000, day = 31, repeat = Repeat.YEAR, monthOfYear = 2, category = "보험")
        assertEquals(listOf(LocalDate.of(2027, 2, 28)), yearly.occurrences(d(1), LocalDate.of(2027, 12, 31)))
        assertEquals("매년 2월 말일", yearly.schedule)
        // Up to the 15th: the 6th and 13th are written, never before the first day.
        val due = Recurring.due(listOf(weekly), emptyList(), d(15))
        assertEquals(listOf(d(6), d(13)), due.map { it.date })
        assertEquals(listOf(1L, 1L), due.map { it.fixedId })
        assertTrue(Recurring.due(listOf(weekly), due, d(15)).isEmpty())
        // A line written by hand a day late still counts for that week, even after a rename.
        val late = UpcomingPayments.entry(weekly, d(14))
        assertTrue(UpcomingPayments.recorded(weekly.copy(name = "알바 주급"), d(13), listOf(late)))
        // Not set to write itself: nothing is written, it is reminded instead; income on the day only.
        assertTrue(Recurring.due(listOf(weekly.copy(auto = false)), emptyList(), d(15)).isEmpty())
        val alerts = MoneyAlerts.all(emptyList(), listOf(weekly.copy(auto = false)), emptyList(), d(15))
        assertEquals(listOf("오늘 들어올 돈"), alerts.map { it.title }.distinct())
        // Income is not left to spend.
        assertEquals(0L, UpcomingPayments.leftThisMonth(listOf(weekly), emptyList(), d(15)))
        val rent = FixedCost(id = 3, name = "월세", amount = 450_000, day = 25)
        assertEquals(450_000L, UpcomingPayments.leftThisMonth(listOf(rent, weekly), emptyList(), d(15)))
    }

    // ---- [LEDG-06] Budgets ----

    @Test fun budgetsShowWhatIsLeftPerDayAndWhereTheMonthIsHeading() {
        val budgets = listOf(MoneyBudget(MoneyBudget.TOTAL, 1_000_000), MoneyBudget("식비", 300_000), MoneyBudget("카페", 50_000))
        val lines = listOf(spend(1, 200_000, "식비"), spend(5, 60_000, "카페"), spend(10, 40_000, "식비"),
            spend(10, 100_000, "주거").copy(source = MoneySource.FIXED))
        val s = Budgets.status(budgets, lines, nov, d(10), fixedLeft = 150_000)
        assertEquals(400_000L, s.total!!.spent)
        assertEquals(21, s.daysLeft)
        // (1,000,000 - 260,000 spent before today - 150,000 still to leave) / 21 days.
        assertEquals((1_000_000L - 260_000 - 150_000) / 21, s.perDay)
        assertEquals(s.perDay!! - 140_000, s.todayLeft)
        assertEquals(listOf("카페", "식비"), s.categories.map { it.category })
        assertEquals(BudgetLevel.OVER, s.categories[0].level)
        assertEquals(BudgetLevel.NEAR, s.categories[1].level)
        // 240,000 of 식비 in 10 days heads for 720,000 by the 30th.
        assertTrue(s.categories[1].headedOver)
        assertEquals(240_000L + 240_000 * 20 / 10, s.categories[1].forecast)
        // Past months have no per-day share; without a whole budget the categories add up to one.
        assertNull(Budgets.status(budgets, lines, nov, d(10).plusMonths(1)).perDay)
        assertEquals(350_000L, Budgets.status(budgets.drop(1), lines, nov, d(10)).total!!.budget)
        // A new 식비 line that goes past 300,000 is said once.
        val crossed = Budgets.crossedBy(spend(11, 70_000, "식비"), budgets, lines, d(11))
        assertEquals(listOf("식비"), crossed.map { it.category })
        assertTrue(Budgets.crossedBy(spend(11, 1_000, "카페"), budgets, lines, d(11)).isEmpty())
    }

    // ---- [LEDG-07] Search, filters and order ----

    @Test fun searchFiltersSortsAndAddsUp() {
        val lines = listOf(
            spend(1, 4_500, "카페", "스타벅스", id = 1).copy(tags = listOf("출근")),
            spend(2, 32_000, "식비", "김밥천국", id = 2, account = 3).copy(memo = "팀 점심"),
            spend(3, 68_400, "장보기·쇼핑", "이마트", id = 3).copy(parts = listOf(EntryPart("장보기·쇼핑", 50_000), EntryPart("생활", 18_400))),
            MoneyEntry(id = 4, date = d(3), kind = MoneyKind.INCOME, amount = 150_000, category = "부수입", merchant = "중고 거래"),
            MoneyEntry(id = 5, date = d(4), kind = MoneyKind.TRANSFER, amount = 300_000, category = "저축", accountId = 1, toAccountId = 2),
            spend(1, 4_500, "카페", "메가커피", id = 6).copy(date = d(1).minusMonths(1)),
        )
        val (from, to) = LedgerPeriod.MONTH.range(nov)
        val month = LedgerFilter(from = from, to = to)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), Ledger.search(lines, month).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), Ledger.search(lines, month, LedgerSort.OLDEST).map { it.id })
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), Ledger.search(lines, month, LedgerSort.LARGEST).map { it.id })
        // Words: the memo, digits of the amount with or without commas, a #tag, an account's name.
        assertEquals(listOf(2L), Ledger.search(lines, month.copy(query = "점심")).map { it.id })
        assertEquals(listOf(3L), Ledger.search(lines, month.copy(query = "68,400원")).map { it.id })
        assertEquals(listOf(1L), Ledger.search(lines, month.copy(query = "#출")).map { it.id })
        assertEquals(listOf(2L), Ledger.search(lines, month.copy(query = "신한"), accounts = accounts).map { it.id })
        // A part of a split payment is found by its category, and only that part is added up.
        val life = Ledger.search(lines, month.copy(categories = setOf("생활")))
        assertEquals(listOf(3L), life.map { it.id })
        assertEquals(18_400L, Ledger.totals(life, month.copy(categories = setOf("생활"))).expense)
        // Kinds, amounts, accounts (a transfer shows for either end) and lines with no account.
        assertEquals(listOf(4L), Ledger.search(lines, month.copy(kinds = setOf(MoneyKind.INCOME))).map { it.id })
        assertEquals(listOf(3L, 2L), Ledger.search(lines, month.copy(minAmount = 10_000, maxAmount = 100_000)).map { it.id })
        assertEquals(listOf(5L), Ledger.search(lines, month.copy(accounts = setOf(2L)), accounts = accounts).map { it.id })
        assertEquals(listOf(4L, 3L, 1L), Ledger.search(lines, month.copy(accounts = setOf(LedgerFilter.NO_ACCOUNT)), accounts = accounts).map { it.id })
        assertEquals(2, month.copy(kinds = setOf(MoneyKind.EXPENSE), minAmount = 1).count)
        val all = Ledger.search(lines, month)
        val t = Ledger.totals(all)
        assertEquals(104_900L, t.expense)
        assertEquals(150_000L, t.income)
        assertEquals(300_000L, t.transfer)
        val days = Ledger.days(all)
        assertEquals(listOf(d(4), d(3), d(2), d(1)), days.map { it.date })
        assertEquals(68_400L to 150_000L, days[1].expense to days[1].income)
    }

    @Test fun quickAddRemembersPlacesAndTheLastCategory() {
        val lines = listOf(
            spend(1, 4_500, "카페", "스타벅스", id = 1), spend(5, 5_000, "카페", "스타벅스", id = 2, account = 3),
            spend(2, 3_000, "카페", "스타벅스 역삼점", id = 3), spend(6, 12_000, "식비", "김밥천국", id = 4),
        )
        val s = Ledger.suggest(lines, "스타", MoneyKind.EXPENSE)
        assertEquals(listOf("스타벅스", "스타벅스 역삼점"), s.map { it.merchant })
        assertEquals(5_000L, s[0].amount)
        assertEquals(3L, s[0].accountId)
        assertEquals(2, s[0].count)
        assertEquals("식비", Ledger.lastCategory(lines, MoneyKind.EXPENSE))
        assertNull(Ledger.lastCategory(lines, MoneyKind.INCOME))
        val copy = Ledger.duplicate(lines[1].copy(source = MoneySource.NOTIFICATION, time = LocalTime.NOON, reviewed = false), d(20))
        assertEquals(0L, copy.id)
        assertEquals(MoneySource.MANUAL to true, copy.source to copy.reviewed)
        assertNull(copy.time)
        assertEquals(emptyList<EntryPart>(), Ledger.recategorize(lines[2].copy(parts = listOf(EntryPart("카페", 1))), "식비").parts)
    }

    // ---- [LEDG-12] CSV ----

    @Test fun csvHasOneRowPerCategoryAndNoFormulas() {
        val lines = listOf(
            spend(3, 68_400, "장보기·쇼핑", "이마트", account = 3).copy(parts = listOf(EntryPart("장보기·쇼핑", 50_000), EntryPart("생활", 18_400)),
                tags = listOf("장보기"), installments = 2),
            spend(4, 1_000, "기타", "=HYPERLINK(\"x\")").copy(memo = "쉼표, 들어감"),
        )
        val csv = LedgerCsv.write(lines, accounts)
        assertTrue(csv.startsWith("﻿날짜,시각,구분,분류,금액"))
        val rows = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(4, rows.size)
        assertEquals("2026-11-03,,지출,장보기·쇼핑,50000,이마트 (나눔 1/2),신한카드 (1234),,,#장보기,2개월,직접", rows[1])
        assertTrue(rows[2].startsWith("2026-11-03,,지출,생활,18400,"))
        assertEquals("2026-11-04,,지출,기타,1000,\"'=HYPERLINK(\"\"x\"\")\",,,\"쉼표, 들어감\",,,직접", rows[3])
        assertEquals("밤낮_가계부_2026-11.csv", LedgerCsv.fileName(d(1), d(30)))
        assertEquals("밤낮_가계부_전체.csv", LedgerCsv.fileName(null, null))
    }

    // ---- [LEDG-10] Reports ----

    @Test fun monthReportComparesWithTheMonthBefore() {
        val oct = YearMonth.of(2026, 10)
        val lines = listOf(
            spend(1, 10_000, "식비").copy(tags = listOf("회식")), spend(3, 20_000, "카페"), spend(3, 5_000, "식비", account = 3),
            MoneyEntry(date = oct.atDay(2), kind = MoneyKind.EXPENSE, amount = 40_000, category = "식비"),
            MoneyEntry(date = oct.atDay(20), kind = MoneyKind.EXPENSE, amount = 1_000, category = "카페"),
        )
        val jobs = listOf(Job(date = d(2), title = "도배", amount = 300_000, costs = 50_000))
        val book = MoneyBook(entries = lines, jobs = jobs, accounts = accounts)
        val r = Reports.month(book.month(nov), book.month(oct), book, d(4))
        assertEquals(85_000L, r.spent)
        assertEquals(listOf(Reports.JOB_COSTS, "카페", "식비"), r.categories.map { it.category })
        assertEquals(40_000L, r.categories.first { it.category == "식비" }.previous)
        assertEquals(listOf(10_000L, 60_000L, 85_000L, 85_000L), r.cumulative)
        assertEquals(40_000L, r.previousSameDay)
        assertEquals(1, r.noSpendDays)
        // Accounts and tags count the ledger's own lines.
        assertEquals(listOf<Pair<Long?, Long>>(null to 30_000L, 3L to 5_000L), r.byAccount)
        assertEquals(listOf("회식" to 10_000L), r.byTag)
        val year = Reports.year(2026, listOf(book.month(oct), book.month(nov)))
        assertEquals(126_000L, year.spent)
        assertEquals(2, year.activeMonths)
        assertEquals(63_000L, year.averageSpent)
    }

    // ---- [MCAL-06][LEDG-08] Several calendars' money for the notices ----

    @Test fun mergedBooksKeepEachCalendarsCardsApart() {
        val a = MoneyBook(entries = listOf(spend(20, 10_000, "식비", account = 3).copy(date = LocalDate.of(2026, 10, 20))), accounts = accounts)
        val b = MoneyBook(entries = listOf(spend(20, 99_000, "식비", account = 3).copy(date = LocalDate.of(2026, 10, 20))),
            accounts = listOf(credit.copy(name = "알바 카드")))
        val merged = MoneyBook.merge(listOf(a, b))
        assertEquals(4, merged.accounts.map { it.id }.distinct().size)
        val bills = UpcomingPayments.within(emptyList(), merged.entries, d(10), 7, merged.accounts).associate { it.cost.name to it.cost.amount }
        assertEquals(mapOf("신한카드" to 10_000L, "알바 카드" to 99_000L), bills)
    }
}
