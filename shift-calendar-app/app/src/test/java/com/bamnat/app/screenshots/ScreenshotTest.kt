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
import com.bamnat.core.pay.RestDayWork
import com.bamnat.app.ui.settings.ExtraKindsScreen
import com.bamnat.app.ui.settings.ExtraKindsViewModel
import com.bamnat.app.ui.pay.LineDetailDialog
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.pay.RaiseDialog
import com.bamnat.app.ui.pay.ValueTarget
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.Snapshot
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.money.FixedCost
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.app.ui.Fmt
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.foundation.layout.fillMaxSize
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
    /** The day most day screenshots open: 잔업 2시간 and a memo with an amount. */
    private val dayCell = today.minusDays(2)
    /** 특근 09–18 on a day off, before today; 야간 대근 on a day off after it. */
    private lateinit var restDayExtra: LocalDate
    private lateinit var coverDay: LocalDate

    /** A 대근 of [t]: that shift's hours, as the calendar adds it. */
    private fun cover(t: ShiftType) =
        ExtraEntry(ExtraKind.COVER_ID, t.start, t.end, t.breakMinutes, t.breakStart.takeIf { t.breakMinutes > 0 }, coveredShiftId = t.id)

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

        // A swapped shift, 잔업, 특근 and a 야간 대근 on days off, and a skipped alarm this month [EXTRA-01].
        repo.setOverride(today.plusDays(3), ShiftOverride.Shift(evening))
        repo.addExtra(dayCell, ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, night, night, off))
        val nightType = repo.shiftTypes.first().first { it.id == night }
        restDayExtra = (1..10).map { today.minusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == off }
        repo.addExtra(restDayExtra, ExtraEntry(ExtraKind.SPECIAL_ID, LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0)))
        coverDay = (1..10).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == off }
        repo.addExtra(coverDay, cover(nightType))
        repo.addExtra((1..6).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) != off }, ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))
        repo.setAlarmSkipped(today.plusDays(6), true)
        // [FEAT-10] 반차 today and a whole day of 연차 for the check-up on the first working day after it.
        repo.setLeave(today, Leave(LeaveKind.ANNUAL, 2))
        val checkUp = (5..12).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == day }
        repo.setLeave(checkUp, Leave())
        // [FEAT-02] 주간 then a 야간 대근 on the next working day after the check-up.
        val doubleDay = (1..6).map { checkUp.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == day }
        repo.addExtra(doubleDay, cover(nightType))
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
                // Dialogs and sheets live in their own windows, so they are drawn on top by hand, oldest first.
                for (dialog in shownDialogs()) {
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

    /** Dialogs still open, oldest first: a time picker over a sheet is two. */
    private fun shownDialogs() = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()

    /**
     * Lets Room deliver on its background threads and the UI draw, then finishes animations. State
     * written outside composition (a tap, new content) is applied by hand: after many tests in one
     * JVM, Compose's own apply loop can stop being run by the reset main looper, and then a tap or a
     * new screen is never drawn.
     */
    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /**
     * Touches go straight to the window. [QA-02] Taps find their target by the words on it in the
     * topmost window (a dialog or sheet when one is open), scrolling until it shows, so a layout
     * change can no longer land a tap on something else; only the tab bar and drags use points.
     */
    inner class Screen(private val scenario: ActivityScenario<MainActivity>) {
        private fun topWindow(activity: MainActivity): View = shownDialogs().lastOrNull()?.window?.decorView ?: activity.window.decorView

        private fun rootOf(v: View): ViewRootForTest? {
            if (v is ViewRootForTest) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) rootOf(v.getChildAt(i))?.let { return it }
            return null
        }

        private fun SemanticsNode.words(): List<String> =
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text)

        /** The first node on screen showing [text] (whole words with [exact], the start with [prefix]): clickable ones first. */
        private fun find(text: String, exact: Boolean, prefix: Boolean): SemanticsNode? {
            var found: SemanticsNode? = null
            scenario.onActivity { activity ->
                val root = rootOf(topWindow(activity)) ?: return@onActivity
                val shown = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter { n ->
                    val b = n.boundsInWindow
                    b.width > 1f && b.height > 1f && n.words().any { w -> if (exact) w == text else if (prefix) w.startsWith(text) else text in w }
                }
                found = shown.firstOrNull { it.config.contains(SemanticsActions.OnClick) } ?: shown.firstOrNull()
            }
            return found
        }

        /** Scrolls the content up until [text] shows, and returns where it is. */
        private fun reach(text: String, exact: Boolean, prefix: Boolean, scrolls: Int, across: Float): SemanticsNode {
            repeat(scrolls + 1) { i ->
                find(text, exact, prefix)?.let { return it }
                if (i < scrolls) scrollUp(350, across)
            }
            error("화면에서 ‘$text’를 찾지 못했어요. 보이는 글자: " + shownWords().joinToString(" | ").take(1500))
        }

        /** Every word on screen, for a failure message that says what was there instead. */
        private fun shownWords(): List<String> {
            val words = mutableListOf<String>()
            scenario.onActivity { activity ->
                val root = rootOf(topWindow(activity)) ?: return@onActivity
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    .filter { it.boundsInWindow.width > 1f && it.boundsInWindow.height > 1f }
                    .forEach { words += it.words() }
            }
            return words
        }

        private fun touch(at: Offset, hold: Int = 1) {
            scenario.onActivity { activity ->
                val target = topWindow(activity)
                val start = SystemClock.uptimeMillis()
                val n = hold + 1
                repeat(n) { i ->
                    val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; n - 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    target.dispatchTouchEvent(MotionEvent.obtain(start, start + i * 16L, action, at.x, at.y, 0))
                    shadowOf(Looper.getMainLooper()).idle()
                    compose.mainClock.advanceTimeBy(16)
                }
            }
            settle()
        }

        /** [QA-02] Taps what shows [text], scrolling down to it first when it is below. */
        fun tapText(text: String, exact: Boolean = false, prefix: Boolean = false, scrolls: Int = 8, across: Float = 0.5f) {
            reach(text, exact, prefix, scrolls, across)
            // Where it is once a scroll or late data has stopped moving things.
            settle()
            touch(reach(text, exact, prefix, 0, across).boundsInWindow.center)
        }

        /** Scrolls until [text] shows near the top of the screen, without tapping it. */
        fun reveal(text: String, exact: Boolean = false, across: Float = 0.5f) {
            val node = reach(text, exact, prefix = false, scrolls = 10, across = across)
            // Bring it up from the bottom edge so what follows it shows too, below a sheet's fixed day header.
            val dp = app.resources.displayMetrics.density
            val y = node.boundsInWindow.top / dp
            val to = app.resources.configuration.screenHeightDp / 3
            if (y > to + 80) scrollUp((y - to).toInt(), across)
        }

        /** [DAY-02] Opens a day group when it is closed; a group with something to say may already be open. */
        fun openGroup(title: String) {
            val node = reach(title, exact = true, prefix = false, scrolls = 10, across = 0.5f)
            if (node.config.getOrNull(SemanticsActions.OnClick)?.label == "펼치기") touch(node.boundsInWindow.center)
        }

        /** Holds a finger on what shows [text], for its quick menu. */
        fun longPressText(text: String, prefix: Boolean = false) = touch(reach(text, false, prefix, 0, 0.5f).boundsInWindow.center, hold = 40)

        /** [DAY-01] Opens a day: a tap selects it and shows its preview, a tap on the chosen day opens it whole. */
        fun openDay(date: LocalDate) {
            val title = Fmt.dayTitle(date)
            if (find(title, exact = false, prefix = true)?.config?.getOrNull(SemanticsProperties.Selected) != true) tapText(title, prefix = true, scrolls = 0)
            tapText(title, prefix = true, scrolls = 0)
        }

        /** Selects a day without opening it. */
        fun selectDay(date: LocalDate) = tapText(Fmt.dayTitle(date), prefix = true, scrolls = 0)

        private fun gesture(points: (width: Int, height: Int, density: Float) -> List<Pair<Float, Float>>) {
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val path = points(view.width, view.height, activity.resources.displayMetrics.density)
                // An open dialog takes the touches; its window is centered on the screen.
                val target = topWindow(activity)
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

        /** Replaces the activity's content, for screens that are slow to reach by touch. */
        fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
            scenario.onActivity { activity -> activity.setContent { BamnatTheme { body() } } }
        }

        /** Taps a point given in dp from the top-left, or from the right edge when [fromRight]. */
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
    @Test fun foldedDayDetail() = shoot("2_folded_day_sheet", FOLDED) { openDay(dayCell) }
    @Test fun foldedDaySheetScrolled() = shoot("2b_folded_day_sheet_extra", FOLDED) {
        openDay(dayCell)
        reveal("메모", exact = true)
    }
    @Test fun foldedExtraWorkDay() = shoot("19_folded_extra_work_day", FOLDED) {
        openDay(restDayExtra)
        reveal("추가 근무", exact = true)
    }
    @Test fun foldedTimeWheel() = shoot("20_folded_time_wheel", FOLDED) {
        tab(4, wide = false)
        tapText("근무 유형", exact = true)
        tapText("주간", exact = true)
        tapText("시작", exact = true)
    }
    @Test fun foldedPatternChange() = shoot("21_folded_pattern_change", FOLDED) {
        tab(4, wide = false)
        tapText("근무 패턴", exact = true)
        tapText("저장", exact = true)
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
        tapText("일반 메모", prefix = true)
    }
    @Test fun foldedTrash() = shoot("11_folded_trash", FOLDED) {
        tab(2, wide = false)
        tapText("휴지통", exact = true)
    }
    @Test fun unfoldedMemos() = shoot("12_unfolded_memos", UNFOLDED) { tab(2, wide = true) }
    @Test fun foldedShiftTypes() = shoot("13_folded_shift_types", FOLDED) {
        tab(4, wide = false)
        tapText("근무 유형", exact = true)
    }
    @Test fun foldedPattern() = shoot("14_folded_pattern", FOLDED) {
        tab(4, wide = false)
        tapText("근무 패턴", exact = true)
    }
    @Test fun foldedDayAlarmEditor() = shoot("15_folded_day_alarm_editor", FOLDED) {
        openDay(dayCell)
        openGroup("알람")
        tapText("이 날만 알람")
    }

    @Test fun foldedShiftTypeEditor() = shoot("16_folded_shift_type_editor", FOLDED) {
        tab(4, wide = false)
        tapText("근무 유형", exact = true)
        tapText("주간", exact = true)
    }

    @Test fun foldedShiftTypeAlarms() = shoot("17_folded_shift_type_alarms", FOLDED) {
        tab(4, wide = false)
        tapText("근무 유형", exact = true)
        tapText("주간", exact = true)
        reveal("알람 추가")
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

    /** [EXTRA-03] 대근 1회 기본급의 10% for 8 hours with the 연장·야간 가산 on top, 특근 a fixed amount. */
    /** [EXTRA-08] 대근 as the thread's company pays it: 8시간에 기본급 10%, 이 일 8시간 넘으면 50%, 야간 50%. */
    private val companyCover = ExtraRule.lawUnit(overtime = 0.5, night = 0.5, holiday = 0.5, restAsHoliday = false).let { law ->
        law.copy(unit = com.bamnat.core.pay.PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480, premiums = law.premiums.map {
            if (it.key == com.bamnat.core.pay.Premium.OVERTIME) it.copy(scope = com.bamnat.core.pay.PremiumScope.Over(480)) else it
        })
    }
    /** How 1.8 and 1.9.0 stored the same 대근, before the boxes. */
    private val legacyCover = ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = 0.1, coverMinutes = 480, legalPremiums = true)
    private val companyRules = CompanyRules(
        extra = mapOf(
            ExtraKind.COVER_ID to companyCover,
            // A way from 1.9.0, kept as it was.
            ExtraKind.SPECIAL_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 150_000),
        ),
        annualLeaveDays = 15.0,
    )

    private fun payScreen(
        tier: Tier, withProfile: Boolean,
        adjust: (com.bamnat.core.pay.PayProfile) -> com.bamnat.core.pay.PayProfile = { it },
        body: (@androidx.compose.runtime.Composable (PayViewModel) -> Unit)? = null,
    ): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = tier) }
        val vm = PayViewModel(c)
        runBlocking { c.payStore.save(if (withProfile) adjust(vm.sampleProfile().copy(rules = companyRules)) else null, keepUndo = false) }
        settle()
        content { if (body != null) body(vm) else PayScreen(vm, onBack = {}) }
        settle()
    }

    @Test fun foldedDayPay() = shoot("33_folded_day_pay", FOLDED) {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = Tier.PRO) }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        settle()
        openDay(restDayExtra)
        openGroup("돈")
        reveal("돈", exact = true)
    }
    @Test fun foldedCompanyRules() = shoot("34_folded_company_rules", FOLDED, payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {}, onOpenKinds = {})
    })
    @Test fun foldedCompanyRulesExtra() = shoot("35_folded_company_rules_extra", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {}, onOpenKinds = {})
        }.invoke(this)
        scrollUp(700)
    }
    @Test fun foldedPayDays() = shoot("36_folded_pay_days", FOLDED) {
        payScreen(Tier.PRO, withProfile = true).invoke(this)
        scrollUp(880)
    }

    @Test fun foldedLeaveDay() = shoot("37_folded_leave_day", FOLDED) {
        openDay(today)
        reveal("휴가", exact = true)
    }
    @Test fun foldedShiftIcons() = shoot("38_folded_shift_icons", FOLDED) {
        tab(4, wide = false)
        tapText("근무 유형", exact = true)
        tapText("주간", exact = true)
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
        tapText("근무 유형", exact = true)
        tapText("주간", exact = true)
        scrollUp(150)
    }
    @Test fun foldedRulesLeave() = shoot("43_folded_rules_leave", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {}, onOpenKinds = {})
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
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {}, onOpenKinds = {})
        }
        settle()
    }
    // ---- M9 [EXTRA-01..06][DAY-01..03][VIS-01] ----
    /** The 잔업·특근·대근 screen with pay set up, so the editor shows the company's way and the preview. */
    private fun kindsScreen(rules: (nightId: Long) -> CompanyRules = { companyRules }): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(tier = Tier.PRO) }
        val nightId = runBlocking { c.scheduleRepository.snapshot.first().shiftTypes.first { it.name == "야간" }.id }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = rules(nightId)), keepUndo = false) }
        settle()
        val vm = ExtraKindsViewModel(c)
        content { ExtraKindsScreen(vm, onBack = {}, onOpenPay = {}) }
        settle()
    }
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
    @Test fun foldedDayPreview() = shoot("97_folded_day_preview", FOLDED) { selectDay(dayCell) }
    @Test fun foldedCoverDay() = shoot("98_folded_cover_day", FOLDED) { openDay(coverDay) }
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
        kindsScreen { night ->
            companyRules.copy(extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(night to
                companyCover.copy(unit = com.bamnat.core.pay.PayUnit.PER_TIME, amount = 100_000, coverMinutes = 480))))
        }.invoke(this)
        tapText("대근", exact = true)
        tapText("야간 ·", prefix = true)
        reveal("야간 대근", exact = true)
    }
    @Test fun foldedExtraKinds() = shoot("76_folded_extra_kinds", FOLDED) {
        tab(4, wide = false)
        tapText("휴무일 근무 지정", exact = true)
    }
    // ---- [EXTRA-07][EXTRA-08] 휴무일 근무 지정 ----
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
    @Test fun foldedFieldDayShifts() = shoot("77_folded_field_day_shifts_toggle", FOLDED, style(WorkStyle.FIELD) { openDay(dayCell); reveal("근무", exact = true) })

    /** [SLP-14] 오늘의 흐름 through a first night and the day after, at fixed times. */
    private fun flowCards(vararg cards: com.bamnat.core.sleep.TodayFlow.Card): Screen.() -> Unit = {
        content {
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    cards.forEach { com.bamnat.app.ui.calendar.TodayFlowCard(it, 420, 60, null, { _, _ -> }, {}, { _, _ -> }, {}) }
                }
            }
        }
    }
    private val flowResolver by lazy {
        val d = ShiftType(11, "주간", "주", 0xFF2F80ED, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
        val n = ShiftType(12, "야간", "야", 0xFF6C5CE7, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
        val o = ShiftType(13, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true)
        com.bamnat.core.schedule.ScheduleResolver(listOf(d, n, o),
            WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(d.id, n.id, n.id, o.id, o.id)),
            listOf(com.bamnat.core.model.DayEntry(LocalDate.of(2026, 11, 12), alarmSkipped = true)))
    }
    private fun flowAt(d: Int, h: Int, m: Int, log: List<com.bamnat.core.sleep.SleepEntry> = emptyList()) =
        com.bamnat.core.sleep.TodayFlow.of(java.time.LocalDateTime.of(2026, 11, d, h, m), flowResolver, true, log)
    @Test fun foldedTodayFlowNight() = shoot("105_folded_today_flow_night", FOLDED, flowCards(
        flowAt(3, 14, 30), flowAt(4, 1, 0), flowAt(4, 7, 20), flowAt(6, 10, 0),
    ))
    @Test fun foldedTodayFlowSleep() = shoot("106_folded_today_flow_sleep", FOLDED, flowCards(
        flowAt(4, 11, 0, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), null))),
        flowAt(4, 14, 40, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), java.time.LocalDateTime.of(2026, 11, 4, 14, 22)))),
        flowAt(11, 10, 0),
        flowAt(5, 2, 0, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), null))),
    ))
    @Test fun foldedTodayFlowDark() = shoot("107_folded_today_flow_dark", "w369dp-h905dp-port-night-420dpi", flowCards(
        flowAt(3, 14, 30), flowAt(4, 7, 20), flowAt(6, 21, 45),
    ))


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
        nightIntoDay().invoke(this); openDay(today.minusDays(3)); reveal("몸", exact = true)
    }
    @Test fun foldedDayHistory() = shoot("79_folded_day_change_history", FOLDED) {
        nightIntoDay().invoke(this); openDay(today.minusDays(3)); openGroup("기록"); reveal("기록", exact = true)
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
        openDay(dayCell)
        openGroup("몸")
        reveal("몸", exact = true)
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
        openDay(today)
        openGroup("몸")
        reveal("몸", exact = true)
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
        openDay(today)
        openGroup("몸")
        fun at(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m)
        app.container.healthSleep.showForTest(com.bamnat.app.sleep.HealthSleep.State.READY, listOf(
            com.bamnat.core.sleep.SleepEntry(start = at(5, 8, 40), end = at(5, 14, 10), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(4, 9), end = at(4, 15, 20), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(3, 23, 10), end = at(2, 5, 50), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(2, 23, 20), end = at(1, 6, 40), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(1, 22, 30), end = at(0, 4, 50), imported = true),
        ))
        settle()
        reveal("몸", exact = true)
    }

    // ---- M7 [PAYX-14][PAYX-15][UX-01] ----
    private val closeOn20: (com.bamnat.core.pay.PayProfile) -> com.bamnat.core.pay.PayProfile =
        { it.copy(period = com.bamnat.core.pay.PayPeriod(endDay = 20, payDay = 25), rules = it.rules.copy(holidayByShiftStart = true)) }
    @Test fun foldedPayPeriod() = shoot("91_folded_pay_period", FOLDED, payScreen(Tier.PRO, withProfile = true, adjust = closeOn20))
    @Test fun foldedRulesPeriod() = shoot("92_folded_rules_period", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = closeOn20) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, java.time.YearMonth.now(), onClose = {}, onOpenKinds = {})
        }.invoke(this)
        scrollUp(RULES_PERIOD_SCROLL)
    }
    @Test fun foldedSteppers() = shoot("93_folded_steppers_fixed_width", FOLDED) {
        content {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                for (m in listOf(5, 60, 90, 23 * 60 + 55)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text("알람", androidx.compose.ui.Modifier.weight(1f))
                        com.bamnat.app.ui.components.MinutesStepper(minutes = m, onChange = {}, step = 1)
                    }
                }
            }
        }
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

    /** [DAY-02] The day's own alarms with the line that says what they replace; the old notice dialog is gone. */
    @Test fun foldedDayAlarmGroup() = shoot("18_folded_day_alarm_group", FOLDED) {
        openDay(today)
        openGroup("알람")
        reveal("알람", exact = true)
    }

    private companion object {
        const val SETTINGS_SLEEP_SCROLLS = 4
        const val RULES_PERIOD_SCROLL = 420

        // Galaxy Z Fold6: cover screen 968x2376 px and main screen 1856x2160 px at 420 dpi.
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
    }
}

