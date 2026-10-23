package com.bamnat.app.screenshots

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.MainActivity
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.leave.LeaveScreen
import com.bamnat.app.ui.leave.LeaveViewModel
import com.bamnat.app.ui.money.IncomeCard
import com.bamnat.app.ui.pay.HourlyPayCard
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.RateNewsCard
import com.bamnat.app.ui.pay.SlipAuditCard
import com.bamnat.app.ui.pay.WhatIfCard
import com.bamnat.app.ui.pay.YearEndCard
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.leave.LedgerEntry
import com.bamnat.core.model.WorkPattern
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * [LEAVE-10][LEAVE-11][LEAVE-13][LEDG-29][PAYX-35][PAYX-38][PAYX-41][PAYX-42][PAYX-44] Bundle K's screenshots,
 * numbers 600~629 (계획/1.23-K-급여-연차-돈-개발계획.md 54번): the 연차 screen with the 촉진 기록 and the plan, and the
 * new pay cards on their own. Its own file like JScreenshotTest, so it never collides with ScreenshotTest.kt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class KScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val today = LocalDate.now()
    private val month = YearMonth.from(today)

    @Before
    fun seed() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = app.container
        c.consentStore.markSeen(com.bamnat.core.consent.ConsentItem.entries)
        com.bamnat.app.data.GuideStore.get(app).let { g ->
            listOf("guide.checklist", "guide.tips").forEach { id -> g.setOn(com.bamnat.core.guide.FeatureRegistry.get(id)!!, "main", false) }
        }
        val repo = c.scheduleRepository
        repo.ensureDefaults()
        // 4조 2교대 (주주휴휴야야휴휴): two working days between days off, so a day of 연차 makes a long rest.
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = types.getValue("주간").id
        val night = types.getValue("야간").id
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, off, off, night, night, off, off)))
    }

    // ==== 연차 ====
    @Test fun leavePromotionFolded() = leave("600_k_leave_promotion_folded", FOLDED, "회사의 사용 촉진 기록")

    @Test fun leavePlanFolded() = leave("601_k_leave_plan_folded", FOLDED, "없어지기 전에 쓰는 법")

    @Test fun leaveLinesFolded() = leave("602_k_leave_lines_pdf_folded", FOLDED, "연차 원장")

    @Test fun leavePromotionUnfolded() = leave("603_k_leave_promotion_unfolded", UNFOLDED, "회사의 사용 촉진 기록")

    @Test fun leavePromotionBigFont() = leave("604_k_leave_promotion_folded_200", FOLDED, "회사의 사용 촉진 기록", fontScale = 2f)

    @Test fun leavePromotionDark() = leave("605_k_leave_promotion_folded_dark", FOLDED_NIGHT, "회사의 사용 촉진 기록")

    // ==== 급여 카드 ====
    @Test fun whatIfFolded() = shoot("606_k_pay_what_if_folded", FOLDED) { Cards { p, s -> WhatIfCard(p, s, month) } }

    @Test fun yearEndFolded() = shoot("607_k_pay_year_end_folded", FOLDED) {
        Cards { p, s ->
            RateNewsCard(p, 13_000)
            YearEndCard(p, s, month)
        }
    }

    @Test fun hourlyWeeksFolded() = shoot("608_k_pay_hourly_weeks_folded", FOLDED) { Hourly() }

    @Test fun slipAuditFolded() = shoot("609_k_pay_slip_audit_folded", FOLDED) { Cards { p, s -> Audit(p, s) } }

    @Test fun incomeTakeHomeFolded() = shoot("610_k_money_take_home_folded", FOLDED) {
        Cards { p, _ -> IncomeCard(income(), WorkStyle.SHIFT, p.copy(ledgerTakeHome = true), onOpenPay = {}, onTakeHome = {}) }
    }

    @Test fun whatIfBigFont() = shoot("611_k_pay_what_if_folded_200", FOLDED, fontScale = 2f) { Cards { p, s -> WhatIfCard(p, s, month) } }

    @Test fun slipAuditDark() = shoot("612_k_pay_slip_audit_folded_dark", FOLDED_NIGHT) { Cards { p, s -> Audit(p, s) } }

    @Test fun cardsUnfolded() = shoot("613_k_pay_cards_unfolded", UNFOLDED) {
        Cards { p, s ->
            WhatIfCard(p, s, month)
            YearEndCard(p, s, month)
        }
    }

    @Test fun hourlyWeeksBigFont() = shoot("614_k_pay_hourly_weeks_folded_200", FOLDED, fontScale = 2f) { Hourly() }

    /** The 간호사 sample as 4대보험, the way the year-end card wants it. */
    private fun profile(): PayProfile = PayViewModel(app.container).sampleProfile().copy(taxMode = TaxMode.INSURANCE_4, hireDate = today.minusYears(3).minusMonths(4))

    private fun snapshot(): ScheduleSnapshot = runBlocking { app.container.scheduleRepository.snapshot.first() }

    @Composable
    private fun Cards(body: @Composable (PayProfile, ScheduleSnapshot) -> Unit) {
        val p = profile()
        val s = snapshot()
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { body(p, s) }
    }

    /** A 시급 파트타임 at 10,320원 on the 4조 2교대 roster: some weeks earn 주휴, the week with a day off does not. */
    @Composable
    private fun Hourly() {
        val preset = PayPresets.of(Occupation.PART_TIME)
        val p = PayPresets.profile(preset, preset.wageType, preset.baseHint, LocalDate.of(2025, 3, 1), preset.items.filter { it.checked },
            fiveOrMore = true, holidayMode = HolidayPayMode.FULL, estimated = emptySet())
        val s = snapshot()
        val pay = PayCalculator().calculate(p, month, s.resolver::resolve, checks = false)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
            HourlyPayCard(PayViewModel(app.container), p, pay.total, unlocked = true, snapshot = s, month = month, holidayWeeks = pay.holidayWeeks)
        }
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

    /** The 연차 screen with pay set up 3년 4개월 ago, 이월 2일 and one 촉진 step on record, scrolled to [card]. */
    private fun leave(name: String, qualifiers: String, card: String, fontScale: Float = 1f) {
        runBlocking {
            val c = app.container
            c.payStore.save(profile().copy(rules = profile().rules.copy(annualLeaveDays = null)), keepUndo = false)
            val year = today.withDayOfYear(1)
            c.leaveRepository.add(LedgerEntry(kind = EntryKind.CARRY, date = year, days = 2.0, expires = year.plusYears(1).minusDays(1), note = "작년에서 넘어온 날"))
            c.leaveRepository.summary.first().nextLapse?.let { (lapse, _) ->
                c.leaveRepository.addNotice(LeavePromotion.Record(lapse = lapse, step = LeavePromotion.Step.FIRST_NOTICE,
                    date = minOf(today.minusDays(10), lapse.minusMonths(6)), note = "인사팀 메일"))
            }
        }
        shoot(name, qualifiers, fontScale, scrollTo = card) {
            LeaveScreen(LeaveViewModel(app.container, PersonalCalendar.MAIN), onBack = {}, onEditRules = {})
        }
    }

    private fun shoot(name: String, qualifiers: String, fontScale: Float = 1f, scrollTo: String? = null, body: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { it.setContent { BamnatTheme { body() } } }
            settle()
            scrollTo?.let { t ->
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(t))
                settle()
            }
            lateinit var bmp: Bitmap
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                view.draw(canvas)
                for (dialog in ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()) {
                    canvas.drawColor(0x66000000)
                    val dv = dialog.window!!.decorView
                    canvas.save()
                    canvas.translate((view.width - dv.width) / 2f, (view.height - dv.height) / 2f)
                    dv.draw(canvas)
                    canvas.restore()
                }
            }
            File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private companion object {
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
    }
}
