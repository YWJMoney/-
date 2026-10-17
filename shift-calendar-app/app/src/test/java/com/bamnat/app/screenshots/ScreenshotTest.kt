package com.bamnat.app.screenshots

import org.junit.Assert.assertTrue
import com.bamnat.app.share.MonthImage
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.MainActivity
import com.bamnat.app.BamnatApp
import com.bamnat.app.R
import androidx.activity.compose.setContent
import com.bamnat.app.ui.diag.DiagnosticsScreen
import com.bamnat.app.ui.diag.DiagnosticsViewModel
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.pay.CompanyRulesEditor
import com.bamnat.app.ui.pay.EditValueDialog
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.app.ui.pay.LineDetailDialog
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.RaiseDialog
import com.bamnat.app.ui.pay.ValueTarget
import androidx.compose.runtime.collectAsState
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.money.FixedCost
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog
import java.io.File
import androidx.glance.appwidget.compose
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import java.time.LocalDate
import java.time.LocalTime
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.work.WorkStyle

/** Renders the main screens with sample data at Galaxy Fold sizes into build/screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val today = LocalDate.now()

    @Before
    fun seed() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val c = app.container
        c.settingsRepository.update { it.copy(notificationPermissionAsked = true) }
        val repo = c.scheduleRepository
        repo.ensureDefaults()
        val types = repo.shiftTypes.first().associateBy { it.name }
        // 12-hour two-shift work, common in security and manufacturing.
        val day = repo.saveShiftType(types.getValue("주간").copy(start = LocalTime.of(8, 0), end = LocalTime.of(20, 0),
            alarmEnabled = true, alarms = listOf(AlarmSpec(90), AlarmSpec(60), AlarmSpec(30, enabled = false)), icon = "sun"))
        val night = repo.saveShiftType(types.getValue("야간").copy(start = LocalTime.of(20, 0), end = LocalTime.of(8, 0), icon = "moon"))
        val evening = types.getValue("오후").id
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, night, night, off)))

        // A swapped shift, overtime, a day of holiday work and a skipped alarm this month.
        repo.setOverride(today.plusDays(3), ShiftOverride.Shift(evening))
        repo.setOvertime(today.minusDays(2), 120)
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, night, night, off))
        val nextOff = (1..10).map { today.minusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == off }
        repo.setExtraWork(nextOff, ExtraWork.SPECIAL, 8 * 60, workDay = false)
        val laterOff = (1..10).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == off }
        repo.setExtraWork(laterOff, ExtraWork.COVER, 12 * 60, workDay = false)
        repo.setExtraWork(today.plusDays(1), ExtraWork.OVERTIME, 2 * 60, workDay = true)
        repo.setAlarmSkipped(today.plusDays(6), true)
        // [FEAT-10] 반차 today and a whole day of 연차 for the check-up on the first working day after it.
        repo.setLeave(today, Leave(LeaveKind.ANNUAL, 2))
        val checkUp = (5..12).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == day }
        repo.setLeave(checkUp, Leave())
        // [FEAT-02] 주간 then a 야간 대근 on the next working day after the check-up.
        val doubleDay = (1..6).map { checkUp.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == day }
        repo.setSecondShift(doubleDay, night)
        repo.setExtraWork(doubleDay, ExtraWork.COVER, 0, workDay = true)
        repo.setDayAlarms(today, listOf(DayAlarm(LocalTime.of(6, 10)), DayAlarm(LocalTime.of(6, 30), enabled = false)))

        val memos = c.memoRepository
        memos.save(Memo(date = today, title = "인수인계", body = "3번 라인 센서 점검 요청\n야간조에 키 반납 확인"))
        memos.save(Memo(date = today, isChecklist = true, title = "준비물",
            checklist = listOf(ChecklistItem("출입증", true), ChecklistItem("안전화", true), ChecklistItem("도시락"))))
        memos.save(Memo(date = today.plusDays(5), body = "건강검진 오전 9시", pinned = true))
        memos.save(Memo(date = today.minusDays(3), title = "야간 특이사항", body = "2번 설비 소음, 정비팀 전달 #설비"))
        memos.save(Memo(title = "교대 일정 바꾸기", body = "김 반장님께 19일 근무 교환 문의 #교대", colorIndex = 3))
        memos.save(Memo(title = "자격증 공부", body = "산업안전기사 필기 #공부\n주 3회 1시간", colorIndex = 5, pinned = true))
        memos.save(Memo(isChecklist = true, title = "장보기",
            checklist = listOf(ChecklistItem("우유", true), ChecklistItem("계란"), ChecklistItem("커피"))))
        // [LEDG-01][WORK-02][WORK-03] A month of spending, a few jobs and a shop day.
        val money = c.moneyRepository
        val month = today.withDayOfMonth(1)
        listOf(
            Triple(0, 4_500L, "카페" to "출근길 커피"), Triple(1, 32_000L, "식비" to "팀 점심"), Triple(2, 68_400L, "장보기·쇼핑" to "이마트"),
            Triple(3, 13_500L, "구독" to "넷플릭스"), Triple(5, 5_200L, "카페" to "메가커피"), Triple(6, 55_000L, "통신" to "휴대폰 요금"),
            Triple(8, 18_000L, "배달" to "배달의민족"), Triple(9, 3_300L, "편의점" to "CU"),
        ).forEach { (d, amount, cn) ->
            money.save(MoneyEntry(date = month.plusDays(d.toLong().coerceAtMost(today.dayOfMonth - 1L)), kind = MoneyKind.EXPENSE,
                amount = amount, category = cn.first, note = cn.second))
        }
        money.save(MoneyEntry(date = month, kind = MoneyKind.INCOME, amount = 150_000, category = "부수입", note = "중고 거래"))
        money.save(MoneyEntry(date = month.minusMonths(1).plusDays(4), kind = MoneyKind.EXPENSE, amount = 210_000, category = "식비"))
        money.save(Job(date = today, title = "욕실 타일 시공", client = "김OO 고객", place = "OO아파트 103동", start = LocalTime.of(9, 0),
            end = LocalTime.of(17, 0), amount = 1_200_000, deposit = 300_000, depositPaid = true, remindMinutes = 60))
        money.save(Job(date = today.minusDays(5), endDate = today.minusDays(3), title = "도배", client = "박OO", amount = 800_000, balancePaid = true, costs = 180_000, withholding = true))
        money.save(Job(date = today.minusDays(2), title = "주방 수전 교체", amount = 150_000))
        money.save(Job(date = today.plusDays(2), title = "현장 실측", place = "OO빌라", start = LocalTime.of(14, 0), amount = 0))
        money.save(DailySales(today, sales = 1_240_000, costs = 380_000, note = "단체 손님 1팀"))
        money.save(DailySales(today.minusDays(1), sales = 860_000, costs = 240_000))
        // [LEDG-02][LEDG-05][WORK-05] Card notifications read in, one the parser could not finish, fixed costs, a memo amount.
        val md = "${today.monthValue}/${today.dayOfMonth}"
        val posted = System.currentTimeMillis()
        money.capture("[Web발신]\n신한카드(1234)승인 홍*동 4,500원(일시불)$md 08:10 스타벅스 누적512,300원", "신한카드", posted)
        money.capture("KB국민카드5678승인 홍*동 23,800원 일시불 $md 12:40 김밥천국 누적 88,000원", "KB Pay", posted)
        money.capture("우리(9012)승인 USD 12.99 $md 07:00 NETFLIX.COM", "우리WON카드", posted)
        money.save(FixedCost(name = "월세", amount = 450_000, day = today.plusDays(2).dayOfMonth, category = "주거"))
        money.save(FixedCost(name = "실손보험", amount = 38_000, day = today.plusDays(6).dayOfMonth, category = "의료"))
        money.save(FixedCost(name = "신한카드", amount = 612_000, day = 14, kind = MoneyKind.TRANSFER, category = "카드값"))
        // On the cell the day screenshots tap (row 3, Wednesday).
        memos.save(Memo(date = today.minusDays(2), title = "부업", body = "+50,000 대리운전 팁"))

        val old = memos.save(Memo(title = "지난달 회식 장소", body = "OO갈비"))
        memos.delete(old)
        memos.consumeLastDeleted()
    }

    private fun shoot(name: String, qualifiers: String, actions: Screen.() -> Unit = {}) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        // The calendar re-reads the date every minute forever, so the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            Screen(scenario).actions()
            settle()
            lateinit var bmp: Bitmap
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                view.draw(canvas)
                // Dialogs live in their own window, so they are drawn on top by hand.
                val dialog = ShadowDialog.getLatestDialog()
                if (dialog != null && dialog.isShowing) {
                    canvas.drawColor(0x66000000)
                    val dv = dialog.window!!.decorView
                    canvas.save()
                    canvas.translate((view.width - dv.width) / 2f, (view.height - dv.height) / 2f)
                    dv.draw(canvas)
                    canvas.restore()
                }
            }
            val out = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            out.outputStream().use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) }
        }
    }

    /** Lets Room deliver on its background threads and the UI draw, then finishes animations. */
    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /**
     * Touches go straight to the window: looking nodes up in the semantics tree of the
     * 2400-page month pager is too slow under Robolectric. Points are in px of the window.
     */
    inner class Screen(private val scenario: ActivityScenario<MainActivity>) {
        private fun gesture(points: (width: Int, height: Int, density: Float) -> List<Pair<Float, Float>>) {
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val path = points(view.width, view.height, activity.resources.displayMetrics.density)
                // An open dialog takes the touches; its window is centered on the screen.
                val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                val target = dialog?.window?.decorView ?: view
                val dx = (view.width - target.width) / 2f
                val dy = (view.height - target.height) / 2f
                val start = SystemClock.uptimeMillis()
                path.forEachIndexed { i, (px, py) ->
                    val x = px - dx
                    val y = py - dy
                    val action = when (i) {
                        0 -> MotionEvent.ACTION_DOWN
                        path.lastIndex -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    target.dispatchTouchEvent(MotionEvent.obtain(start, start + i * 16L, action, x, y, 0))
                    shadowOf(Looper.getMainLooper()).idle()
                    compose.mainClock.advanceTimeBy(16)
                }
            }
            settle()
        }

        /** Opens a top-level tab (0 = calendar, 1 = money, 2 = memos, 3 = stats, 4 = settings). */
        fun tab(index: Int, wide: Boolean) = gesture { w, h, d ->
            val p = if (wide) 40 * d to (45 + index * 62) * d
            else w * (index + 0.5f) / 5 to h - 40 * d
            listOf(p, p)
        }

        /** Taps a point given in dp from the top-left, or from the right edge when [fromRight]. */
        /** Replaces the activity's content, for screens that are slow to reach by touch. */
        fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
            scenario.onActivity { activity -> activity.setContent { BamnatTheme { body() } } }
        }

        fun tap(xDp: Float, yDp: Float, fromRight: Boolean = false) = gesture { w, _, d ->
            val p = (if (fromRight) w - xDp * d else xDp * d) to yDp * d
            listOf(p, p)
        }

        /** Holds a finger on a point long enough for a long press. */
        fun longPress(xDp: Float, yDp: Float) = gesture { _, _, d ->
            val p = xDp * d to yDp * d
            List(40) { p }
        }

        /** Drags the content up by [dp] (slowly, so it does not fling). */
        fun scrollUp(dp: Int, across: Float = 0.15f) = gesture { w, h, d ->
            val x = w * across
            val from = h * 0.8f
            (0..40).map { x to from - dp * d * it / 40 } + listOf(x to from - dp * d)
        }
    }

    @Test fun foldedCalendar() = shoot("1_folded_calendar", FOLDED)
    @Test fun foldedDayDetail() = shoot("2_folded_day_sheet", FOLDED) { tap(DAY_X, DAY_Y) }
    @Test fun foldedDaySheetScrolled() = shoot("2b_folded_day_sheet_extra", FOLDED) {
        tap(DAY_X, DAY_Y)
        scrollUp(500)
    }
    @Test fun foldedExtraWorkDay() = shoot("19_folded_extra_work_day", FOLDED) {
        tap(EXTRA_DAY_X, DAY_Y)
        scrollUp(250)
    }
    @Test fun foldedTimeWheel() = shoot("20_folded_time_wheel", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 270f)
        tap(100f, 470f)
    }
    @Test fun foldedPatternChange() = shoot("21_folded_pattern_change", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_PATTERN_Y)
        tap(30f, 32f, fromRight = true)
    }
    @Test fun foldedStats() = shoot("3_folded_stats", FOLDED) { tab(3, wide = false) }
    @Test fun foldedSettings() = shoot("4_folded_settings", FOLDED) { tab(4, wide = false) }
    @Test fun unfoldedCalendar() = shoot("5_unfolded_calendar", UNFOLDED)
    @Test fun unfoldedStats() = shoot("7_unfolded_stats", UNFOLDED) { tab(3, wide = true) }
    @Test fun unfoldedDayDetail() = shoot("6_unfolded_day", UNFOLDED) { scrollUp(500, across = 0.75f) }
    @Test fun unfoldedSettings() = shoot("8_unfolded_settings", UNFOLDED) { tab(4, wide = true) }

    @Test fun foldedCalendarMemos() = shoot("9_folded_memos_calendar", FOLDED) { tab(2, wide = false) }
    @Test fun foldedGeneralMemos() = shoot("10_folded_memos_general", FOLDED) {
        tab(2, wide = false)
        tap(276f, TAB_ROW_Y)
    }
    @Test fun foldedTrash() = shoot("11_folded_trash", FOLDED) {
        tab(2, wide = false)
        tap(28f, 32f, fromRight = true)
    }
    @Test fun unfoldedMemos() = shoot("12_unfolded_memos", UNFOLDED) { tab(2, wide = true) }
    @Test fun foldedShiftTypes() = shoot("13_folded_shift_types", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
    }
    @Test fun foldedPattern() = shoot("14_folded_pattern", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_PATTERN_Y)
    }
    @Test fun foldedDayAlarmEditor() = shoot("15_folded_day_alarm_editor", FOLDED) {
        tap(DAY_X, DAY_Y)
        scrollUp(DAY_ALARM_SCROLL)
        tap(180f, DAY_ALARM_EDIT_Y)
    }

    @Test fun foldedShiftTypeEditor() = shoot("16_folded_shift_type_editor", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 270f)
    }

    @Test fun foldedShiftTypeAlarms() = shoot("17_folded_shift_type_alarms", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 270f)
        scrollUp(600)
    }
    @Test fun foldedDiagnostics() = shoot("22_folded_diagnostics", FOLDED) {
        val c = app.container
        val now = System.currentTimeMillis()
        c.telemetry.event(DiagEvents.APP_OPEN)
        c.telemetry.event(DiagEvents.ALARM_FIRED, EventBook.triggerDetail(now - 3_600_000, "late_s=2"))
        c.telemetry.event(DiagEvents.ALARM_DISMISSED)
        c.telemetry.event(DiagEvents.SCREEN, "stats")
        val vm = DiagnosticsViewModel(c)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }

    /** 대근 at 200% with 8 hours credited, 특근 at a fixed amount: a typical company setup. */
    private val companyRules = CompanyRules(
        extra = mapOf(
            ExtraWork.COVER to ExtraRule(ExtraBasis.HOURLY_RATE, multiplier = 2.0, minMinutes = 480),
            ExtraWork.SPECIAL to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 150_000),
        ),
        annualLeaveDays = 15.0,
    )

    private fun payScreen(tier: Tier, withProfile: Boolean, body: (@androidx.compose.runtime.Composable (PayViewModel) -> Unit)? = null): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = tier) }
        val vm = PayViewModel(c)
        runBlocking { c.payStore.save(if (withProfile) vm.sampleProfile().copy(rules = companyRules) else null, keepUndo = false) }
        settle()
        content { if (body != null) body(vm) else PayScreen(vm, onBack = {}) }
        settle()
    }

    @Test fun foldedDayPay() = shoot("33_folded_day_pay", FOLDED) {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = Tier.PRO) }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        settle()
        tap(EXTRA_DAY_X, DAY_Y)
        scrollUp(560)
    }
    @Test fun foldedCompanyRules() = shoot("34_folded_company_rules", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {})
    })
    @Test fun foldedCompanyRulesExtra() = shoot("35_folded_company_rules_extra", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {})
        }.invoke(this)
        scrollUp(700)
    }
    @Test fun foldedPayDays() = shoot("36_folded_pay_days", FOLDED) {
        payScreen(Tier.PRO, withProfile = true).invoke(this)
        scrollUp(880)
    }

    @Test fun foldedLeaveDay() = shoot("37_folded_leave_day", FOLDED) {
        tap(DAY_X + 51f, DAY_Y)
        scrollUp(500)
        scrollUp(420)
    }
    @Test fun foldedShiftIcons() = shoot("38_folded_shift_icons", FOLDED) {
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 270f)
        scrollUp(330)
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
        tab(4, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 270f)
        scrollUp(150)
    }
    @Test fun foldedRulesLeave() = shoot("43_folded_rules_leave", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {})
        }.invoke(this)
        scrollUp(800)
        scrollUp(800)
    }
    @Test fun foldedBonusDialog() = shoot("44_folded_bonus_dialog", FOLDED, payScreen(Tier.PRO, withProfile = true) { _ ->
        com.bamnat.app.ui.pay.AddBonusDialog(onDismiss = {}) { _, _, _, _, _, _, _ -> }
    })

    // ---- M3 [FEAT-16][FEAT-17][PAYX-09..13] ----
    private fun rulesEditor(rules: (nightId: Long) -> CompanyRules): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = Tier.PRO) }
        val vm = PayViewModel(c)
        val nightId = runBlocking { c.scheduleRepository.snapshot.first().shiftTypes.first { it.name == "야간" }.id }
        runBlocking { c.payStore.save(vm.sampleProfile().copy(rules = rules(nightId)), keepUndo = false) }
        settle()
        content {
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {})
        }
        settle()
    }
    @Test fun foldedMemoFirstCalendar() = shoot("71_folded_memo_first_calendar", FOLDED) {
        app.container.settingsRepository.update { it.copy(memoFirst = true) }
        settle()
        scrollUp(450)
    }
    @Test fun foldedCalendarMemoList() = shoot("72_folded_calendar_memo_list", FOLDED) { scrollUp(700); scrollUp(700) }
    @Test fun foldedRulesNight() = shoot("73_folded_rules_night_window", FOLDED) {
        rulesEditor { companyRules.copy(nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(7, 0)) }.invoke(this)
        scrollUp(800)
    }
    @Test fun foldedRulesPerShift() = shoot("74_folded_rules_cover_per_shift", FOLDED) {
        rulesEditor { night ->
            companyRules.copy(extraByShift = mapOf(ExtraWork.COVER to mapOf(night to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 100_000))))
        }.invoke(this)
        repeat(3) { scrollUp(800) }
    }
    @Test fun foldedExtraKinds() = shoot("76_folded_extra_kinds", FOLDED, payScreen(Tier.PRO, withProfile = true) { _ ->
        com.bamnat.app.ui.settings.ExtraKindsDialog(
            com.bamnat.core.model.ExtraKinds(names = mapOf(ExtraWork.OVERTIME to "조출"), minutes = mapOf(ExtraWork.COVER to 720)),
            onDismiss = {}, onSave = {},
        )
    })
    @Test fun foldedFieldDayShifts() = shoot("77_folded_field_day_shifts_toggle", FOLDED, style(WorkStyle.FIELD) { tap(DAY_X, DAY_Y); scrollUp(600) })

    @Test fun monthImage() = runBlocking {
        val snap = app.container.scheduleRepository.snapshot.first()
        val bmp = MonthImage.render(snap, java.time.YearMonth.from(today), java.time.DayOfWeek.SUNDAY)
        val out = File("build/screenshots/40_month_share_image.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.height > bmp.width)
    }

    @Test fun foldedPayWizard() = shoot("26_folded_pay_wizard", FOLDED, payScreen(Tier.PRO, withProfile = false))
    @Test fun foldedPayResult() = shoot("27_folded_pay_result", FOLDED, payScreen(Tier.PRO, withProfile = true))
    @Test fun unfoldedPayResult() = shoot("28_unfolded_pay_result", UNFOLDED, payScreen(Tier.PRO, withProfile = true))
    @Test fun foldedPayWizardBonus() = shoot("45_folded_pay_wizard_bonus", FOLDED) {
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, startStep = 3) }
    }
    @Test fun foldedPayWizardHours() = shoot("46_folded_pay_wizard_hours", FOLDED) {
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, startStep = 4) }
    }
    @Test fun foldedPayWizardSchedule() = shoot("47_folded_pay_wizard_schedule", FOLDED) {
        content { com.bamnat.app.ui.pay.PayWizard(null, null, {}, startStep = 4, scrollToEnd = true) }
    }
    @Test fun foldedShareLink() = shoot("48_folded_share_link", FOLDED) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content {
            com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {},
                preview = com.bamnat.app.share.ShareLinkState("Ab3dE", "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv", "t",
                    java.time.Instant.now().plus(java.time.Duration.ofDays(30)), "민지"))
        }
    }
    // [WORK-01] First launch: how do you work?
    @Test fun foldedWorkStylePicker() = shoot("49_folded_work_style", FOLDED) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    @Test fun unfoldedWorkStylePicker() = shoot("50_unfolded_work_style", UNFOLDED) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    // [LEDG-01][WORK-04] The money tab for a shift worker.
    @Test fun foldedMoney() = shoot("51_folded_money", FOLDED) { tab(1, wide = false) }
    @Test fun foldedMoneyScrolled() = shoot("52_folded_money_stats", FOLDED) { tab(1, wide = false); scrollUp(700, across = 0.3f) }
    @Test fun unfoldedMoney() = shoot("53_unfolded_money", UNFOLDED) { tab(1, wide = true) }
    // [WORK-02] Field work: jobs on the calendar and owed money.
    @Test fun foldedFieldCalendar() = shoot("54_folded_field_calendar", FOLDED, style(WorkStyle.FIELD))
    @Test fun foldedFieldDay() = shoot("55_folded_field_day", FOLDED, style(WorkStyle.FIELD) { tap(DAY_X, DAY_Y) })
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
    @Test fun foldedShiftDayMoney() = shoot("64_folded_shift_day_money", FOLDED) { tap(DAY_X, DAY_Y); repeat(3) { scrollUp(700, across = 0.5f) } }
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
    @Test fun foldedMemoAmount() = shoot("69_folded_memo_amount", FOLDED) { tap(DAY_X, DAY_Y); repeat(3) { scrollUp(700, across = 0.5f) } }
    // [WORK-03] A shop's day and month.
    @Test fun foldedBusinessDay() = shoot("58_folded_business_day", FOLDED, style(WorkStyle.BUSINESS) { tap(DAY_X, DAY_Y) })
    @Test fun foldedBusinessMoney() = shoot("59_folded_business_money", FOLDED, style(WorkStyle.BUSINESS) { tab(1, wide = false) })
    @Test fun foldedEntryDialog() = shoot("60_folded_entry_dialog", FOLDED) {
        content { com.bamnat.app.ui.money.EntryDialog(today, null, onSave = {}, onDismiss = {}) }
    }

    // [WORK-01] A new install: no pattern, no work style. Picking 교대 opens the pattern screen.
    @Test fun firstLaunchPickShift() = shoot("61_folded_first_launch_to_pattern", FOLDED, fresh(null) { tap(180f, 200f) })
    @Test fun foldedFieldWithoutPattern() = shoot("62_folded_field_no_pattern", FOLDED, fresh(WorkStyle.FIELD))

    // ---- M4 [SLP-01][MKT-07][MKT-08][PAY-12][FEAT-08][COMM-00] ----
    /** A night straight into a day shift two days ago, so the rest check has something to say. */
    private fun nightIntoDay(): Screen.() -> Unit = {
        val repo = app.container.scheduleRepository
        runBlocking {
            val types = repo.shiftTypes.first().associateBy { it.name }
            repo.setOverride(today.minusDays(4), ShiftOverride.Shift(types.getValue("야간").id))
            repo.setOverride(today.minusDays(3), ShiftOverride.Shift(types.getValue("주간").id))
        }
        settle()
    }
    @Test fun foldedDayRestWarning() = shoot("78_folded_day_rest_warning", FOLDED) {
        nightIntoDay().invoke(this); tap(DAY_X, DAY_Y)
    }
    @Test fun foldedDayHistory() = shoot("79_folded_day_change_history", FOLDED) {
        nightIntoDay().invoke(this); tap(DAY_X, DAY_Y); repeat(5) { scrollUp(800, across = 0.5f) }
    }
    @Test fun foldedMonthRest() = shoot("80_folded_month_rest_check", FOLDED) { nightIntoDay().invoke(this); scrollUp(700) }
    @Test fun foldedLeaveCarry() = shoot("81_folded_rules_leave_carry", FOLDED) {
        rulesEditor { companyRules.copy(annualLeaveDays = 15.0, annualLeaveCarry = 3.0) }.invoke(this)
        repeat(5) { scrollUp(800) }
    }
    @Test fun foldedBackupPassword() = shoot("82_folded_backup_password", FOLDED) {
        content { com.bamnat.app.ui.settings.ExportPasswordDialog(onDismiss = {}, onExport = {}) }
        settle()
    }
    @Test fun foldedCompanyShare() = shoot("83_folded_company_share", FOLDED) {
        content { com.bamnat.app.ui.settings.CompanyShareDialog(hasPay = true, onDismiss = {}, onShare = { _, _ -> }) }
        settle()
    }
    @Test fun foldedCompanyImport() = shoot("84_folded_company_import", FOLDED) {
        content {
            com.bamnat.app.ui.settings.CompanyImportDialog(
                com.bamnat.app.data.CompanySummary("1공장 A조", listOf("주간", "야간", "휴무"), 5, true, listOf("야간수당", "명절상여"), false),
                hasPay = true, onDismiss = {}, onApply = {},
            )
        }
        settle()
    }

    // ---- M5 [SLP-03][SLP-04][SLP-06][MKT-11] ----
    @Test fun foldedDaySleepTips() = shoot("85_folded_day_sleep_tips", FOLDED) {
        val repo = app.container.scheduleRepository
        runBlocking {
            val types = repo.shiftTypes.first().associateBy { it.name }
            repo.setOverride(today.minusDays(4), ShiftOverride.Shift(types.getValue("휴무").id))
            repo.setOverride(today.minusDays(3), ShiftOverride.Shift(types.getValue("야간").id))
        }
        settle()
        tap(DAY_X, DAY_Y)
        scrollUp(800, across = 0.5f)
        scrollUp(500, across = 0.5f)
    }
    @Test fun foldedTodaySleepLog() = shoot("86_folded_today_sleep_log", FOLDED) {
        runBlocking {
            val dao = app.container.database.sleepDao()
            val zone = java.time.ZoneId.systemDefault()
            fun ms(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(3, 23), endMillis = ms(2, 5, 30)))
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(2, 23, 30), endMillis = ms(1, 7)))
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(1, 22), endMillis = ms(0, 4)))
        }
        settle()
        tap(TODAY_X, DAY_Y)
        scrollUp(800, across = 0.5f)
        scrollUp(450, across = 0.5f)
    }
    @Test fun foldedStrictRest() = shoot("87_folded_month_rest_strict", FOLDED) {
        app.container.settingsRepository.update { it.copy(strictRest = true) }
        settle()
        scrollUp(700)
    }

    // ---- M6 [SLP-08][QA-01] ----
    @Test fun foldedSettingsHealthSleep() = shoot("89_folded_settings_health_sleep", FOLDED) {
        tab(4, wide = false)
        repeat(SETTINGS_SLEEP_SCROLLS) { scrollUp(800, across = 0.5f) }
    }
    @Test fun foldedTodayHealthSleep() = shoot("90_folded_today_health_sleep", FOLDED) {
        app.container.settingsRepository.update { it.copy(healthSleep = true) }
        runBlocking {
            val zone = java.time.ZoneId.systemDefault()
            fun ms(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            // One night tapped by hand; the watch measured the others.
            app.container.database.sleepDao().insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(2, 23, 30), endMillis = ms(1, 7)))
        }
        settle()
        tap(TODAY_X, DAY_Y)
        fun at(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m)
        app.container.healthSleep.showForTest(com.bamnat.app.sleep.HealthSleep.State.READY, listOf(
            com.bamnat.core.sleep.SleepEntry(start = at(5, 8, 40), end = at(5, 14, 10), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(4, 9), end = at(4, 15, 20), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(3, 23, 10), end = at(2, 5, 50), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(2, 23, 20), end = at(1, 6, 40), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(1, 22, 30), end = at(0, 4, 50), imported = true),
        ))
        settle()
        scrollUp(800, across = 0.5f)
        scrollUp(450, across = 0.5f)
    }

    private fun fresh(s: WorkStyle?, then: Screen.() -> Unit = {}): Screen.() -> Unit {
        runBlocking { app.container.scheduleRepository.clearPattern() }
        app.container.settingsRepository.update { it.copy(workStyle = s) }
        return then
    }

    private fun style(s: WorkStyle, then: Screen.() -> Unit = {}): Screen.() -> Unit {
        app.container.settingsRepository.update { it.copy(workStyle = s) }
        return then
    }

    @Test fun foldedPayLocked() = shoot("29_folded_pay_locked", FOLDED, payScreen(Tier.FREE, withProfile = true))
    @Test fun foldedPayBulkEdit() = shoot("30_folded_pay_bulk_edit", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            EditValueDialog(vm, profile, snap, ValueTarget.Base, java.time.YearMonth.now(), onDismiss = {})
        }
    })
    @Test fun foldedPayLineDetail() = shoot("31_folded_pay_line_detail", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            val result = vm.calculate(profile, snap, java.time.YearMonth.now())
            val line = result.lines.firstOrNull { it.kind == com.bamnat.core.pay.LineKind.NIGHT } ?: result.lines.first()
            LineDetailDialog(line, result, onDismiss = {})
        }
    })
    @Test fun foldedPayRaise() = shoot("32_folded_pay_raise", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        if (profile != null) RaiseDialog(vm, profile, ValueTarget.Base, onDismiss = {})
    })

    /** The launcher icon as a circle-masked launcher would show it, and the themed (monochrome) one. */
    @Test fun launcherIcon() {
        val size = 432
        val bmp = Bitmap.createBitmap(size * 2, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val icon = app.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        icon.setBounds(0, 0, size, size)
        canvas.save()
        canvas.clipPath(icon.iconMask)
        icon.draw(canvas)
        canvas.restore()
        canvas.translate(size.toFloat(), 0f)
        val mono = icon.monochrome!!.mutate()
        mono.setTint(0xFF3B4A6B.toInt())
        val r = size / 2f
        canvas.drawCircle(r, r, r, android.graphics.Paint().apply { color = 0xFFDDE3F5.toInt(); isAntiAlias = true })
        // The adaptive layers are 108dp drawn over 72dp; the mask sits in the middle.
        mono.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
        mono.draw(canvas)
        val out = File("build/screenshots/0_launcher_icon.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { st -> bmp.compress(Bitmap.CompressFormat.PNG, 100, st) }
    }

    /** Home screen widgets, rendered from the RemoteViews Glance produces. */
    @Test fun widgets() = runBlocking {
        val shots = listOf(
            Triple("23_widget_today", com.bamnat.app.widget.TodayWidget() as androidx.glance.appwidget.GlanceAppWidget, 180 to 150),
            Triple("24_widget_week", com.bamnat.app.widget.WeekWidget(), 340 to 110),
            Triple("25_widget_month", com.bamnat.app.widget.MonthWidget(), 340 to 330),
        )
        for ((name, widget, dims) in shots) {
            val (wDp, hDp) = dims
            @OptIn(androidx.glance.ExperimentalGlanceApi::class)
            val rv = widget.compose(app, size = androidx.compose.ui.unit.DpSize(wDp.dp, hDp.dp))
            val density = app.resources.displayMetrics.density
            val w = (wDp * density).toInt()
            val h = (hDp * density).toInt()
            val parent = android.widget.FrameLayout(app)
            val view = rv.apply(app, parent)
            parent.addView(view, android.widget.FrameLayout.LayoutParams(w, h))
            parent.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xFF7A8BA8.toInt())
            parent.draw(canvas)
            val out = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            out.outputStream().use { st -> bmp.compress(Bitmap.CompressFormat.PNG, 100, st) }
        }
    }

    @Test fun foldedDayAlarmNotice() = shoot("18_folded_day_alarm_notice", FOLDED) {
        tap(DAY_X, DAY_Y)
        scrollUp(DAY_ALARM_SCROLL)
        tap(180f, DAY_ALARM_EDIT_Y)
        tap(315f, 578f)
    }

    private companion object {
        // Touch points in dp, read off the folded screenshots.
        const val TAB_ROW_Y = 148f
        const val SETTINGS_SHIFT_TYPES_Y = 206f
        const val SETTINGS_PATTERN_Y = 139f
        const val DAY_ALARM_EDIT_Y = 519f
        const val DAY_ALARM_SCROLL = 600
        const val SETTINGS_SLEEP_SCROLLS = 4
        // Today's cell in the folded calendar (row 3, Wednesday).
        const val DAY_X = 135f
        const val DAY_Y = 290f
        /** Today's cell (10/17) on the same row. */
        const val TODAY_X = 286f
        const val EXTRA_DAY_X = 84f

        // Galaxy Z Fold6: cover screen 968x2376 px and main screen 1856x2160 px at 420 dpi.
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
    }
}

