package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.glance.appwidget.compose
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import java.time.LocalTime
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.work.WorkStyle

/** [QA-05] Screenshots: 돈·가계부·일감·받을 돈·투자·현장·가게. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MoneyScreenshotTest : ScreenshotBase() {
    @Test fun foldedFieldDayShifts() = shoot("77_folded_field_day_shifts_toggle", FOLDED, style(WorkStyle.FIELD) { openDay(dayCell); reveal("근무", exact = true) })

    @Test fun foldedDutyTyping() = shoot("364_folded_duty_typing", FOLDED, dutyTyping())
    @Test fun phoneDutyTypingHugeText() = shoot("365_phone_duty_typing_huge_text", PHONE, 1.5f, dutyTyping())

    @Test fun foldedInvestFoundTrades() = shoot("361_folded_invest_found_trades", FOLDED, foundTrades())
    @Test fun phoneInvestFoundTradesHugeText() = shoot("362_phone_invest_found_trades_huge_text", PHONE, 1.5f, foundTrades())
    @Test fun foldedInvestRecordsBadges() = shoot("363_folded_invest_records_badges", FOLDED, foundTrades { tapText("기록", exact = true) })

    @Test fun foldedOwedBusiness() = shoot("210_folded_owed_business", FOLDED, owed(WorkStyle.BUSINESS))
    @Test fun foldedOwedFieldList() = shoot("211_folded_owed_field_list", FOLDED, owed(WorkStyle.FIELD) { scrollUp(520, across = 0.5f) })
    @Test fun foldedOwedSheet() = shoot("212_folded_owed_sheet", FOLDED, owed(WorkStyle.BUSINESS) { tapText("단체 도시락 외상") })
    @Test fun foldedOwedJobSheet() = shoot("213_folded_owed_job_sheet", FOLDED, owed(WorkStyle.FIELD) { tapText("주방 수전 교체") })
    @Test fun unfoldedOwed() = shoot("214_unfolded_owed", UNFOLDED, owed(WorkStyle.SHIFT, wide = true))
    @Test fun phoneOwedEmpty() = shoot("215_phone_owed_empty", PHONE, style(WorkStyle.HOURLY) {
        val c = app.container
        // Before any job was done, so nothing is owed yet.
        val vm = com.bamnat.app.ui.money.OwedViewModel(c)
        content { com.bamnat.app.ui.money.OwedScreen(vm, wide = false, onBack = {}, today = today.minusYears(5)) }
    })
    // [LEDG-01][WORK-04] The money tab for a shift worker.
    @Test fun foldedMoney() = shoot("51_folded_money", FOLDED) { tab(1, wide = false) }
    @Test fun foldedMoneyScrolled() = shoot("52_folded_money_stats", FOLDED) { tab(1, wide = false); scrollUp(700, across = 0.3f) }
    @Test fun unfoldedMoney() = shoot("53_unfolded_money", UNFOLDED) { tab(1, wide = true) }
    // [WORK-02] Field work: jobs on the calendar and owed money.
    @Test fun foldedFieldCalendar() = shoot("54_folded_field_calendar", FOLDED, style(WorkStyle.FIELD))
    @Test fun foldedFieldDay() = shoot("55_folded_field_day", FOLDED, style(WorkStyle.FIELD) { openDay(dayCell) })
    @Test fun foldedFieldMoney() = shoot("56_folded_field_money", FOLDED, style(WorkStyle.FIELD) { tab(1, wide = false) })
    @Test fun foldedJobDialog() = shoot("57_folded_job_dialog", FOLDED) {
        content { com.bamnat.app.ui.money.JobDialog(today, null, onSave = {}, onDismiss = {}) }
    }
    // [WORK-02] A three-day job paid with 3.3% withheld and materials bought.
    @Test fun foldedJobDialogFilled() = shoot("63_folded_job_dialog_multiday", FOLDED) {
        content {
            com.bamnat.app.ui.money.JobDialog(today, Job(date = today, endDate = today.plusDays(2), title = "욕실 리모델링", amount = 3_000_000,
                deposit = 1_000_000, depositPaid = true, costs = 700_000, withholding = true), onSave = {}, onDismiss = {})
        }
        scrollUp(500, across = 0.5f)
    }
    // [LEDG-01] A shift worker's day: the side-job chip sits with the spend/income chips.
    @Test fun foldedShiftDayMoney() = shoot("64_folded_shift_day_money", FOLDED) { openDay(dayCell); openGroup("돈"); reveal("돈", exact = true) }
    // [LEDG-02][LEDG-05] The money tab with new lines from notifications and what leaves this week.
    @Test fun foldedMoneyNew() = shoot("65_folded_money_new_records", FOLDED) { tab(1, wide = false) }
    @Test fun foldedMoneyAuto() = shoot("66_folded_money_auto_record", FOLDED) { tab(1, wide = false); repeat(2) { scrollUp(700, across = 0.3f) } }
    // [LEDG-03] Three texts pasted at once: a payment, a cancellation, a deposit.
    @Test fun foldedPaste() = shoot("67_folded_paste_texts", FOLDED) {
        content {
            com.bamnat.app.ui.money.PasteDialog(emptyList(), emptyMap(), onSave = {}, onDismiss = {}, initialText =
            "[Web발신]\n현대카드(3456)승인 홍*동 45,000원 3개월 10/14 19:20 쿠팡\n\n[Web발신]\n신한카드(1234)취소 4,500원 10/15 09:00 스타벅스\n\n하나 10/15 09:00 123-456***-78 입금 3,200,000 잔액 4,000,000 (주)회사급여")
        }
    }
    // [LEDG-02] Before notification access, the app says what it reads and that nothing leaves the phone.
    @Test fun foldedCaptureDisclosure() = shoot("70_folded_capture_disclosure", FOLDED) {
        content { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp).let { it }) { com.bamnat.app.ui.money.AutoRecordCard(onPaste = {}, explainFirst = true) } }
    }
    @Test fun foldedFixedCost() = shoot("68_folded_fixed_cost", FOLDED) {
        content { com.bamnat.app.ui.money.FixedCostDialog(null, onSave = {}, onDelete = {}, onDismiss = {}) }
    }
    // [WORK-05] "+50,000" in the day's memo is offered as income.
    @Test fun foldedMemoAmount() = shoot("69_folded_memo_amount", FOLDED) { openDay(dayCell); openGroup("돈"); reveal("돈", exact = true) }
    // [WORK-03] A shop's day and month.
    @Test fun foldedBusinessDay() = shoot("58_folded_business_day", FOLDED, style(WorkStyle.BUSINESS) { openDay(dayCell) })
    @Test fun foldedBusinessMoney() = shoot("59_folded_business_money", FOLDED, style(WorkStyle.BUSINESS) { tab(1, wide = false) })
    @Test fun foldedEntryDialog() = shoot("60_folded_entry_dialog", FOLDED) {
        content { com.bamnat.app.ui.money.EntryDialog(today, null, onSave = {}, onDismiss = {}) }
    }

    @Test fun foldedFieldWithoutPattern() = shoot("62_folded_field_no_pattern", FOLDED, fresh(WorkStyle.FIELD))

    /** [MCAL-06] 돈 on an added calendar: its own jobs, with the calendars to switch above. */
    @Test fun foldedAddedCalendarMoney() = shoot("219_folded_added_calendar_money", FOLDED) {
        val c = app.container
        val id = addedCalendar(show = true)
        runBlocking {
            c.calendars.money(id).save(Job(date = today, title = "주말 행사 진행", start = LocalTime.of(10, 0), amount = 120_000))
            c.calendars.money(id).save(MoneyEntry(date = today.minusDays(3), kind = MoneyKind.INCOME, amount = 412_000, category = "급여", note = "알바 급여"))
        }
        tab(1, wide = false)
    }

    @Test fun phoneMoneyLargeText() = shoot("168_phone_money_large_text", PHONE, fontScale = 1.5f) { tab(1, wide = false) }
    @Test fun calFieldFolded() = shoot("293_folded_field_calendar", FOLDED, plain(WorkStyle.FIELD, moreJobs))
    @Test fun calFieldUnfolded() = shoot("294_unfolded_field_calendar", UNFOLDED, plain(WorkStyle.FIELD, moreJobs))
    @Test fun calFieldDay() = shoot("296_folded_field_day", FOLDED, plain(WorkStyle.FIELD, moreJobs) { openDay(today) })
    @Test fun calBusinessFolded() = shoot("297_folded_business_calendar", FOLDED, plain(WorkStyle.BUSINESS, moreSales))
    @Test fun calBusinessUnfolded() = shoot("298_unfolded_business_calendar", UNFOLDED, plain(WorkStyle.BUSINESS, moreSales))
    @Test fun calBusinessDay() = shoot("299_folded_business_day", FOLDED, plain(WorkStyle.BUSINESS, moreSales) { openDay(today) })

    @Test fun calListField() = shoot("303_folded_field_list_view", FOLDED, plain(WorkStyle.FIELD, moreJobs) {
        tapText("목록으로 보기", exact = true, scrolls = 0)
    })
    @Test fun calListBusiness() = shoot("304_unfolded_business_list_view", UNFOLDED, plain(WorkStyle.BUSINESS, moreSales) {
        tapText("목록으로 보기", exact = true, scrolls = 0)
    })

    @Test fun calLegendField() = shoot("306_folded_legend_field", FOLDED, plain(WorkStyle.FIELD, moreJobs) {
        tapText("달력 표시 안내", exact = true, scrolls = 0)
    })

    @Test fun calFieldHugeText() = shoot("314_phone_field_huge_text", PHONE, 2f, plain(WorkStyle.FIELD, moreJobs))

    @Test fun calFieldDark() = shoot("316_folded_field_dark", "w369dp-h905dp-port-night-420dpi", plain(WorkStyle.FIELD, moreJobs))

    @Test fun calFieldWidgets() {
        plain(WorkStyle.FIELD, moreJobs)
        widgetShots("317_widget_field")
    }
    @Test fun calBusinessWidgets() {
        plain(WorkStyle.BUSINESS, moreSales)
        widgetShots("318_widget_business")
    }
    @Test fun smallPhoneMoney() = shoot("367_small_phone_money", SMALL_PHONE) { tab(1, wide = false) }
    // Robolectric draws no blur, so this shows the cover Android 11 and older get (ProLock); 12+ blurs under a lighter one.
    @Test fun foldedMoneyStatsLocked() = shoot("379_folded_money_stats_locked", FOLDED, launched {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 30)
        tab(1, wide = false); tapText("분석", exact = true, scrolls = 0); reveal("돈 통계", exact = true)
    })
    @Test fun foldedLedgerSummary() = shoot("230_folded_ledger_summary", FOLDED, ledgerPage(null))
    @Test fun foldedLedgerBudgets() = shoot("231_folded_ledger_budgets", FOLDED, ledgerPage(null) { reveal("분류별 예산", exact = true) })
    @Test fun foldedLedgerAccounts() = shoot("232_folded_ledger_accounts", FOLDED, ledgerPage(null) { reveal("곧 나갈 돈") })
    @Test fun unfoldedLedgerSummary() = shoot("233_unfolded_ledger_summary", UNFOLDED, ledgerPage(null, wide = true))
    @Test fun foldedLedgerList() = shoot("234_folded_ledger_list", FOLDED, ledgerPage("내역"))
    @Test fun unfoldedLedgerList() = shoot("235_unfolded_ledger_list", UNFOLDED, ledgerPage("내역", wide = true))
    @Test fun foldedLedgerFilter() = shoot("236_folded_ledger_filter", FOLDED, ledgerPage("내역") { tapText("거르기", exact = true, scrolls = 0) })
    @Test fun foldedLedgerSelect() = shoot("237_folded_ledger_select", FOLDED, ledgerPage("내역") {
        reveal("팀 점심")
        longPressText("팀 점심")
        tapText("이마트")
    })
    @Test fun foldedLedgerReport() = shoot("238_folded_ledger_report", FOLDED, ledgerPage("분석"))
    @Test fun foldedLedgerReportMore() = shoot("239_folded_ledger_report_more", FOLDED, ledgerPage("분석") { reveal("년 흐름") })
    @Test fun unfoldedLedgerReport() = shoot("240_unfolded_ledger_report", UNFOLDED, ledgerPage("분석", wide = true))
    @Test fun foldedLedgerEntry() = shoot("241_folded_ledger_entry", FOLDED, ledgerPage(null) { tapText("돈 적기", exact = true, scrolls = 0) })
    @Test fun foldedLedgerEntryMore() = shoot("242_folded_ledger_entry_more", FOLDED, ledgerPage("내역") {
        tapText("이마트")
        reveal("나눠 적기", exact = true)
    })
    @Test fun foldedLedgerRecurring() = shoot("243_folded_ledger_recurring", FOLDED, ledgerPage(null) { tapText("주말 알바비") })
    @Test fun foldedLedgerAccount() = shoot("244_folded_ledger_account", FOLDED, ledgerPage(null) { tapText("신한 Deep", prefix = true) })
    @Test fun foldedLedgerBudgetEditor() = shoot("245_folded_ledger_budget_editor", FOLDED, ledgerPage(null) {
        reveal("분류별 예산", exact = true)
        tapText("고치기", exact = true)
    })
    @Test fun foldedLedgerCategories() = shoot("246_folded_ledger_categories", FOLDED, ledgerPage(null) {
        tapText("돈 적기", exact = true, scrolls = 0)
        tapText("분류 편집", exact = true)
    })
    @Test fun phoneLedgerSummaryHugeText() = shoot("247_phone_ledger_summary_huge_text", PHONE, fontScale = 2f, ledgerPage(null))
    @Test fun phoneLedgerListHugeText() = shoot("248_phone_ledger_list_huge_text", PHONE, fontScale = 2f, ledgerPage("내역"))
    @Test fun phoneLedgerEntryHugeText() = shoot("249_phone_ledger_entry_huge_text", PHONE, fontScale = 2f,
        ledgerPage(null) { tapText("돈 적기", exact = true, scrolls = 0) })
    @Test fun foldedLedgerDark() = shoot("250_folded_ledger_dark", "w369dp-h905dp-port-night-420dpi", ledgerPage(null))
    @Test fun foldedLedgerReportDark() = shoot("251_folded_ledger_report_dark", "w369dp-h905dp-port-night-420dpi", ledgerPage("분석"))
    @Test fun foldedLedgerEmpty() = shoot("252_folded_ledger_empty", FOLDED) {
        addedCalendar(show = true)
        tab(1, wide = false)
        tapText("내역", exact = true, scrolls = 0)
    }
    /** [LEDG-01][LEDG-11] A day's ledger lines on the calendar, with badges, a split line, tags and installments. */
    @Test fun foldedDayLedger() = shoot("253_folded_day_ledger", FOLDED) {
        ledger()
        runBlocking {
            val money = app.container.moneyRepository
            val card = money.allAccounts().first { it.last4 == "1234" }
            money.save(MoneyEntry(date = dayCell, kind = MoneyKind.EXPENSE, amount = 86_000, category = "식비", merchant = "가나다 한우",
                accountId = card.id, tags = listOf("회식"), parts = listOf(com.bamnat.core.money.EntryPart("여가", 20_000))))
            money.save(MoneyEntry(date = dayCell, kind = MoneyKind.EXPENSE, amount = 1_290_000, category = "생활", merchant = "라마바전자 냉장고",
                accountId = card.id, installments = 6))
            money.save(MoneyEntry(date = dayCell, kind = MoneyKind.TRANSFER, amount = 300_000, category = "저축", merchant = "적금"))
        }
        openDay(dayCell)
        openGroup("돈")
        reveal("가계부", exact = true)
    }
    @Test fun unfoldedLedgerEntry() = shoot("254_unfolded_ledger_entry", UNFOLDED, ledgerPage("내역", wide = true) { tapText("팀 점심") })
    @Test fun phoneLedgerReport() = shoot("255_phone_ledger_report", PHONE, ledgerPage("분석"))
    // [LEDG-16] 자동 기록, as settings' 결제 알림 읽기 opens it (the ⋮ menu's popup is out of the test's reach).
    @Test fun foldedLedgerAutoRecord() = shoot("259_folded_ledger_auto_record", FOLDED, ledgerPage(null) {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.AUTO_RECORD)
        settle()
    })
    // [LEDG-15] Field and shop work read jobs or sales on 통계, not 0시간; a narrow pane keeps an amount whole.
    @Test fun foldedFieldStats() = shoot("256_folded_field_stats", FOLDED, fresh(WorkStyle.FIELD) { tab(3, wide = false) })
    @Test fun foldedBusinessStats() = shoot("257_folded_business_stats", FOLDED, fresh(WorkStyle.BUSINESS) { tab(3, wide = false) })
    @Test fun unfoldedBusinessDaySales() = shoot("258_unfolded_business_day_sales", UNFOLDED, style(WorkStyle.BUSINESS) {
        reveal("순수익", exact = true, across = 0.75f)
    })

    // ==== 1.22 묶음 E ====
    /** [DAILY-04][DAILY-15] 돈 관리 요약 in its new order, and its 화면 꾸미기 sheet. */
    @Test fun e480FoldedMoneySummary() = shoot("480_folded_money_summary_order", FOLDED, ledgerPage(null))
    @Test fun e481UnfoldedMoneySummary() = shoot("481_unfolded_money_summary_order", UNFOLDED, ledgerPage(null, wide = true))
    @Test fun e482FoldedMoneyCustomize() = shoot("482_folded_money_customize", FOLDED, ledgerPage(null) {
        content { com.bamnat.app.ui.money.MoneyEditSheet("", {}, onDismiss = {}) }
        settle()
    })
    @Test fun e483PhoneMoneySummaryHugeText() = shoot("483_phone_money_summary_huge_text", PHONE, 1.5f, ledgerPage(null))

    /** [LEDG-27] 받을 돈 with its CSV button. */
    @Test fun e489FoldedOwedCsv() = shoot("489_folded_owed_csv", FOLDED, owed(WorkStyle.BUSINESS))

    /** [LEDG-25] 지출 적기 with 영수증 사진 읽기 first. */
    @Test fun e490FoldedReceiptScan() = shoot("490_folded_entry_receipt_scan", FOLDED, ledgerPage(null) { tapText("돈 적기", exact = true, scrolls = 0) })
    @Test fun e491PhoneReceiptScanHugeText() = shoot("491_phone_entry_receipt_scan_huge_text", PHONE, 1.5f,
        ledgerPage(null) { tapText("돈 적기", exact = true, scrolls = 0) })

    /** [LEDG-26] 종합소득세 참고 on a field worker's 근무·급여. */
    @Test fun e492FoldedIncomeTax() = shoot("492_folded_field_income_tax", FOLDED, style(WorkStyle.FIELD) {
        tab(3, wide = false)
        reveal("종합소득세 참고", across = 0.5f)
    })

    /** [DAILY-13] Card sales from notices waiting for 적기 on a shop's 근무·급여. */
    @Test fun e493FoldedShopPendingSales() = shoot("493_folded_shop_pending_sales", FOLDED, style(WorkStyle.BUSINESS) {
        val store = com.bamnat.app.money.ShopPendingStore.get(app)
        listOf(38_500L to "토스플레이스", 12_000L to "배민사장님", -4_500L to "토스플레이스").forEachIndexed { i, (amount, from) ->
            val bucket = if (from == "배민사장님") com.bamnat.core.money.ShopCapture.Bucket.DELIVERY else com.bamnat.core.money.ShopCapture.Bucket.CARD
            store.add(com.bamnat.app.data.PersonalCalendar.MAIN, com.bamnat.core.money.ShopCapture.Pending("shot$i", today, com.bamnat.core.money.ShopCapture.Sale(bucket, amount, from)))
        }
        tab(3, wide = false)
        settle()
        reveal("확인할 매출 알림", across = 0.5f)
    })

    /** [INV-33] An account's own 해외 주식 fee. */
    @Test fun e500FoldedInvestAccountFees() = shoot("500_folded_invest_account_fees", FOLDED, style(WorkStyle.INVEST) {
        val vm = com.bamnat.app.ui.invest.InvestViewModel(app.container, com.bamnat.app.data.PersonalCalendar.MAIN)
        content { com.bamnat.app.ui.invest.AccountSheet(vm, null) {} }
        settle()
    })

    // [NURS-04] A roster photo's doubtful cells, the only ones left to tap.
    @Test fun foldedDutyTypingDoubtful() = shoot("433_folded_duty_typing_doubtful", FOLDED, style(WorkStyle.SHIFT) {
        val c = app.container
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val t = snap.shiftTypes.associateBy { it.name }
        val month = java.time.YearMonth.of(2026, 11)
        val codes = listOf("주간", "주간", "오후", "오후", "야간", "야간", "휴무", "휴무", "주간", "주간", "오후", "오후", "야간", "야간")
        val initial = codes.mapIndexed { i, n -> month.atDay(i + 1) to ShiftOverride.Shift(t.getValue(n).id) as ShiftOverride }.toMap()
        val doubtful = setOf(4, 9, 15, 16, 22).map { month.atDay(it) }.toSet()
        content {
            com.bamnat.app.ui.calendar.DutyTypingSheet(snap, month, onDismiss = {}, onSave = {}, initial = initial, doubtful = doubtful, onRules = {})
        }
        settle()
    })
}
