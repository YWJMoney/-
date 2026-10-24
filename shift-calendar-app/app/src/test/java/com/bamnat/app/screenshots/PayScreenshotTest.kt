package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.pay.CompanyRulesEditor
import com.bamnat.app.ui.pay.EditValueDialog
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.RestDayWork
import com.bamnat.app.ui.pay.LineDetailDialog
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.RaiseDialog
import com.bamnat.app.ui.pay.ValueTarget
import androidx.compose.runtime.collectAsState
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.time.Today

/** [QA-05] Screenshots: 급여·연차·회사 규칙·추가 근무 종류. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PayScreenshotTest : ScreenshotBase() {
    @Test fun foldedDayPay() = shoot("33_folded_day_pay", FOLDED) {
        val c = app.container
        c.settingsRepository.update { it.copy(previewTier = null) }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        settle()
        openDay(restDayExtra)
        openGroup("돈")
        reveal("돈", exact = true)
    }
    @Test fun foldedCompanyRules() = shoot("34_folded_company_rules", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
    })
    @Test fun foldedCompanyRulesExtra() = shoot("35_folded_company_rules_extra", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
        }.invoke(this)
        scrollUp(700)
    }
    @Test fun foldedPayDays() = shoot("36_folded_pay_days", FOLDED) {
        payScreen(Tier.PRO, withProfile = true).invoke(this)
        scrollUp(880)
    }

    @Test fun foldedLeaveDay() = shoot("37_folded_leave_day", FOLDED) {
        openDay(halfDay)
        reveal("휴가", exact = true)
    }
    @Test fun foldedPayLeave() = shoot("39_folded_pay_leave", FOLDED) {
        payScreen(Tier.PRO, withProfile = true).invoke(this)
        scrollUp(880)
        scrollUp(250)
    }
    @Test fun foldedSecondShift() = shoot("41_folded_second_shift", FOLDED) {
        scrollUp(330)
    }
    @Test fun foldedBreakStart() = shoot("42_folded_break_start", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
        tapText("주간", exact = true)
        scrollUp(150)
    }
    @Test fun foldedRulesLeave() = shoot("43_folded_rules_leave", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
        }.invoke(this)
        scrollUp(800)
        scrollUp(800)
    }
    @Test fun foldedBonusDialog() = shoot("44_folded_bonus_dialog", FOLDED, payScreen(Tier.PRO, withProfile = true) { _ ->
        com.bamnat.app.ui.pay.AddBonusDialog(onDismiss = {}) { _, _, _, _, _, _, _ -> }
    })

    @Test fun foldedKindEditor() = shoot("94_folded_kind_editor", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
    }
    @Test fun foldedKindPay() = shoot("95_folded_kind_pay_preview", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        reveal("회사는 대근을 어떻게 계산하나요?", exact = true)
        tapText("한 번 하면", scrolls = 0)
    }
    @Test fun foldedKindTemplates() = shoot("96_folded_kind_templates", FOLDED) {
        kindsScreen().invoke(this)
        tapText("종류 추가", exact = true, scrolls = 0)
    }
    @Test fun foldedRulesKinds() = shoot("99_folded_rules_kinds", FOLDED) {
        rulesEditor { companyRules }.invoke(this)
        reveal("종류·시간·가산 정하기", exact = true)
    }
    @Test fun foldedRulesRestDay() = shoot("100_folded_rules_rest_day", FOLDED) {
        rulesEditor { companyRules.copy(weeklyHoliday = com.bamnat.core.pay.WeeklyHoliday.Fixed(java.time.DayOfWeek.SUNDAY)) }.invoke(this)
        reveal("쉬는 날(휴무일)에 일하면", exact = true)
    }
    /** A 1.8 user kept on 휴일근로로 sees once what 법대로 would pay. */
    @Test fun foldedPayRestCompare() = shoot("101_folded_pay_rest_compare", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = {
            it.copy(rules = it.rules.copy(restDayWork = RestDayWork.HOLIDAY, extra = it.rules.extra + (ExtraKind.COVER_ID to legacyCover)))
        }).invoke(this)
        reveal("쉬는 날 근무, 법대로와 비교해 보세요", exact = true)
    }
    /** [EXTRA-05] A record carried over from 1.8 without times asks for one look. */
    @Test fun foldedExtraReviewCard() = shoot("102_folded_extra_review_card", FOLDED) {
        runBlocking { app.container.scheduleRepository.addExtra(today.minusDays(5), ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 90, needsReview = true)) }
        settle()
    }
    @Test fun foldedExtraReviewDialog() = shoot("102b_folded_extra_review_dialog", FOLDED) {
        runBlocking { app.container.scheduleRepository.addExtra(today.minusDays(5), ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 90, needsReview = true)) }
        settle()
        tapText("확인하기", exact = true, scrolls = 0)
        check(shownDialogs().isNotEmpty()) { "확인 창이 열리지 않았어요" }
    }
    @Test fun foldedKindReport() = shoot("103_folded_kind_report", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("회사 방식 알려 주기", exact = true)
    }
    @Test fun unfoldedKindEditor() = shoot("104_unfolded_kind_editor", UNFOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
    }
    @Test fun foldedRulesNight() = shoot("73_folded_rules_night_window", FOLDED) {
        rulesEditor { companyRules.copy(nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(7, 0)) }.invoke(this)
        scrollUp(800)
    }
    @Test fun foldedRulesPerShift() = shoot("74_folded_rules_cover_per_shift", FOLDED) {
        kindsScreen { night ->
            companyRules.copy(extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(night to
                companyCover.copy(unit = com.bamnat.core.pay.PayUnit.PER_TIME, amount = 100_000, coverMinutes = 480))))
        }.invoke(this)
        tapText("대근", exact = true)
        tapText("야간 ·", prefix = true)
        reveal("야간 대근", exact = true)
    }
    @Test fun foldedExtraKinds() = shoot("76_folded_extra_kinds", FOLDED) {
        settingsPage("내 근무")
        tapText("연장 휴무일 근무 지정", exact = true)
    }
    // ---- [EXTRA-07][EXTRA-08] 연장 휴무일 근무 지정 ----
    @Test fun foldedKindPremiums() = shoot("108_folded_kind_premiums", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        reveal("가산 · 체크한 것만 더해요", exact = true)
    }
    @Test fun foldedKindCustomPremium() = shoot("109_folded_kind_custom_premium", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("가산 직접 추가", exact = true)
        check(shownDialogs().isNotEmpty()) { "가산 추가 창이 열리지 않았어요" }
    }
    @Test fun foldedKindRate() = shoot("110_folded_kind_rate", FOLDED) {
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("연장 가산 비율 바꾸기", exact = true)
        check(shownDialogs().isNotEmpty()) { "비율 창이 열리지 않았어요" }
    }
    @Test fun foldedKindShifts() = shoot("111_folded_kind_several_shifts", FOLDED) {
        val c = app.container
        val types = runBlocking { c.scheduleRepository.snapshot.first().shiftTypes.associateBy { it.name } }
        c.settingsRepository.update { s ->
            s.copy(extraKinds = s.extraKinds.put(s.extraKinds.of(ExtraKind.COVER_ID).copy(
                timing = com.bamnat.core.model.ExtraTiming.AsShift(setOf(types.getValue("주간").id, types.getValue("야간").id)))))
        }
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        reveal("어느 근무를 대신하나요? (여러 개 고를 수 있어요)", exact = true)
    }
    @Test fun foldedKindLegacy() = shoot("112_folded_kind_way_from_before", FOLDED) {
        kindsScreen().invoke(this)
        tapText("특근", exact = true)
        reveal("예전 방식으로 계산해요", exact = true)
    }
    @Test fun foldedKindLaw() = shoot("113_folded_kind_by_law", FOLDED) {
        kindsScreen { CompanyRules() }.invoke(this)
        tapText("잔업", exact = true)
        reveal("법대로 계산해요", exact = true)
    }
    @Test fun foldedPayWizard() = shoot("26_folded_pay_wizard", FOLDED, payScreen(Tier.PRO, withProfile = false))
    @Test fun foldedPayResult() = shoot("27_folded_pay_result", FOLDED, payScreen(Tier.PRO, withProfile = true))
    @Test fun unfoldedPayResult() = shoot("28_unfolded_pay_result", UNFOLDED, payScreen(Tier.PRO, withProfile = true))
    // [PAYS-01] The allowances step ends with the bonus; the questions follow the user's own roster.
    @Test fun foldedPayWizardBonus() = shoot("45_folded_pay_wizard_bonus", FOLDED) {
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, scrollToEnd = true, startAt = "items") }
    }
    @Test fun foldedPayWizardHours() = shoot("46_folded_pay_wizard_hours", FOLDED) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, snapshot = snap, startAt = com.bamnat.core.pay.Unknowns.MONTHLY_HOURS) }
    }
    @Test fun foldedPayWizardSchedule() = shoot("47_folded_pay_wizard_schedule", FOLDED) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, snapshot = snap, startAt = com.bamnat.core.pay.Unknowns.SCHEDULE_IS_REGULAR) }
    }
    @Test fun foldedPayWizardSummary() = shoot("149_folded_pay_wizard_summary", FOLDED) {
        val c = app.container
        val vm = PayViewModel(c)
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val month = Today.month()
        val sample = vm.sampleProfile().copy(estimatedFields = setOf(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE))
        content {
            com.bamnat.app.ui.pay.PayWizard(sample, {}, {}, snapshot = snap, preview = { vm.preview(it, snap, month) }, startAt = "summary")
        }
        // The month is worked out off the main thread.
        repeat(3) { settle() }
    }
    @Test fun foldedPayRange() = shoot("150_folded_pay_range", FOLDED,
        payScreen(Tier.PRO, withProfile = true, adjust = { it.copy(estimatedFields = unknownAnswers) }))
    @Test fun foldedPayUnknowns() = shoot("151_folded_pay_unknowns", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = { it.copy(estimatedFields = unknownAnswers) }).invoke(this)
        tapText("눌러서 확인 필요")
    }
    @Test fun foldedPayslipMatchForm() = shoot("152_folded_payslip_match_form", FOLDED) {
        payslipMatch().invoke(this)
        tapText("네, 같았어요", exact = true)
        typeInto("지급 총액", "4300000")
        tapText("더 넣으면 더 정확해요 (선택)", exact = true)
    }
    @Test fun foldedPayslipMatchResult() = shoot("153_folded_payslip_match_result", FOLDED) {
        payslipMatch().invoke(this)
        tapText("네, 같았어요", exact = true)
        typeInto("지급 총액", "4300000")
        tapText("맞춰 보기", exact = true)
    }
    @Test fun foldedLeaveCarry() = shoot("81_folded_rules_leave_carry", FOLDED) {
        rulesEditor { companyRules.copy(annualLeaveDays = 15.0, annualLeaveCarry = 3.0) }.invoke(this)
        repeat(5) { scrollUp(800) }
    }
    @Test fun foldedPayPeriod() = shoot("91_folded_pay_period", FOLDED, payScreen(Tier.PRO, withProfile = true, adjust = closeOn20))
    @Test fun foldedRulesPeriod() = shoot("92_folded_rules_period", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = closeOn20) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
        }.invoke(this)
        scrollUp(RULES_PERIOD_SCROLL)
    }
    @Test fun foldedPayLocked() = shoot("29_folded_pay_locked", FOLDED, payScreen(Tier.FREE, withProfile = true))
    @Test fun foldedPayBulkEdit() = shoot("30_folded_pay_bulk_edit", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            EditValueDialog(vm, profile, snap, ValueTarget.Base, Today.month(), onDismiss = {})
        }
    })
    @Test fun foldedPayLineDetail() = shoot("31_folded_pay_line_detail", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            val result = vm.calculate(profile, snap, Today.month())
            val line = result.lines.firstOrNull { it.kind == com.bamnat.core.pay.LineKind.NIGHT } ?: result.lines.first()
            LineDetailDialog(line, result, onDismiss = {})
        }
    })
    @Test fun foldedPayRaise() = shoot("32_folded_pay_raise", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        if (profile != null) RaiseDialog(vm, profile, ValueTarget.Base, onDismiss = {})
    })

    @Test fun foldedCompanyPay() = shoot("119_folded_company_pay", FOLDED) {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, Today.instant()))
        c.companyStore.pendingPay = company.file
        val vm = com.bamnat.app.ui.company.CompanyPayViewModel(c)
        content { com.bamnat.app.ui.company.CompanyPayScreen(vm, onBack = {}, onByHand = {}) }
    }
    @Test fun foldedKindAsk() = shoot("128_folded_kind_ask", FOLDED) {
        companyOn()
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("회사 방식 알려 주기", exact = true)
    }
    @Test fun phonePayLargeText() = shoot("169_phone_pay_large_text", PHONE, fontScale = 1.5f, payScreen(Tier.PRO, withProfile = true))
    @Test fun phonePayWizardHugeText() = shoot("170_phone_pay_wizard_huge_text", PHONE, fontScale = 2f, payScreen(Tier.PRO, withProfile = false))
    @Test fun calPaintLeave() = shoot("307_unfolded_paint_leave", UNFOLDED) {
        tapText("빠른 입력", exact = true)
        tapText("연차 칠하기", exact = true, scrolls = 0)
        dragDays(secondRowMonday(), secondRowMonday().plusDays(4))
    }

    // ---- [PROV-01..PROV-08] 1.20.0: the rest of 프로's four pillars ----
    @Test fun phonePayReport() = shoot("385_phone_pay_report", PHONE) {
        payScreen(Tier.PRO, withProfile = true).invoke(this); reveal("급여 리포트", exact = true)
    }
    @Test fun phonePayReportLocked() = shoot("386_phone_pay_report_locked", PHONE, launched {
        payScreen(Tier.FREE, withProfile = true).invoke(this); reveal("급여 리포트", exact = true)
    })
    @Test fun store3Pay() = shoot("store_3_pay", PHONE, clean(payScreen(Tier.PRO, withProfile = true)))
    @Test fun foldedPayItemKinds() = shoot("260_folded_pay_item_kinds", FOLDED, itemBuilder())
    @Test fun unfoldedPayItemKinds() = shoot("261_unfolded_pay_item_kinds", UNFOLDED, itemBuilder())
    @Test fun foldedPayItemPerShift() = shoot("262_folded_pay_item_per_shift", FOLDED) {
        itemBuilder(com.bamnat.app.ui.pay.ItemKind.PER_SHIFT).invoke(this)
        typeInto("이름 (선택)", "위험수당")
        typeInto("1회 금액", "10000")
        tapText("야간", exact = true)
        repeat(2) { settle() }
    }
    @Test fun foldedPayItemDeduct() = shoot("263_folded_pay_item_deduct", FOLDED) { deductForm() }
    @Test fun unfoldedPayItemDeduct() = shoot("264_unfolded_pay_item_deduct", UNFOLDED) { deductForm() }
    @Test fun foldedPayMadeShare() = shoot("265_folded_pay_made_share", FOLDED) { madeDeduction() }
    @Test fun unfoldedPayMadeShare() = shoot("266_unfolded_pay_made_share", UNFOLDED) { madeDeduction() }
    @Test fun foldedPayCustomList() = shoot("267_folded_pay_custom_list", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = {
            it.copy(customRules = listOf(
                com.bamnat.core.pay.CustomRule("d1", "기숙사비", com.bamnat.core.pay.RuleWhen(), com.bamnat.core.pay.RulePay.Monthly(50_000), deduct = true),
                com.bamnat.core.pay.CustomRule("d2", "식대", com.bamnat.core.pay.RuleWhen(dayKind = com.bamnat.core.pay.RuleDayKind.WORK_DAY),
                    com.bamnat.core.pay.RulePay.PerTime(3_000), deduct = true),
                com.bamnat.core.pay.CustomRule("s1", "근속수당", com.bamnat.core.pay.RuleWhen(), com.bamnat.core.pay.RulePay.OfBaseMonthly(0.03)),
            ))
        }).invoke(this)
        reveal("직접 만든 항목", exact = true)
    }
    @Test fun foldedPayslipGap() = shoot("268_folded_payslip_gap", FOLDED) { payslipGap() }
    @Test fun unfoldedPayslipGap() = shoot("269_unfolded_payslip_gap", UNFOLDED) { payslipGap() }
    @Test fun foldedPayslipGapBuilder() = shoot("270_folded_payslip_gap_builder", FOLDED) {
        payslipGap()
        tapText("이 차이를 항목으로 만들기")
    }
    @Test fun foldedPayslipMatchedShare() = shoot("271_folded_payslip_matched_share", FOLDED) { payslipMatched() }
    @Test fun unfoldedPayslipMatchedShare() = shoot("272_unfolded_payslip_matched_share", UNFOLDED) { payslipMatched() }
    @Test fun foldedPayWizardQuestion() = shoot("273_folded_pay_wizard_question", FOLDED, wizardAt(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE))
    @Test fun unfoldedPayWizardQuestion() = shoot("274_unfolded_pay_wizard_question", UNFOLDED, wizardAt(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE))
    @Test fun foldedPayTermDialog() = shoot("275_folded_pay_term_dialog", FOLDED) {
        wizardAt(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE).invoke(this)
        tapText("5인 이상이란?", exact = true)
    }
    @Test fun foldedPayWizardHoursTerm() = shoot("276_folded_pay_wizard_hours_term", FOLDED, wizardAt(com.bamnat.core.pay.Unknowns.MONTHLY_HOURS))
    @Test fun foldedPayWizardItemsHint() = shoot("277_folded_pay_wizard_items_hint", FOLDED) {
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, scrollToEnd = true, startAt = "items") }
    }
    @Test fun phonePayWizardQuestionHugeText() = shoot("278_phone_pay_wizard_question_huge_text", PHONE, fontScale = 2f,
        wizardAt(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE))
    @Test fun foldedPayItemDeductDark() = shoot("288_folded_pay_item_deduct_dark", "w369dp-h905dp-port-night-420dpi") { deductForm() }
    @Test fun phonePayItemDeductHugeText() = shoot("289_phone_pay_item_deduct_huge_text", PHONE, fontScale = 2f) { deductForm() }
    // [PAYS-10][PAYS-11] The ‘?’ in the top bar, and a 추정 badge saying what to put in.
    @Test fun foldedPayEstimateHint() = shoot("267b_folded_pay_estimate_hint", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm -> com.bamnat.app.ui.pay.PayScreen(vm, onBack = {}, onHelp = {}) }.invoke(this)
        tapText("추정 포함", exact = true)
    }
    /** [DAILY-16] The pay screen's line next to 광장 시세, and its switch in 설정 › 급여. */
    @Test fun e496FoldedPayRateHint() = shoot("496_folded_pay_rate_hint", FOLDED, run {
        // Only the user's own job and region count: the 경기 형틀목수 group is on the phone too and is left alone.
        app.container.rateHints.keep("31", listOf(
            com.bamnat.app.company.RateGroup("construction", "31", "형틀목수", "day", 2026, 5, 230_000, 250_000, 280_000),
            com.bamnat.app.company.RateGroup("nurse", "31", "병동 간호사", "day", 2026, 4, 210_000, 240_000, 260_000)))
        app.container.rateHints.pick("31")
        payScreen(Tier.PRO, withProfile = true, adjust = { p ->
            p.copy(occupation = com.bamnat.core.pay.Occupation.NURSE, wageType = com.bamnat.core.pay.WageType.DAILY,
                base = com.bamnat.core.pay.ValueTimeline(listOf(com.bamnat.core.pay.DatedValue(today.withDayOfYear(1), 200_000))))
        }) { vm ->
            PayScreen(vm, onBack = {}, underTotal = { com.bamnat.app.ui.company.RateHintLine(vm.profile.collectAsState().value) {} })
        }
    })
    @Test fun e497FoldedSettingsPayRateHint() = shoot("497_folded_settings_pay_rate_hint", FOLDED) { settingsPage("급여와 연차") }

    @Test fun foldedDayTakeHome() = shoot("400_folded_day_take_home_family", FOLDED, dayTakeHome())
    @Test fun phoneDayTakeHomeHugeText() = shoot("401_phone_day_take_home_family_huge_text", PHONE, fontScale = 2f, dayTakeHome())
    @Test fun unfoldedDayTakeHome() = shoot("405_unfolded_day_take_home_family", UNFOLDED, dayTakeHome(wide = true))
    // [PAYR-07] 식대 as 비과세 on the pay screen's allowance row.
    @Test fun foldedPayMealTaxFree() = shoot("406_folded_pay_meal_tax_free", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = { p ->
            p.copy(items = p.items + com.bamnat.core.pay.PayItem("meal", "식대",
                com.bamnat.core.pay.ValueTimeline(listOf(com.bamnat.core.pay.DatedValue(LocalDate.of(2026, 1, 1), 200_000))),
                com.bamnat.core.pay.Cadence.MONTHLY, taxable = false))
        }).invoke(this)
        reveal("세금 없는 돈")
    }
    @Test fun foldedRestRules() = shoot("430_folded_rest_rules", FOLDED, restRules())
    @Test fun phoneRestRulesHugeText() = shoot("431_phone_rest_rules_huge_text", PHONE, 1.5f, restRules())
    // [JOB-02] 공무원 봉급표 on 급여, only for 공무원·경찰·소방.
    @Test fun foldedPublicGrade() = shoot("434_folded_public_grade", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = {
            com.bamnat.core.pay.PublicServantPay.apply(it.copy(occupation = com.bamnat.core.pay.Occupation.FIRE),
                com.bamnat.core.pay.PublicGrade("FIRE:소방장", 6, today.minusYears(8), onSite = true), today)
        }).invoke(this)
        reveal("다시 채우기", exact = true)
    }
    @Test fun phonePublicGradeHugeText() = shoot("435_phone_public_grade_huge_text", PHONE, 1.5f) {
        payScreen(Tier.PRO, withProfile = true, adjust = { it.copy(occupation = com.bamnat.core.pay.Occupation.PUBLIC) }).invoke(this)
        // The 연차 card above it is longer since 1.22 묶음 D, and 1.23 묶음 K's cards come before it: steps, each within reveal's scrolls.
        reveal("상담용 묶음 (급여·근무시간·연차)", exact = true)
        reveal("공무원 봉급표", exact = true)
        reveal("교대·현업 근무", exact = true)
    }
    // [REST-01] Settings keeps one row for it, beside 휴식 점검.
    @Test fun foldedSettingsRestRules() = shoot("437_folded_settings_rest_rules", FOLDED) {
        settingsPage("알림과 잠")
        reveal("근무표 점검 기준", exact = true)
    }

    @Test fun foldedLeave() = shoot("460_folded_leave", FOLDED, leaveScreen())
    @Test fun foldedLeaveLots() = shoot("461_folded_leave_lots", FOLDED, leaveScreen { reveal("없어지는 날 순서", exact = true) })
    @Test fun foldedLeaveLines() = shoot("462_folded_leave_lines", FOLDED, leaveScreen { reveal("연차 원장", exact = true) })
    @Test fun foldedLeavePayout() = shoot("463_folded_leave_payout", FOLDED, leaveScreen { reveal("못 쓴 연차, 돈으로는", exact = true) })
    @Test fun foldedLeaveBridges() = shoot("464_folded_leave_bridges", FOLDED, leaveScreen { reveal("연차로 길게 쉬기", exact = true) })
    @Test fun foldedLeaveAdd() = shoot("465_folded_leave_add", FOLDED, leaveScreen { tapText("이월", exact = true) })
    @Test fun unfoldedLeave() = shoot("466_unfolded_leave", UNFOLDED, leaveScreen())
    @Test fun phoneLeaveHugeText() = shoot("467_phone_leave_huge_text", PHONE, fontScale = 2f, leaveScreen())
    @Test fun foldedLeaveDark() = shoot("468_folded_leave_dark", "w369dp-h905dp-port-night-420dpi", leaveScreen())
    @Test fun foldedPayLeaveCard() = shoot("469_folded_pay_leave_card", FOLDED) {
        leaveSeed()
        payScreen(Tier.PRO, withProfile = true, adjust = {
            it.copy(hireDate = today.minusYears(3).minusMonths(4), rules = it.rules.copy(annualLeaveDays = null))
        }).invoke(this)
        reveal("연차 화면", exact = true)
    }
    /** [PAYX-18] The kinds of leave as chips, 보상휴가 among them, and the choice on a piece of extra work. */
    @Test fun foldedDayLeaveKinds() = shoot("470_folded_day_leave_kinds", FOLDED) {
        // 휴가 after 알람 and 메모, so it opens below the day's fixed header.
        app.container.settingsRepository.update { it.copy(dayGroupOrder = listOf("alarm", "memo", "leave")) }
        settle()
        openDay(halfDay)
        openGroup("휴가")
        reveal("반반차", exact = true)
        tapText("보상휴가", exact = true, scrolls = 0)
    }
    @Test fun foldedExtraCompLeave() = shoot("471_folded_extra_comp_leave", FOLDED) {
        openDay(restDayExtra)
        // A piece just added opens for editing.
        reveal("더 넣기", exact = true)
        tapText("잔업 2시간", exact = true, scrolls = 0)
        tapText("돈 대신 보상휴가로 받기", exact = true)
        reveal("가산을 더한 시간만큼")
    }
    @Test fun foldedPayExamples() = shoot("472_folded_pay_examples", FOLDED) {
        leaveRulesEditor().invoke(this)
        // 1.23 묶음 K's 회사마다 다른 계산 sits above it: two steps, each within reveal's scrolls.
        reveal("근로시간 제도 (주 52시간 예외)", exact = true)
        reveal("예시 규칙", exact = true)
    }
    @Test fun foldedPayExampleDialog() = shoot("473_folded_pay_example_dialog", FOLDED) {
        leaveRulesEditor().invoke(this)
        // 1.23 묶음 K's 회사마다 다른 계산 sits above it: two steps, each within reveal's scrolls.
        reveal("근로시간 제도 (주 52시간 예외)", exact = true)
        reveal("예시 규칙", exact = true)
        tapText("탄력적 근로시간제 2주", exact = true, scrolls = 0)
    }
    @Test fun foldedPayFlexTwoWeeks() = shoot("474_folded_pay_flex_two_weeks", FOLDED) {
        leaveRulesEditor { it.copy(scheduleIsRegular = true, flexPeriod = com.bamnat.core.pay.FlexPeriod.TWO_WEEKS, flexStart = today.minusDays(3)) }.invoke(this)
        reveal("2주 단위는 취업규칙으로")
    }
    /** [PAYX-20] 호봉표 pasted, the step now chosen, the values it gives. */
    @Test fun foldedPayScale() = shoot("475_folded_pay_scale", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            vm.profile.collectAsState().value?.let { p -> com.bamnat.app.ui.pay.PayScaleDialog(vm, p, onDismiss = {}) }
        }.invoke(this)
        typeInto("호봉표", "1호봉 2,400,000\n2호봉 2,480,000\n3호봉 2,565,000\n4호봉 2,650,000")
        tapText("2호봉", exact = true, scrolls = 0)
    }
    /** [PAYX-22] A matched month would change: keep, from this pay period, or undo. */
    @Test fun foldedPayVerifiedChange() = shoot("476_folded_pay_verified_change", FOLDED) {
        lateinit var vm: PayViewModel
        payScreen(Tier.PRO, withProfile = true, adjust = {
            it.copy(verified = com.bamnat.core.pay.PayslipCheck(Today.month().minusMonths(1), 3_120_000, 3_118_500, 1_760_000_000_000))
        }) { v ->
            vm = v
            PayScreen(v, onBack = {})
        }.invoke(this)
        vm.update { p -> p.copy(base = p.base.copy(entries = p.base.entries.map { it.copy(value = it.value + 50_000) })) }
        settle()
    }
    /** [IMPORT-03] 듀티표 넣기 with the file button, and the 징검다리 card on a shift calendar [LEAVE-04]. */
    @Test fun foldedDutyRosterFile() = shoot("477_folded_duty_roster_file", FOLDED) {
        val vm = com.bamnat.app.ui.imports.DutyRosterViewModel(app.container, com.bamnat.app.data.PersonalCalendar.MAIN)
        content { com.bamnat.app.ui.imports.DutyRosterScreen(vm, onBack = {}) }
        settle()
    }
    @Test fun foldedShiftBridges() = shoot("478_folded_shift_bridges", FOLDED, style(WorkStyle.SHIFT) {
        twoOnTwoOff()
        tab(3, wide = false)
        reveal("징검다리 연휴", exact = true)
    })
    @Test fun phonePayScaleHugeText() = shoot("479_phone_pay_scale_huge_text", PHONE, fontScale = 2f) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            vm.profile.collectAsState().value?.let { p -> com.bamnat.app.ui.pay.PayScaleDialog(vm, p, onDismiss = {}) }
        }.invoke(this)
        typeInto("호봉표", "1호봉 2,400,000\n2호봉 2,480,000")
    }
}
