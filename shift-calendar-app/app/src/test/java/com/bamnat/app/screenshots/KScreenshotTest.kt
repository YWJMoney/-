package com.bamnat.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.money.IncomeCard
import com.bamnat.app.ui.pay.HourlyPayCard
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.RateNewsCard
import com.bamnat.app.ui.pay.SlipAuditCard
import com.bamnat.app.ui.pay.WhatIfCard
import com.bamnat.app.ui.pay.YearEndCard
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.money.MonthMoney
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayslipMatcher
import com.bamnat.core.pay.PayslipReader
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * [LEAVE-10][LEAVE-11][LEAVE-13][LEDG-29][PAYX-35][PAYX-38][PAYX-41][PAYX-42][PAYX-44] Bundle K's screenshots,
 * numbers 600~629 (계획/1.23-K-급여-연차-돈-개발계획.md 54번): the 연차 screen with the 촉진 기록 and the plan, and the
 * new pay cards on their own, on the shared [ScreenshotBase].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class KScreenshotTest : ScreenshotBase() {
    private val month = YearMonth.from(today)

    // ==== 연차 ====
    @Test fun leavePromotionFolded() = shoot("600_k_leave_promotion_folded", FOLDED, leave("회사의 사용 촉진 기록"))

    @Test fun leavePlanFolded() = shoot("601_k_leave_plan_folded", FOLDED, leave("없어지기 전에 쓰는 법"))

    @Test fun leaveLinesFolded() = shoot("602_k_leave_lines_pdf_folded", FOLDED, leave("연차 원장"))

    @Test fun leavePromotionUnfolded() = shoot("603_k_leave_promotion_unfolded", UNFOLDED, leave("회사의 사용 촉진 기록"))

    @Test fun leavePromotionBigFont() = shoot("604_k_leave_promotion_folded_200", FOLDED, 2f, leave("회사의 사용 촉진 기록"))

    @Test fun leavePromotionDark() = shoot("605_k_leave_promotion_folded_dark", FOLDED_NIGHT, leave("회사의 사용 촉진 기록"))

    @Test fun leavePromotionAdd() = shoot("606_k_leave_promotion_add_folded", FOLDED, leave("회사의 사용 촉진 기록") { tapText("내가 쓸 날을 정해 회사에 알림", exact = true) })

    // ==== 급여 카드 ====
    @Test fun whatIfFolded() = shoot("607_k_pay_what_if_folded", FOLDED, cards { p, s -> WhatIfCard(p, s, month) })

    @Test fun yearEndFolded() = shoot("608_k_pay_year_end_folded", FOLDED, cards({ tapText("계산하기", exact = true) }) { p, s ->
        RateNewsCard(p, 13_000)
        YearEndCard(p, s, month)
    })

    @Test fun hourlyWeeksFolded() = shoot("609_k_pay_hourly_weeks_folded", FOLDED, hourly())

    @Test fun slipAuditFolded() = shoot("610_k_pay_slip_audit_folded", FOLDED, cards { p, s -> Audit(p, s) })

    @Test fun incomeTakeHomeFolded() = shoot("611_k_money_take_home_folded", FOLDED, cards { p, _ ->
        IncomeCard(income(), WorkStyle.SHIFT, p.copy(ledgerTakeHome = true), onOpenPay = {}, onTakeHome = {})
    })

    @Test fun whatIfBigFont() = shoot("612_k_pay_what_if_folded_200", FOLDED, 2f, cards { p, s -> WhatIfCard(p, s, month) })

    @Test fun slipAuditDark() = shoot("613_k_pay_slip_audit_folded_dark", FOLDED_NIGHT, cards { p, s -> Audit(p, s) })

    @Test fun cardsUnfolded() = shoot("614_k_pay_cards_unfolded", UNFOLDED, cards { p, s ->
        WhatIfCard(p, s, month)
        YearEndCard(p, s, month)
    })

    @Test fun hourlyWeeksBigFont() = shoot("615_k_pay_hourly_weeks_folded_200", FOLDED, 2f, hourly())

    /** The 간호사 sample as 4대보험, the way the year-end card wants it. */
    private fun profile(): PayProfile = PayViewModel(app.container).sampleProfile().copy(taxMode = TaxMode.INSURANCE_4, hireDate = today.minusYears(3).minusMonths(4))

    private fun snapshot(): ScheduleSnapshot = runBlocking { app.container.scheduleRepository.snapshot.first() }

    /** The cards on a plain page over the 4조 2교대 roster. */
    private fun cards(then: Screen.() -> Unit = {}, body: @Composable (PayProfile, ScheduleSnapshot) -> Unit): Screen.() -> Unit = {
        twoOnTwoOff()
        val p = profile()
        val s = snapshot()
        content {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { body(p, s) }
        }
        settle()
        then()
    }

    /** A 시급 파트타임 on the 4조 2교대 roster: some weeks earn 주휴, a week with a day missed does not. */
    private fun hourly(): Screen.() -> Unit = {
        twoOnTwoOff()
        val preset = PayPresets.of(Occupation.PART_TIME)
        val p = PayPresets.profile(preset, preset.wageType, preset.baseHint, LocalDate.of(2025, 3, 1), preset.items.filter { it.checked },
            fiveOrMore = true, holidayMode = HolidayPayMode.FULL, estimated = emptySet())
        val s = snapshot()
        val pay = PayCalculator().calculate(p, month, s.resolver::resolve, checks = false)
        val vm = PayViewModel(app.container)
        content {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
                HourlyPayCard(vm, p, pay.total, unlocked = true, snapshot = s, month = month, holidayWeeks = pay.holidayWeeks)
            }
        }
        settle()
    }

    /** A payslip that pays less 연장 than the roster and has a 직책수당 the app does not know. */
    @Composable
    private fun Audit(p: PayProfile, s: ScheduleSnapshot) {
        val pay = PayCalculator().calculate(p, month, s.resolver::resolve, checks = false)
        val text = buildString {
            appendLine("${month.year}년 ${month.monthValue}월 급여명세서")
            pay.lines.forEach { l -> appendLine("${l.label} ${if (l.label.startsWith("연장")) l.amount - 40_000 else l.amount}") }
            appendLine("직책수당 100000")
            appendLine("국민연금 140000")
            appendLine("건강보험 110000")
            appendLine("장기요양보험 14000")
            appendLine("고용보험 28000")
            appendLine("소득세 90000")
        }
        val read = PayslipReader.read(text)
        val slip = read.payslip()!!
        val m = PayslipMatcher.match(p, month, s.resolver::resolve, slip)
        SlipAuditCard(m, slip, read, s)
    }

    private fun income() = MonthMoney(
        month = month, payEstimate = 3_412_000, payRecorded = 0, jobEarned = 0, jobReceived = 0, jobCount = 0,
        sales = 0, salesCosts = 0, salesDays = 0, otherIncome = 0, expenses = 1_250_000, expenseByCategory = emptyList(),
    )

    /** The 연차 screen ([leaveSeed]) with the company's first 촉진 on record, scrolled to [card]. */
    private fun leave(card: String, then: Screen.() -> Unit = {}): Screen.() -> Unit = leaveScreen {
        runBlocking {
            val c = app.container
            c.leaveRepository.summary.first().nextLapse?.let { (lapse, _) ->
                c.leaveRepository.addNotice(LeavePromotion.Record(lapse = lapse, step = LeavePromotion.Step.FIRST_NOTICE,
                    date = minOf(today.minusDays(10), lapse.minusMonths(6)), note = "인사팀 메일"))
            }
        }
        settle()
        reveal(card, exact = true)
        then()
    }

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
    }
}
