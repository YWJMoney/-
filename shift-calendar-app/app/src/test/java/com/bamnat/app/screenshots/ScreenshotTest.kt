package com.bamnat.app.screenshots

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
import com.bamnat.core.consent.ConsentItem
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

    /** [DAILY-09] Stands in for the fold's hinge sensor; nothing is published unless a test asks. */
    @get:Rule val fold = androidx.window.testing.layout.WindowLayoutInfoPublisherRule()

    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val today = LocalDate.now()
    /** The day most day screenshots open: 잔업 2시간 and a memo with an amount. */
    private val dayCell = today.minusDays(2)
    /** 특근 09–18 on a day off, before today; 야간 대근 on a day off after it. */
    private lateinit var restDayExtra: LocalDate
    private lateinit var coverDay: LocalDate
    /** Today, or the next working day when today is off: a day off has no leave to take. */
    private lateinit var halfDay: LocalDate

    /** A 대근 of [t]: that shift's hours, as the calendar adds it. */
    private fun cover(t: ShiftType) =
        ExtraEntry(ExtraKind.COVER_ID, t.start, t.end, t.breakMinutes, t.breakStart.takeIf { t.breakMinutes > 0 }, coveredShiftId = t.id)

    @Before
    fun seed() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val c = app.container
        // [ONBOARD-01] Through the consent pages already; the shots of them start over.
        c.consentStore.markSeen(ConsentItem.entries)
        // [CAL-17] The one-time tip on the first date tap has its own shot (295); the others tap dates past it.
        com.bamnat.app.ui.calendar.HintStore(app).markDone(com.bamnat.app.ui.calendar.HintStore.LONG_PRESS)
        // [GUIDE-02][GUIDE-03] A settled user: the first-days card and the tips are put away, so the shots show the screens.
        com.bamnat.app.data.GuideStore.get(app).let { g ->
            listOf("guide.checklist", "guide.tips").forEach { id -> g.setOn(com.bamnat.core.guide.FeatureRegistry.get(id)!!, "main", false) }
        }
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
        // [FEAT-10] 반차 on [halfDay] and a whole day of 연차 for the check-up on the first working day after it.
        halfDay = (0..4).map { today.plusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) != off }
        repo.setLeave(halfDay, Leave(LeaveKind.ANNUAL, 2))
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

    private fun shoot(name: String, qualifiers: String, actions: Screen.() -> Unit = {}) = shoot(name, qualifiers, 1f, actions)

    /** [VIS-02] With the phone's text size at [fontScale]. */
    private fun shoot(name: String, qualifiers: String, fontScale: Float, actions: Screen.() -> Unit = {}) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
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
        /** [DAILY-09] The fold half open with its hinge across the middle (Flex, 탁상 자세). */
        fun halfOpen() {
            scenario.onActivity { activity ->
                fold.overrideWindowLayoutInfo(androidx.window.testing.layout.TestWindowLayoutInfo(listOf(
                    androidx.window.testing.layout.FoldingFeature(activity, state = androidx.window.layout.FoldingFeature.State.HALF_OPENED,
                        orientation = androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL))))
            }
            settle()
        }

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

        /** Types [text] into the field labelled [label], as the keyboard would. */
        fun typeInto(label: String, text: String) {
            reach(label, exact = true, prefix = false, scrolls = 4, across = 0.5f)
            scenario.onActivity { activity ->
                val root = rootOf(topWindow(activity)) ?: return@onActivity
                val field = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    .firstOrNull { n -> n.config.contains(SemanticsActions.SetText) && n.words().any { it == label } }
                    ?: error("‘$label’ 입력칸을 찾지 못했어요")
                field.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString(text))
            }
            settle()
        }

        /** Holds a finger on what shows [text], for its quick menu. */
        fun longPressText(text: String, prefix: Boolean = false) = touch(reach(text, false, prefix, 0, 0.5f).boundsInWindow.center, hold = 40)

        /** [DAY-01] Opens a day: a tap selects it and shows its preview, a tap on the chosen day opens it whole. */
        fun openDay(date: LocalDate) {
            val title = Fmt.dayTitle(date)
            if (find(title, exact = false, prefix = true)?.config?.getOrNull(SemanticsProperties.Selected) != true) tapText(title, prefix = true, scrolls = 0)
            tapText(title, prefix = true, scrolls = 0)
        }

        /** [SCALE-01] [openDay] on a screen too short to show the whole month: scrolls down to the day first. */
        fun openDayBelow(date: LocalDate) {
            val title = Fmt.dayTitle(date)
            tapText(title, prefix = true, scrolls = 6)
            tapText(title, prefix = true, scrolls = 6)
        }

        /** Selects a day without opening it. */
        fun selectDay(date: LocalDate) = tapText(Fmt.dayTitle(date), prefix = true, scrolls = 0)

        /** [CAL-01] Drags a finger from one day to another, slowly, as quick input's painting stroke. */
        fun dragDays(from: LocalDate, to: LocalDate) {
            val a = reach(Fmt.dayTitle(from), exact = false, prefix = true, scrolls = 0, across = 0.5f).boundsInWindow.center
            val b = reach(Fmt.dayTitle(to), exact = false, prefix = true, scrolls = 0, across = 0.5f).boundsInWindow.center
            gesture { _, _, _ -> (0..24).map { i -> (a.x + (b.x - a.x) * i / 24) to (a.y + (b.y - a.y) * i / 24) } }
        }

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

        /** [SET-01] Opens a group from the settings list. */
        fun settingsPage(title: String, wide: Boolean = false) {
            tab(4, wide)
            tapText(title, exact = true)
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

        /** Drags the content down by [dp] from near the top (slowly), to bring back what scrolled away. */
        fun scrollDown(dp: Int, across: Float = 0.5f) = gesture { w, h, d ->
            val x = w * across
            val from = h * 0.3f
            (0..40).map { x to from + dp * d * it / 40 } + listOf(x to from + dp * d)
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
    // ---- [CAL-03][DAY-04] 그 주만 보기, 하루 화면 순서 ----
    @Test fun foldedWeekOnly() = shoot("174_folded_week_only", FOLDED) {
        app.container.settingsRepository.update { it.copy(phoneDayView = com.bamnat.app.data.PhoneDayView.WEEK) }
        settle()
        selectDay(dayCell)
        // Fails when the month did not fold to the week.
        reveal("달 전체 보기", exact = true)
    }
    @Test fun foldedDayGroupOrder() = shoot("175_folded_day_group_order", FOLDED) {
        app.container.settingsRepository.update { it.copy(dayGroupOrder = listOf("memo", "alarm")) }
        settle()
        openDay(dayCell)
        tapText("순서 바꾸기", exact = true)
        check(shownDialogs().isNotEmpty()) { "순서 창이 열리지 않았어요" }
    }
    @Test fun foldedExtraWorkDay() = shoot("19_folded_extra_work_day", FOLDED) {
        openDay(restDayExtra)
        reveal("추가 근무", exact = true)
    }
    @Test fun foldedTimeWheel() = shoot("20_folded_time_wheel", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
        tapText("주간", exact = true)
        tapText("시작", exact = true)
    }
    @Test fun foldedPatternChange() = shoot("21_folded_pattern_change", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 주기", exact = true)
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
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
    }
    @Test fun foldedPattern() = shoot("14_folded_pattern", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 주기", exact = true)
    }
    @Test fun foldedDayAlarmEditor() = shoot("15_folded_day_alarm_editor", FOLDED) {
        openDay(dayCell)
        openGroup("알람")
        tapText("이 날만 알람")
    }

    @Test fun foldedShiftTypeEditor() = shoot("16_folded_shift_type_editor", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
        tapText("주간", exact = true)
    }

    @Test fun foldedShiftTypeAlarms() = shoot("17_folded_shift_type_alarms", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
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
    // [REL-13] 알람 점검: the checks with their fix buttons and a test alarm waiting.
    @Test fun foldedAlarmChecks() = shoot("350_folded_alarm_checks", FOLDED) {
        val c = app.container
        c.alarmScheduler.scheduleTest()
        val vm = DiagnosticsViewModel(c)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }
    @Test fun unfoldedAlarmChecks() = shoot("351_unfolded_alarm_checks", UNFOLDED) {
        val vm = DiagnosticsViewModel(app.container)
        content { DiagnosticsScreen(vm, onBack = {}) }
    }
    // [OPS-23] 설정 › 앱 정보 › 오픈소스 라이선스.
    @Test fun foldedOpenSourceLicenses() = shoot("352_folded_open_source_licenses", FOLDED) {
        content { com.bamnat.app.ui.settings.OpenSourceLicensesDialog(onDismiss = {}) }
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
        c.settingsRepository.update { it.copy(previewTier = tier.takeIf { t -> t != Tier.PRO }) }
        val vm = PayViewModel(c)
        runBlocking { c.payStore.save(if (withProfile) adjust(vm.sampleProfile().copy(rules = companyRules)) else null, keepUndo = false) }
        settle()
        content { if (body != null) body(vm) else PayScreen(vm, onBack = {}) }
        settle()
    }

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
        openDay(halfDay)
        reveal("휴가", exact = true)
    }
    @Test fun foldedShiftIcons() = shoot("38_folded_shift_icons", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
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
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
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
        c.settingsRepository.update { it.copy(previewTier = null) }
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
        c.settingsRepository.update { it.copy(previewTier = null) }
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
        app.container.settingsRepository.update { it.copy(memoInCells = true) }
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
        val month = java.time.YearMonth.now()
        val sample = vm.sampleProfile().copy(estimatedFields = setOf(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE))
        content {
            com.bamnat.app.ui.pay.PayWizard(sample, {}, {}, snapshot = snap, preview = { vm.preview(it, snap, month) }, startAt = "summary")
        }
        // The month is worked out off the main thread.
        repeat(3) { settle() }
    }
    // [PAYS-01] Answers left 모르겠어요 show as a range, and the sheet says what each one moves.
    private val unknownAnswers = setOf(
        com.bamnat.core.pay.Unknowns.FIVE_OR_MORE, com.bamnat.core.pay.Unknowns.REST_DAY_WORK, com.bamnat.core.pay.Unknowns.MONTHLY_HOURS,
    )
    @Test fun foldedPayRange() = shoot("150_folded_pay_range", FOLDED,
        payScreen(Tier.PRO, withProfile = true, adjust = { it.copy(estimatedFields = unknownAnswers) }))
    @Test fun foldedPayUnknowns() = shoot("151_folded_pay_unknowns", FOLDED) {
        payScreen(Tier.PRO, withProfile = true, adjust = { it.copy(estimatedFields = unknownAnswers) }).invoke(this)
        tapText("눌러서 확인 필요")
    }
    // [PAYS-02] 명세서로 맞춰 보기: the form, then what the closest settings change.
    private fun payslipMatch(): Screen.() -> Unit = payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) com.bamnat.app.ui.pay.PayslipMatchDialog(vm, profile, snap, java.time.YearMonth.now(), onDismiss = {})
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
    @Test fun foldedShareLink() = shoot("48_folded_share_link", FOLDED) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content {
            com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {},
                preview = com.bamnat.app.share.ShareLinkState("Ab3dE", "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv", "t",
                    java.time.Instant.now().plus(java.time.Duration.ofDays(30)), "민지"))
        }
    }
    // [SHR-11] A shop shares its 매출 and a freelancer their 일정 and 일감 수입, with the figures shown before sending.
    private fun shareSheet(style: WorkStyle): Screen.() -> Unit = style(style) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}) }
    }
    @Test fun foldedShareLinkBusiness() = shoot("200_folded_share_link_business", FOLDED, shareSheet(WorkStyle.BUSINESS))
    @Test fun foldedShareLinkField() = shoot("201_folded_share_link_field", FOLDED, shareSheet(WorkStyle.FIELD))
    @Test fun foldedShareLinkBusinessLargeText() = shoot("202_folded_share_link_business_large_text", FOLDED, 1.5f, shareSheet(WorkStyle.BUSINESS))
    // [RECV-05] 받을 돈 for each kind of work: written debts, unpaid jobs, the job and receivable sheets.
    private fun owed(s: WorkStyle, wide: Boolean = false, then: Screen.() -> Unit = {}): Screen.() -> Unit = style(s) {
        val c = app.container
        runBlocking {
            val money = c.moneyRepository
            money.save(com.bamnat.core.money.Receivable(who = "라마바전자", title = "9월 납품 대금", amount = 1_650_000, date = today.minusDays(40),
                due = today.minusDays(6), kind = com.bamnat.core.money.ReceivableKind.SALE))
            val part = money.save(com.bamnat.core.money.Receivable(who = "가나다식당", title = "단체 도시락 외상", amount = 480_000,
                date = today.minusDays(12), due = today.plusDays(2), kind = com.bamnat.core.money.ReceivableKind.SALE, note = "월말에 한 번에 준다고 함"))
            money.receive(part, 200_000, today.minusDays(3))
            money.save(com.bamnat.core.money.Receivable(who = "민지", title = "여행 경비", amount = 120_000, date = today.minusDays(20),
                kind = com.bamnat.core.money.ReceivableKind.LOAN))
        }
        settle()
        val vm = com.bamnat.app.ui.money.OwedViewModel(c)
        content { com.bamnat.app.ui.money.OwedScreen(vm, wide = wide, onBack = {}, today = today) }
        settle()
        then()
    }
    /** [NURS-01][NURS-02] A month typed from the keypad, with 역순 and a night run without days off flagged. */
    private fun dutyTyping(): Screen.() -> Unit = style(WorkStyle.SHIFT) {
        val c = app.container
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val t = snap.shiftTypes.associateBy { it.name }
        val month = java.time.YearMonth.of(2026, 11)
        val codes = listOf("주간", "주간", "오후", "주간", "야간", "야간", "주간", "휴무", "휴무", "오후", "오후", "야간")
        val initial = codes.mapIndexed { i, n -> month.atDay(i + 1) to ShiftOverride.Shift(t.getValue(n).id) as ShiftOverride }.toMap()
        content { com.bamnat.app.ui.calendar.DutyTypingSheet(snap, month, onDismiss = {}, onSave = {}, initial = initial) }
        settle()
    }
    @Test fun foldedDutyTyping() = shoot("364_folded_duty_typing", FOLDED, dutyTyping())
    @Test fun phoneDutyTypingHugeText() = shoot("365_phone_duty_typing_huge_text", PHONE, 1.5f, dutyTyping())

    /** [INV-30][INV-31][INV-32] Trades found in 체결 notifications, waiting for 확인, and the coin/stock badges. */
    private fun foundTrades(then: Screen.() -> Unit = {}): Screen.() -> Unit = style(WorkStyle.INVEST) {
        val c = app.container
        val repo = com.bamnat.app.data.InvestRepository.of(c.database)
        val at = System.currentTimeMillis()
        runBlocking {
            val acc = repo.ensureAccount()
            repo.saveCash(com.bamnat.core.invest.CashFlow(accountId = acc, date = today.minusDays(5), kind = com.bamnat.core.invest.CashKind.DEPOSIT, amount = 5_000_000))
            repo.saveTrade(com.bamnat.core.invest.Trade(accountId = acc, date = today.minusDays(1), symbol = "005930", name = "삼성전자",
                side = com.bamnat.core.invest.Side.BUY, quantity = 10.0, price = 71_500.0, tags = listOf("알림:한국투자")))
            repo.saveTrade(com.bamnat.core.invest.Trade(accountId = acc, date = today.minusDays(1), symbol = "BTC", name = "비트코인",
                market = com.bamnat.core.invest.Market.CRYPTO, side = com.bamnat.core.invest.Side.BUY, quantity = 0.01, price = 95_000_000.0, tags = listOf("알림:업비트")))
            listOf(
                "[체결통보] 매도 삼성전자(005930) 3주 체결 단가 73,000원" to ("한국투자" to "com.truefriend.neosmarta"),
                "[업비트] 매수 체결 KRW-ETH 0.25 ETH @ 4,800,000원" to ("업비트" to "com.dunamu.exchange.upbit"),
                "토스증권 구매 완료 애플(AAPL) 2주 $228.50" to ("토스" to "viva.republica.toss"),
            ).forEachIndexed { i, (text, src) ->
                val alert = com.bamnat.core.invest.TradeAlertParser.parse(text, src.second)!!
                repo.addAlert(com.bamnat.core.invest.TradeAlerts.of(alert, src.first, at - i * 600_000L))
            }
        }
        settle()
        val vm = com.bamnat.app.ui.invest.InvestViewModel(c, com.bamnat.app.data.PersonalCalendar.MAIN)
        content { com.bamnat.app.ui.invest.InvestScreen(vm, wide = false, snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }) }
        settle()
        then()
    }
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

    // [SETUP-01][SETUP-02] A new install: no pattern, no work style. One question a screen until the summary.
    @Test fun firstLaunchSetupStyle() = shoot("61_folded_setup_style", FOLDED, fresh(null))
    @Test fun firstLaunchSetupCompany() = shoot("61b_folded_setup_company", FOLDED, fresh(null) { tapText("순서가 돌아가요") })
    @Test fun firstLaunchSetupTimes() = shoot("61c_folded_setup_times", FOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간")
    })
    @Test fun firstLaunchSetupOrder() = shoot("61d_folded_setup_order", FOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간"); tapText("네, 시간이 맞아요")
    })
    @Test fun firstLaunchSetupToday() = shoot("61e_folded_setup_today", FOLDED, fresh(null) { setupToToday() })
    @Test fun unfoldedSetupToday() = shoot("61f_unfolded_setup_today", UNFOLDED, fresh(null) { setupToToday() })
    @Test fun firstLaunchSetupSummary() = shoot("61g_folded_setup_summary", FOLDED, fresh(null) {
        setupToToday(); tapText("회사 근무표와 같아요"); tapText("다음", exact = true)
    })
    @Test fun firstLaunchSetupRoster() = shoot("61h_folded_setup_roster", FOLDED, fresh(null) {
        tapText("근무표가 매달 나와요"); tapText("건너뛰고 직접 정할게요"); tapText("병원 3교대"); tapText("네, 시간이 맞아요")
        tapText("데이 칠하기")
    })
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
        content { com.bamnat.app.ui.settings.CompanyShareDialog(hasPay = true, hasRotation = true, onDismiss = {}, onShare = { _, _, _ -> }) }
        settle()
    }
    @Test fun foldedCompanyImport() = shoot("84_folded_company_import", FOLDED) {
        content {
            com.bamnat.app.ui.settings.CompanyImportDialog(
                com.bamnat.app.data.CompanySummary("1공장", listOf("주간", "야간", "휴무"), 5, true, listOf("야간수당", "명절상여"), false, team = "A조"),
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
        settingsPage("잠과 휴식")
        reveal("워치·휴대폰 수면 기록 함께 보기", exact = true)
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
    // [TIME-01] Typing a duration became the same wheels as clock times.
    @Test fun foldedDurationWheel() = shoot("203_folded_duration_wheel", FOLDED) {
        content {
            com.bamnat.app.ui.components.DurationDialog("몇 분 전에 울릴까요?", 90, 24 * 60, onDismiss = {}, onConfirm = {})
        }
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

    /** 4조 2교대 from its template, the user's own 주주휴휴야야휴휴, today the second night. */
    private fun Screen.setupToToday() {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간"); tapText("네, 시간이 맞아요")
        tapText("4조 2교대 8일 (주휴야휴)"); tapText("다음", exact = true)
        tapText("야간", exact = true); tapText("둘째 날")
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

    // ---- M12 [COMM-01][COMM-02][COMM-03][EXTRA-06] 회사 찾기와 나누기, with a made-up company server ----
    private val company = FakeCompanyApi()

    /** A coworker's setup as the server hands it out: this phone's own schedule and a company's pay, no amounts. */
    private fun coworkerFile(): String = runBlocking {
        val c = app.container
        val night = c.scheduleRepository.shiftTypes.first().first { it.name == "야간" }.id
        val before = c.payStore.profile.value
        c.payStore.save(com.bamnat.core.pay.PayProfile(
            com.bamnat.core.pay.Occupation.MANUFACTURING, com.bamnat.core.pay.WageType.MONTHLY,
            com.bamnat.core.pay.ValueTimeline(listOf(com.bamnat.core.pay.DatedValue(today, 1))), monthlyHours = 180,
            items = listOf(
                com.bamnat.core.pay.PayItem("q", "자격수당", com.bamnat.core.pay.ValueTimeline(), com.bamnat.core.pay.Cadence.MONTHLY),
                com.bamnat.core.pay.PayItem("n", "야간수당", com.bamnat.core.pay.ValueTimeline(), com.bamnat.core.pay.Cadence.PER_SHIFT_TYPE, shiftTypeIds = setOf(night)),
                com.bamnat.core.pay.PayItem("b", "상여", com.bamnat.core.pay.ValueTimeline(), com.bamnat.core.pay.Cadence.BONUS,
                    months = setOf(2, 4, 6, 8, 10, 12), percentOfBase = 1.0),
            ),
            rules = companyRules,
        ), keepUndo = false)
        val file = c.companyShare.exportForServer().toString()
        c.payStore.save(before, keepUndo = false)
        file
    }

    private fun companyOn(): com.bamnat.app.AppContainer = app.container.also {
        it.companyApi = company
        company.file = coworkerFile()
    }

    /** Lets the view model's own waits pass, e.g. the pause in typing before a search. */
    private fun waitFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        settle()
    }
    @Test fun foldedCompanyFind() = shoot("114_folded_company_find", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn()).apply { query.value = "예시" }
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
        waitFor(400)
    }
    @Test fun foldedCompanyFirstLook() = shoot("115_folded_company_first_look", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
    }
    @Test fun foldedCompanyNotFound() = shoot("116_folded_company_not_found", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyFindViewModel(companyOn()).apply { query.value = "가나다화학" }
        content { com.bamnat.app.ui.company.CompanyFindScreen(vm, setup = true, onBack = {}, onOpen = {}, onByHand = {}, focus = false) }
        waitFor(400)
    }
    @Test fun foldedCompanyDetail() = shoot("117_folded_company_detail", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}) }
        tapText("급여 계산 방식 보기", prefix = true)
    }
    @Test fun foldedCompanyToday() = shoot("118_folded_company_today", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, openPhasesFor = "s1") }
    }
    @Test fun foldedCompanyPay() = shoot("119_folded_company_pay", FOLDED) {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, java.time.Instant.now()))
        c.companyStore.pendingPay = company.file
        val vm = com.bamnat.app.ui.company.CompanyPayViewModel(c)
        content { com.bamnat.app.ui.company.CompanyPayScreen(vm, onBack = {}, onByHand = {}) }
    }
    @Test fun foldedCompanyReport() = shoot("120_folded_company_report", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학", "품질팀"))
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
        scrollUp(600)
    }
    @Test fun foldedCompanyReportEnd() = shoot("130_folded_company_report_end", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학", "품질팀"))
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
        reveal("보내기", exact = true)
    }
    @Test fun foldedCompanySent() = shoot("121_folded_company_sent", FOLDED) {
        val c = companyOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        vm.send("가나다화학", "품질팀", true, "")
        settle()
        content { com.bamnat.app.ui.company.CompanyReportScreen(vm, onBack = {}, onDone = {}, onOpenSent = {}) }
    }
    @Test fun foldedCompanyMine() = shoot("122_folded_company_mine", FOLDED) {
        val c = companyOn()
        // Each send goes on top, so the oldest goes in first.
        company.mine.reversed().forEach { c.companyStore.addSent(com.bamnat.app.company.SentReport(it.id, it.company, it.dept, it.ask, it.createdAt)) }
        val vm = com.bamnat.app.ui.company.CompanyReportViewModel(c)
        content { com.bamnat.app.ui.company.CompanySentScreen(vm, onBack = {}, onShare = {}) }
    }
    private fun votePay(): Screen.() -> Unit = {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, java.time.Instant.now()))
        val nudges = com.bamnat.app.ui.company.CompanyNudgeViewModel(c)
        payScreen(Tier.PRO, withProfile = true) { vm ->
            PayScreen(vm, onBack = {}, top = { com.bamnat.app.ui.company.CompanyNudgeCards(nudges, onShare = {}, onFix = {}) })
        }.invoke(this)
    }
    @Test fun foldedCompanyVote() = shoot("123_folded_company_vote", FOLDED, votePay())
    @Test fun foldedCompanyWrong() = shoot("124_folded_company_wrong", FOLDED) {
        votePay().invoke(this)
        tapText("달라요", exact = true)
        tapText("급여 계산", exact = true)
    }
    @Test fun foldedCompanyPlea() = shoot("125_folded_company_plea", FOLDED) {
        votePay().invoke(this)
        tapText("달라요", exact = true)
        tapText("급여 계산", exact = true)
        tapText("알려 주기", exact = true)
    }
    @Test fun foldedCompanyInvite() = shoot("126_folded_company_invite", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(com.bamnat.app.company.CompanyDraft("가나다화학"))
        val nudges = com.bamnat.app.ui.company.CompanyNudgeViewModel(c)
        payScreen(Tier.PRO, withProfile = true) { vm ->
            PayScreen(vm, onBack = {}, top = { com.bamnat.app.ui.company.CompanyNudgeCards(nudges, onShare = {}, onFix = {}) })
            com.bamnat.app.ui.company.InviteSheet(nudges, onShare = {}, onDismiss = {})
        }.invoke(this)
    }
    @Test fun foldedSettingsCompany() = shoot("127_folded_settings_company", FOLDED) {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, java.time.Instant.now(), vote = true))
        settingsPage("급여와 연차")
        reveal("같은 회사 사람들과", exact = true)
    }
    @Test fun foldedKindAsk() = shoot("128_folded_kind_ask", FOLDED) {
        companyOn()
        kindsScreen().invoke(this)
        tapText("대근", exact = true)
        tapText("회사 방식 알려 주기", exact = true)
    }
    @Test fun unfoldedCompanyDetail() = shoot("129_unfolded_company_detail", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
    }

    // [COMM-06][COMM-07] 회사 규칙 광장: every company's rule sets to vote on, the talk on one, and 맞았어요·달라요 on the company screen.
    @Test fun foldedCompanyBoard() = shoot("190_folded_company_board", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
    }
    @Test fun foldedCompanyBoardAdopted() = shoot("191_folded_company_board_adopted", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        tapText("채택됨", exact = true)
    }
    @Test fun unfoldedCompanyBoard() = shoot("192_unfolded_company_board", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
    }
    @Test fun foldedCompanyBoardSearch() = shoot("195_folded_company_board_search", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        tapText("채택됨", exact = true)
        vm.query.value = "예시"
        // Past the pause the board waits for typing to stop.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        settle()
    }
    @Test fun foldedCompanyTalk() = shoot("193_folded_company_talk", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        vm.talk.show("c-v1", "s-v1", "가나다화학 · 품질팀")
        settle()
    }
    // [PLZ-01][PLZ-05][PLZ-09] 맞아요·달라요 on each post, answers under the post, and a proposal in fields.
    @Test fun foldedPlazaTalkProposal() = shoot("355_folded_plaza_talk_proposal", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.talk.show("c-v1", "s-v1", "가나다화학 · 품질팀")
        settle()
        tapText("고치자는 제안", exact = true)
    }
    // [PLZ-12][PLZ-05] The job plazas, 시·도 and order as chips on the board.
    @Test fun phonePlazaBoard() = shoot("356_phone_plaza_board", PHONE) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
    }
    // [PLZ-14][PLZ-15] 임금표: 광장 시세 and the rates people sent.
    @Test fun foldedPlazaRates() = shoot("357_folded_plaza_rates", FOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
    }
    @Test fun phonePlazaRatesHugeText() = shoot("358_phone_plaza_rates_huge_text", PHONE, fontScale = 2f) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
    }
    // [PLZ-08] 내 활동 with the plaza name.
    @Test fun foldedPlazaActivity() = shoot("359_folded_plaza_activity", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}, onRates = {}) }
        vm.activity.show()
        settle()
    }
    // [PLZ-04] How a rule grew, version by version.
    @Test fun foldedCompanyHistory() = shoot("360_folded_company_history", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
        settle()
        tapText("바뀐 기록", exact = true)
    }
    @Test fun foldedCompanyVoteAnySetup() = shoot("194_folded_company_vote_any", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyDetailViewModel(companyOn(), "c1")
        content { com.bamnat.app.ui.company.CompanyDetailScreen(vm, setup = false, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, ready = true) }
        reveal("다니는 회사라면 이 규칙이 맞는지 알려 주세요")
    }

    // [SHR-01][SHR-02][SHR-04] A link with chosen parts, and calendars received from others beside the user's own.
    private val shares = FakeShareApi()
    private val minjiId = "Mj7dEfGh5JkLmNoPqRsTuv"
    private val junhoId = "Jh2kWxYz8AbCdEfGhJkLmN"

    /** 민지's link as the server keeps it: 4조2교대 with a memo, 잔업, 연차 and her pay, from this month. */
    private fun minjiShare(): com.bamnat.app.share.ReceivedShare {
        val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), icon = "sun")
        val night = ShiftType(2, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0), icon = "moon")
        val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
        val from = today.withDayOfMonth(1)
        val pattern = WorkPattern.Rotation(from.minusDays(3), listOf(1L, 1L, 3L, 3L, 2L, 2L, 3L, 3L))
        val work = (0..20).map { today.plusDays(it.toLong()) }.filter { pattern.shiftTypeIdOn(it) == 1L }
        val trip = work.first { it.isAfter(work.first().plusDays(3)) }
        val snap = com.bamnat.app.data.ScheduleSnapshot(listOf(day, night, off), pattern, listOf(
            com.bamnat.core.model.DayEntry(trip, leave = Leave()),
            com.bamnat.core.model.DayEntry(work.first(), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))),
        ))
        val memos = mapOf(today.plusDays(2) to "치과 2시", trip to "가족 여행 · 강릉")
        val month = java.time.YearMonth.from(today)
        val pay = listOf(com.bamnat.app.share.SharedPay(month, 3_412_000, "세전 예상"), com.bamnat.app.share.SharedPay(month.plusMonths(1), 3_268_000, "세전 예상"))
        val body = com.bamnat.app.share.ShareLinkPayload.build(snap, from, "민지", 30,
            com.bamnat.app.share.ShareOptions(leaveKinds = true, extras = true, memos = true, pay = true), memos, pay)
        body.remove("ttlDays")
        return com.bamnat.app.share.ReceivedShare(body, java.time.Instant.now().plus(java.time.Duration.ofDays(30)), java.time.Instant.now())
    }

    /** 준호's link from an older app: weekdays 09–18, nothing ticked. */
    private fun junhoShare(): com.bamnat.app.share.ReceivedShare {
        val weekday = ShiftType(1, "평일", "평", 0xFF2E7D32, LocalTime.of(9, 0), LocalTime.of(18, 0))
        val off = ShiftType(2, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
        val from = today.withDayOfMonth(1)
        val monday = from.minusDays((from.dayOfWeek.value - 1).toLong())
        val snap = com.bamnat.app.data.ScheduleSnapshot(listOf(weekday, off), WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, 1L, 1L, 2L, 2L)), emptyList())
        val body = com.bamnat.app.share.ShareLinkPayload.build(snap, from, "준호", 30)
        return com.bamnat.app.share.ReceivedShare(body, java.time.Instant.now().plus(java.time.Duration.ofDays(7)), java.time.Instant.now())
    }

    /** The share server answers at once; 준호 then 민지 were received, unless [minji] is false. */
    private fun receivedOn(minji: Boolean = true): com.bamnat.app.AppContainer = app.container.also { c ->
        c.shareApi = shares
        shares.links[minjiId] = minjiShare()
        shares.links[junhoId] = junhoShare()
        c.received.put(junhoId, shares.links.getValue(junhoId))
        if (minji) c.received.put(minjiId, shares.links.getValue(minjiId))
    }

    /** A day both are off on in this month's grid, today or later when there is one. */
    private fun restTogether(): LocalDate {
        val mine = runBlocking { app.container.scheduleRepository.snapshot.first() }
        val theirs = com.bamnat.app.share.SharedSchedule.parse(minjiShare().schedule)
        val days = (1..today.lengthOfMonth()).map { today.withDayOfMonth(it) }.filter { d ->
            val m = mine.resolver.resolve(d)
            theirs.isRest(d) == true && (m.isOff || m.leave?.isFullDay == true) && m.extras.isEmpty()
        }
        return days.firstOrNull { !it.isBefore(today) } ?: days.first()
    }

    @Test fun foldedShareSheet() = shoot("131_folded_share_sheet", FOLDED) {
        val c = receivedOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false) }
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares, onOpenReceived = {}) }
        tapText("연장·휴무일 근무", exact = true)
        tapText("메모", exact = true)
    }
    @Test fun foldedShareSheetEnd() = shoot("131b_folded_share_sheet_end", FOLDED) {
        val c = receivedOn()
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile(), keepUndo = false) }
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares, onOpenReceived = {}) }
        tapText("메모", exact = true)
        tapText("급여", exact = true)
        reveal("동료 근무표도 받아 볼까요?")
    }
    @Test fun foldedIdentityBar() = shoot("132_folded_identity_bar", FOLDED) { receivedOn() }
    @Test fun foldedCalendarSwitcher() = shoot("133_folded_calendar_switcher", FOLDED) {
        receivedOn()
        settle()
        tapText("달력 바꾸기", prefix = true)
    }
    @Test fun foldedReceivedCalendar() = shoot("134_folded_received_calendar", FOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
    }
    @Test fun foldedReceivedDay() = shoot("135_folded_received_day", FOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(restTogether()), prefix = true, scrolls = 0)
        reveal("둘 다 쉬는 날이에요")
    }
    @Test fun unfoldedReceivedCalendar() = shoot("136_unfolded_received_calendar", UNFOLDED) {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(restTogether()), prefix = true, scrolls = 0)
    }
    @Test fun foldedReceivedLink() = shoot("137_folded_received_link", FOLDED) {
        // A link tapped in a message opens the app with it [SHR-02].
        receivedOn(minji = false).received.open(minjiId)
        waitFor(300)
    }
    @Test fun foldedReceivedGone() = shoot("138_folded_received_gone", FOLDED) {
        val c = receivedOn()
        c.received.markGone(junhoId)
        settle()
        tapText("달력 바꾸기", prefix = true)
        tapText("준호의 근무표", exact = true)
    }
    @Test fun foldedReceivedDark() = shoot("139_folded_received_dark", "w369dp-h905dp-port-night-420dpi") {
        receivedOn()
        settle()
        tapText("민지의 근무표 보기", exact = true)
    }

    // ---- [MCAL-01][MCAL-02][MCAL-03][MCAL-04] The user's own calendars ----
    /** An added part-time calendar, 월·수·금 오전 알바, set up and listed; the main one stays on screen unless [show]. */
    private fun addedCalendar(name: String = "편의점 알바", show: Boolean = false): String = runBlocking {
        val c = app.container
        val id = c.calendars.begin(name)
        val repo = c.calendars.repository(id)
        repo.ensureDefaults()
        repo.addHourlyTypes()
        val shift = repo.shiftTypes.first().first { it.name == "오전 알바" }.id
        repo.setFirstPattern(WorkPattern.Weekly.fromSteps(LocalDate.of(2026, 7, 1), listOf(shift, null, shift, null, shift, null, null)))
        c.calendars.finishSetup(id, com.bamnat.core.work.WorkStyle.HOURLY)
        if (!show) c.calendars.setActive(com.bamnat.app.data.PersonalCalendar.MAIN)
        id
    }

    @Test fun foldedMyCalendarsSwitcher() = shoot("210_folded_my_calendars_switcher", FOLDED) {
        receivedOn()
        addedCalendar()
        settle()
        tapText("달력 바꾸기", prefix = true)
    }
    @Test fun foldedAddedCalendar() = shoot("211_folded_added_calendar", FOLDED) {
        receivedOn(minji = false)
        addedCalendar(show = true)
    }
    @Test fun unfoldedAddedCalendar() = shoot("212_unfolded_added_calendar", UNFOLDED) {
        addedCalendar(show = true)
    }
    @Test fun foldedCalendarsList() = shoot("213_folded_calendars_list", FOLDED) {
        receivedOn()
        val id = addedCalendar()
        // Shared with a link of its own [MCAL-03].
        app.container.shareLinksOf(id).save(com.bamnat.app.share.ShareLinkState("al1", "https://bamnat-share.example/s/al1", "t",
            java.time.Instant.now().plus(java.time.Duration.ofDays(30)), "민지"))
        addedCalendar("가족 일정")
        settle()
        tapText("달력 바꾸기", prefix = true)
        tapText("순서·관리", exact = true)
    }
    @Test fun unfoldedCalendarsList() = shoot("214_unfolded_calendars_list", UNFOLDED) {
        receivedOn(minji = false)
        addedCalendar()
        settle()
        tapText("달력 바꾸기", prefix = true)
        tapText("순서·관리", exact = true)
    }
    @Test fun foldedCalendarEdit() = shoot("215_folded_calendar_edit", FOLDED) {
        addedCalendar()
        settle()
        tapText("달력 바꾸기", prefix = true)
        tapText("순서·관리", exact = true)
        tapText("편의점 알바", exact = true)
    }
    @Test fun foldedNewCalendarDialog() = shoot("216_folded_new_calendar", FOLDED) {
        content { com.bamnat.app.ui.calendar.NewCalendarDialog("달력 2", 0, { _, _ -> }, {}) }
    }
    @Test fun foldedNewCalendarSetup() = shoot("217_folded_new_calendar_setup", FOLDED) {
        val c = app.container
        val id = c.calendars.begin("편의점 알바")
        val vm = com.bamnat.app.ui.setup.SetupViewModel(c, calendarId = id)
        content { com.bamnat.app.ui.setup.SetupHost(vm) }
    }
    @Test fun foldedAddedCalendarShare() = shoot("218_folded_added_calendar_share", FOLDED) {
        val c = receivedOn()
        addedCalendar(show = true)
        val snap = runBlocking { c.calendars.activeSnapshot.first() }
        content {
            com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares,
                calendar = c.calendars.current(), onOpenReceived = {})
        }
    }

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

    // ---- M10 [SET-01][SLP-15] Settings in groups ----
    @Test fun foldedSettingsAlarm() = shoot("140_folded_settings_alarm", FOLDED) { settingsPage("알람") }
    @Test fun foldedSettingsNotices() = shoot("141_folded_settings_notices", FOLDED) { settingsPage("알림") }
    @Test fun foldedSettingsRest() = shoot("142_folded_settings_rest", FOLDED) { settingsPage("잠과 휴식") }
    @Test fun unfoldedSettingsNotices() = shoot("143_unfolded_settings_notices", UNFOLDED) { settingsPage("알림", wide = true) }
    // [CAL-15] The FAB keeps under the month's last row, so on the cover screen it is a scroll away.
    @Test fun foldedQuickInput() = shoot("145_folded_quick_input", FOLDED) { tapText("빠른 입력", exact = true) }
    @Test fun foldedQuickInputDrag() = shoot("146_folded_quick_input_drag", FOLDED) {
        tapText("빠른 입력", exact = true)
        tapText("야간 칠하기", exact = true, scrolls = 0)
        dragDays(dayCell, today)
    }
    /** [CAL-01][VIS-02] At 130% text the dates and the line under them stop growing before the cell does. */
    @Test fun foldedCalendarLargeText() = shoot("148_folded_calendar_large_text", FOLDED, fontScale = 1.3f)

    // ---- [SCALE-01] Any phone, any text size: a common phone, a small one, the smallest and the largest text ----
    @Test fun phoneCalendar() = shoot("160_phone_calendar", PHONE)
    @Test fun smallPhoneCalendar() = shoot("161_small_phone_calendar", SMALL_PHONE)
    @Test fun smallPhoneDaySheet() = shoot("162_small_phone_day_sheet", SMALL_PHONE) { openDayBelow(dayCell) }
    @Test fun phoneCalendarSmallText() = shoot("163_phone_calendar_small_text", PHONE, fontScale = 0.85f)
    @Test fun phoneCalendarHugeText() = shoot("164_phone_calendar_huge_text", PHONE, fontScale = 2f)
    @Test fun phoneDaySheetHugeText() = shoot("165_phone_day_sheet_huge_text", PHONE, fontScale = 2f) { openDayBelow(dayCell) }
    @Test fun phoneSettingsHugeText() = shoot("166_phone_settings_huge_text", PHONE, fontScale = 2f) { tab(4, wide = false) }
    @Test fun phoneStatsHugeText() = shoot("167_phone_stats_huge_text", PHONE, fontScale = 2f) { tab(3, wide = false) }
    @Test fun phoneMoneyLargeText() = shoot("168_phone_money_large_text", PHONE, fontScale = 1.5f) { tab(1, wide = false) }
    @Test fun phonePayLargeText() = shoot("169_phone_pay_large_text", PHONE, fontScale = 1.5f, payScreen(Tier.PRO, withProfile = true))
    @Test fun phonePayWizardHugeText() = shoot("170_phone_pay_wizard_huge_text", PHONE, fontScale = 2f, payScreen(Tier.PRO, withProfile = false))
    @Test fun phoneMemosHugeText() = shoot("171_phone_memos_huge_text", PHONE, fontScale = 2f) { tab(2, wide = false) }
    @Test fun unfoldedCalendarHugeText() = shoot("172_unfolded_calendar_huge_text", UNFOLDED, fontScale = 2f)
    @Test fun phoneLandscapeCalendar() = shoot("173_phone_landscape_calendar", PHONE_LANDSCAPE)
    @Test fun foldedStatsSameMonth() = shoot("147_folded_stats_same_month", FOLDED) {
        tapText("이전 달", exact = true, scrolls = 0)
        tab(3, wide = false)
    }
    @Test fun foldedSettingsDataUndo() = shoot("144_folded_settings_data_undo", FOLDED) {
        runBlocking { app.container.importUndo.save() }
        settingsPage("데이터")
    }

    // ---- [SCHED-01] A memo that names a time ----
    @Test fun foldedMemoScheduleOffer() = shoot("188_folded_memo_schedule_offer", FOLDED) {
        tab(2, wide = false); tapText("건강검진 오전 9시")
    }
    @Test fun foldedMemoScheduleDialog() = shoot("189_folded_memo_schedule_dialog", FOLDED) {
        tab(2, wide = false); tapText("건강검진 오전 9시"); tapText("일정으로 넣기", exact = true)
    }

    private companion object {
        const val RULES_PERIOD_SCROLL = 420

        // Galaxy Z Fold6: cover screen 968x2376 px and main screen 1856x2160 px at 420 dpi.
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
        // [SCALE-01] A common phone (Galaxy S24 at 1080x2340), a small one (720x1280 class) and a phone on its side.
        const val PHONE = "w360dp-h780dp-port-450dpi"
        const val SMALL_PHONE = "w320dp-h568dp-port-xhdpi"
        const val PHONE_LANDSCAPE = "w780dp-h360dp-land-450dpi"
    }

    // ---- 달력 (1.15) ----
    // [CAL-05..CAL-14] The calendar for each kind of work: its month summary, list, legend, quick input marks
    // and ranges, holidays, added and received calendars, large text, dark mode and the home screen widgets.

    /** [CAL-05] Field or shop work as such a user keeps it: no shifts, the seeded jobs and sales, and what [more] adds. */
    private fun plain(s: WorkStyle, more: suspend (com.bamnat.app.AppContainer) -> Unit = {}, then: Screen.() -> Unit = {}): Screen.() -> Unit {
        val c = app.container
        runBlocking {
            c.scheduleRepository.clearPattern()
            c.scheduleRepository.entries.first().forEach { c.scheduleRepository.restoreDay(it.date, null) }
            more(c)
        }
        c.settingsRepository.update { it.copy(workStyle = s) }
        return then
    }

    /** A three-day job still owed and one paid last week, beside the seeded ones. */
    private val moreJobs: suspend (com.bamnat.app.AppContainer) -> Unit = { c ->
        c.moneyRepository.save(Job(date = today.plusDays(6), endDate = today.plusDays(8), title = "베란다 방수", client = "이OO 고객",
            amount = 900_000, deposit = 200_000, depositPaid = true))
        c.moneyRepository.save(Job(date = today.minusDays(9), title = "싱크대 교체", amount = 350_000, balancePaid = true))
    }

    /** Every open day of the month so far, closed on Mondays. */
    private val moreSales: suspend (com.bamnat.app.AppContainer) -> Unit = { c ->
        for (d in 1 until today.dayOfMonth - 1) {
            val date = today.withDayOfMonth(d)
            if (date.dayOfWeek == java.time.DayOfWeek.MONDAY) continue
            c.moneyRepository.save(DailySales(date, sales = 520_000L + d * 137_000L % 760_000L, costs = 150_000L + d * 31_000L % 90_000L))
        }
    }

    /** [CAL-06] Pay set up on Pro, so the summary over a shift calendar shows the month's pay. */
    private fun withPay(then: Screen.() -> Unit = {}): Screen.() -> Unit {
        val c = app.container
        c.settingsRepository.update { it.copy(previewTier = null) }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        return then
    }

    @Test fun calShiftFolded() = shoot("290_folded_shift_calendar", FOLDED, withPay())
    @Test fun calShiftUnfolded() = shoot("291_unfolded_shift_calendar", UNFOLDED, withPay())
    /** Hints read once when the calendar opens: set before the screen starts. */
    private fun hints(seen: Boolean, vararg keys: String) {
        val store = com.bamnat.app.ui.calendar.HintStore(app)
        if (seen) keys.forEach(store::markDone)
        else app.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE).edit().apply { keys.forEach { remove(it) } }.commit()
    }

    /**
     * [CAL-16][CAL-15] A returning user with 오늘의 흐름 folded to one line, a little way down: the month's
     * last row ends above the FAB, which rides in its room under the month with its label.
     */
    @Test fun calFlowFolded() {
        hints(seen = true, com.bamnat.app.ui.calendar.HintStore.SHARE)
        shoot("292_folded_flow_folded", FOLDED, withPay {
            tapText("오늘의 흐름 접기", exact = true, scrolls = 0)
            scrollUp(140)
        })
    }
    @Test fun calFieldFolded() = shoot("293_folded_field_calendar", FOLDED, plain(WorkStyle.FIELD, moreJobs))
    @Test fun calFieldUnfolded() = shoot("294_unfolded_field_calendar", UNFOLDED, plain(WorkStyle.FIELD, moreJobs))
    /** [CAL-17] The first tap on a date says once that a long press changes it in place. */
    @Test fun calLongPressTip() {
        hints(seen = false, com.bamnat.app.ui.calendar.HintStore.LONG_PRESS)
        hints(seen = true, com.bamnat.app.ui.calendar.HintStore.SHARE)
        app.container.settingsRepository.update { it.copy(sleepTips = false) }
        shoot("295_folded_long_press_tip", FOLDED) { selectDay(secondRowMonday()) }
    }
    @Test fun calFieldDay() = shoot("296_folded_field_day", FOLDED, plain(WorkStyle.FIELD, moreJobs) { openDay(today) })
    @Test fun calBusinessFolded() = shoot("297_folded_business_calendar", FOLDED, plain(WorkStyle.BUSINESS, moreSales))
    @Test fun calBusinessUnfolded() = shoot("298_unfolded_business_calendar", UNFOLDED, plain(WorkStyle.BUSINESS, moreSales))
    @Test fun calBusinessDay() = shoot("299_folded_business_day", FOLDED, plain(WorkStyle.BUSINESS, moreSales) { openDay(today) })

    /** [CAL-07] 평일 근무 on the next month with a weekday holiday: asked once whether those are days off. */
    @Test fun calDayHolidays() = shoot("300_folded_day_holiday_card", FOLDED) {
        val c = app.container
        runBlocking {
            val repo = c.scheduleRepository
            val day = repo.shiftTypes.first().first { it.name == "주간" }.id
            repo.savePattern(WorkPattern.Weekly.fromSteps(LocalDate.of(2026, 1, 5), listOf(day, day, day, day, day, null, null)))
        }
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.DAY) }
        settle()
        val ahead = (0..12).first { n ->
            val m = java.time.YearMonth.now().plusMonths(n.toLong())
            (1..m.lengthOfMonth()).map { m.atDay(it) }.any { it.dayOfWeek.value <= 5 && com.bamnat.core.holiday.KoreanHolidays.isHoliday(it) }
        }
        repeat(ahead) { tapText("다음 달", exact = true, scrolls = 0) }
    }

    /** [CAL-05] An hourly job's own calendar before any day is written: one step to start. */
    @Test fun calHourlyStart() = shoot("301_folded_hourly_start", FOLDED) {
        val c = app.container
        val id = c.calendars.begin("편의점 알바")
        runBlocking { c.calendars.repository(id).apply { ensureDefaults(); addHourlyTypes() } }
        c.calendars.finishSetup(id, WorkStyle.HOURLY)
    }

    // [CAL-08] The month as a list, for each kind of work.
    @Test fun calListShift() = shoot("302_folded_list_view", FOLDED) { tapText("목록으로 보기", exact = true, scrolls = 0) }
    @Test fun calListField() = shoot("303_folded_field_list_view", FOLDED, plain(WorkStyle.FIELD, moreJobs) {
        tapText("목록으로 보기", exact = true, scrolls = 0)
    })
    @Test fun calListBusiness() = shoot("304_unfolded_business_list_view", UNFOLDED, plain(WorkStyle.BUSINESS, moreSales) {
        tapText("목록으로 보기", exact = true, scrolls = 0)
    })

    // [CAL-06] What every mark means, in the calendar's own kind of work.
    @Test fun calLegendShift() = shoot("305_folded_legend_shift", FOLDED) { tapText("달력 표시 안내", exact = true, scrolls = 0) }
    @Test fun calLegendField() = shoot("306_folded_legend_field", FOLDED, plain(WorkStyle.FIELD, moreJobs) {
        tapText("달력 표시 안내", exact = true, scrolls = 0)
    })

    /** [CAL-09] 연차 painted across a week: only the working days take it, and the bar says so with undo. */
    /** Monday of the month's second row (weeks start on Sunday): above the quick input bar on every screen. */
    private fun secondRowMonday(): LocalDate {
        val first = today.withDayOfMonth(1)
        return first.plusDays((7 - first.dayOfWeek.value % 7).toLong() + 1)
    }
    @Test fun calPaintLeave() = shoot("307_unfolded_paint_leave", UNFOLDED) {
        tapText("빠른 입력", exact = true)
        tapText("연차 칠하기", exact = true, scrolls = 0)
        dragDays(secondRowMonday(), secondRowMonday().plusDays(4))
    }

    /** [CAL-09] A long press starts a range; the next tap ends it. */
    @Test fun calPaintRange() = shoot("308_folded_paint_range", FOLDED) {
        tapText("빠른 입력", exact = true)
        tapText("야간 칠하기", exact = true, scrolls = 0)
        longPressText(Fmt.dayTitle(secondRowMonday()), prefix = true)
    }

    // [CAL-13] An added calendar kept from ringing: said above the calendar and in the day's alarms.
    @Test fun calAddedAlarmsOff() = shoot("309_folded_added_calendar_alarms_off", FOLDED) {
        val id = addedCalendar(show = true)
        app.container.calendars.setAlarms(id, false)
    }
    @Test fun calAddedAlarmsOffDay() = shoot("310_folded_added_calendar_alarms_off_day", FOLDED) {
        val id = addedCalendar(show = true)
        app.container.calendars.setAlarms(id, false)
        settle()
        val work = (0..6).map { today.plusDays(it.toLong()) }.first { it.dayOfWeek.value in setOf(1, 3, 5) }
        if (work.month == today.month) openDay(work) else openDayBelow(work)
        openGroup("알람")
        reveal("알람", exact = true)
    }

    // [CAL-12] A freelancer's link: jobs and a memo, no shifts, so no "휴" on every empty day.
    private val jobsLinkId = "Fr9gHj3KlMnPqRsTuVwXyZ"
    private fun jobsShare(): com.bamnat.app.share.ReceivedShare {
        val snap = com.bamnat.app.data.ScheduleSnapshot(emptyList(), emptyList<WorkPattern>(), emptyList())
        val jobs = linkedMapOf(
            today to listOf(com.bamnat.app.share.SharedJob("욕실 타일 시공", "09:00-17:00")),
            today.plusDays(2) to listOf(com.bamnat.app.share.SharedJob("현장 실측", "14:00-15:00")),
        )
        for (d in 6L..8L) jobs[today.plusDays(d)] = listOf(com.bamnat.app.share.SharedJob("베란다 방수", null))
        val memos = mapOf(today.plusDays(4) to "자재 받기")
        val body = com.bamnat.app.share.ShareLinkPayload.build(snap, today.withDayOfMonth(1), "준호", 30,
            com.bamnat.app.share.ShareOptions(memos = true, jobs = true), memos, jobs = jobs)
        body.remove("ttlDays")
        return com.bamnat.app.share.ReceivedShare(body, java.time.Instant.now().plus(java.time.Duration.ofDays(30)), java.time.Instant.now())
    }
    private fun jobsLinkOn() {
        val c = app.container
        c.shareApi = shares
        shares.links[jobsLinkId] = jobsShare()
        c.received.put(jobsLinkId, shares.links.getValue(jobsLinkId))
    }
    @Test fun calReceivedJobs() = shoot("311_folded_received_jobs", FOLDED) {
        jobsLinkOn()
        settle()
        tapText("준호의 근무표 보기", exact = true)
    }
    @Test fun calReceivedJobsDay() = shoot("312_unfolded_received_jobs", UNFOLDED) {
        jobsLinkOn()
        settle()
        tapText("준호의 근무표 보기", exact = true)
        tapText(Fmt.dayTitle(today), prefix = true, scrolls = 0)
    }

    // [CAL-06][VIS-02] 200% text: the summary's figures and the cells still fit.
    @Test fun calShiftHugeText() = shoot("313_phone_shift_huge_text", PHONE, 2f, withPay())
    @Test fun calFieldHugeText() = shoot("314_phone_field_huge_text", PHONE, 2f, plain(WorkStyle.FIELD, moreJobs))

    /** [CAL-06] Any date, picked on a full calendar from the month list. */
    @Test fun calDateJump() = shoot("315_folded_date_jump", FOLDED) {
        tapText(Fmt.month(java.time.YearMonth.now()), exact = true, scrolls = 0)
        tapText("날짜 고르기", exact = true, scrolls = 0)
    }
    @Test fun calFieldDark() = shoot("316_folded_field_dark", "w369dp-h905dp-port-night-420dpi", plain(WorkStyle.FIELD, moreJobs))

    /** [CAL-11] The three widgets in the words of field and shop work. */
    private fun widgetShots(prefix: String) = runBlocking {
        val shots = listOf(
            Triple("today", com.bamnat.app.widget.TodayWidget() as androidx.glance.appwidget.GlanceAppWidget, 180 to 150),
            Triple("week", com.bamnat.app.widget.WeekWidget(), 340 to 110),
            Triple("month", com.bamnat.app.widget.MonthWidget(), 340 to 330),
        )
        for ((name, widget, dims) in shots) {
            val (wDp, hDp) = dims
            @OptIn(androidx.glance.ExperimentalGlanceApi::class)
            val rv = widget.compose(app, size = androidx.compose.ui.unit.DpSize(wDp.dp, hDp.dp))
            val density = app.resources.displayMetrics.density
            val w = (wDp * density).toInt()
            val h = (hDp * density).toInt()
            val parent = android.widget.FrameLayout(app)
            parent.addView(rv.apply(app, parent), android.widget.FrameLayout.LayoutParams(w, h))
            parent.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xFF7A8BA8.toInt())
            parent.draw(canvas)
            val out = File("build/screenshots/${prefix}_$name.png").apply { parentFile!!.mkdirs() }
            out.outputStream().use { st -> bmp.compress(Bitmap.CompressFormat.PNG, 100, st) }
        }
    }
    @Test fun calFieldWidgets() {
        plain(WorkStyle.FIELD, moreJobs)
        widgetShots("317_widget_field")
    }
    @Test fun calBusinessWidgets() {
        plain(WorkStyle.BUSINESS, moreSales)
        widgetShots("318_widget_business")
    }
    /** [CAL-18] The day's chips past the screen's edge: the end fades and "+N ›" says how many more. */
    @Test fun calDayChipsMore() = shoot("319_folded_day_chips_more", FOLDED) { openDay(today) }

    // ---- 앱 검토·공유·설정·문의 (1.15) ----
    // [UX-03] 공유 in sight on every screen size, [UX-04] settings found by words, [UX-05] help before a question,
    // [UX-06] quick wins on the sleep and memo screens.

    /** [UX-05] The question server for screenshots: two questions from this phone, one answered, and 모두의 문의. */
    private class FakeInquiryApi(override val enabled: Boolean = true) : com.bamnat.app.company.InquiryApi {
        private val now = java.time.Instant.now()
        private fun ago(hours: Long) = now.minus(java.time.Duration.ofHours(hours))
        private val mine = mutableListOf(
            com.bamnat.app.company.Inquiry("q2", com.bamnat.app.company.InquiryKind.BUG, "answered", ago(30), ago(2), listOf(
                com.bamnat.app.company.InquiryMessage(false, "[알람] 야간 근무 전 알람이 가끔 5분 늦게 울려요.", ago(30)),
                com.bamnat.app.company.InquiryMessage(true, "알려 주셔서 고마워요. 배터리 최적화에서 밤낮을 빼 주시면 제시간에 울려요.", ago(2)),
            ), public = true, same = 3, stage = "answered"),
            com.bamnat.app.company.Inquiry("q1", com.bamnat.app.company.InquiryKind.IDEA, "open", ago(50), ago(50), listOf(
                com.bamnat.app.company.InquiryMessage(false, "달력에서 동료 근무표를 겹쳐 보면 좋겠어요.", ago(50)),
            ), public = true, stage = "planned"),
        )

        override suspend fun send(device: String, kind: com.bamnat.app.company.InquiryKind, text: String, info: com.bamnat.app.company.InquiryInfo) =
            com.bamnat.app.company.Inquiry("q3", kind, "open", now, now, listOf(com.bamnat.app.company.InquiryMessage(false, text, now)), public = kind.public)
                .also { mine.add(0, it) }
        override suspend fun mine(device: String) = mine.toList()
        override suspend fun more(device: String, id: String, text: String) = mine.first { it.id == id }
        override suspend fun delete(device: String, id: String) = Unit
        override suspend fun board(device: String, topic: String?, bySame: Boolean, after: Int) = com.bamnat.app.company.PublicPage(listOf(
            com.bamnat.app.company.PublicInquiry("p1", com.bamnat.app.company.InquiryKind.BUG, "answered", "야간 근무 전 알람이 가끔 5분 늦게 울려요.",
                1, 3, false, true, "alarm", "알람", ago(2)),
            com.bamnat.app.company.PublicInquiry("p2", com.bamnat.app.company.InquiryKind.QUESTION, "answered", "근무표 링크를 받으면 내 달력에 같이 보이나요?",
                1, 5, false, false, "share", "공유", ago(20)),
            com.bamnat.app.company.PublicInquiry("p3", com.bamnat.app.company.InquiryKind.IDEA, "open", "위젯에서 다음 근무까지 남은 시간을 보고 싶어요",
                0, 2, false, false, "widget", "위젯", ago(40)),
        ), listOf("alarm" to "알람", "share" to "공유", "widget" to "위젯"), mapOf("alarm" to 1, "share" to 1, "widget" to 1), null)
        override suspend fun boardThread(device: String, id: String) = com.bamnat.app.company.PublicThread(id,
            com.bamnat.app.company.InquiryKind.QUESTION, "answered", "공유", 5, false, false, false, ago(20), listOf(
                com.bamnat.app.company.InquiryMessage(false, "근무표 링크를 받으면 내 달력에 같이 보이나요?", ago(20)),
                com.bamnat.app.company.InquiryMessage(true, "네, ‘동료 근무표 받기’에 링크를 넣으면 달력 이름을 눌러 바꿔 가며 볼 수 있어요.", ago(18)),
            ), null)
        override suspend fun same(device: String, id: String, on: Boolean) = 6
        override suspend fun flag(device: String, id: String, reason: com.bamnat.app.company.InquiryFlag) = Unit
        override suspend fun rate(device: String, id: String, good: Boolean?) =
            mine.first { it.id == id }.copy(rating = good).also { r -> mine.replaceAll { if (it.id == id) r else it } }
    }

    /** [UX-05] Questions through the fake server; [asked]: this phone sent the two above, one with a new answer. */
    private fun inquiriesOn(enabled: Boolean = true, asked: Boolean = false) = app.container.also { c ->
        c.inquiryApi = FakeInquiryApi(enabled)
        c.inquiryStore.asked = asked
    }

    /** [UX-05] Opens 도움말과 문의 the way another screen does: the link, then the navigation. */
    private fun Screen.askFromScreen(start: com.bamnat.app.ui.settings.InquiryStart) {
        com.bamnat.app.ui.settings.InquiryLinks.ask(start)
        settle()
    }

    // ---- 출시 전 개선 (1.18) [CREW-01][CUST-01][CAL-20][INQ-02][GUIDE-08], on a common phone and a small one ----
    private fun Screen.teams() {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.TEAMS)
        settle()
    }
    /** Through the help search's link, which opens it over the calendar. */
    private fun Screen.customize() {
        com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRegistry.get("calendar.customize")!!)
        settle()
    }
    /** A 휴 after the seeded [coverDay], which already has its 대근. */
    private val offDay: LocalDate get() = coverDay.plusDays(5)
    private fun Screen.coverAsk() {
        tapText("빠른 입력", exact = true)
        tapText("야간 칠하기", exact = true, scrolls = 0)
        // On a small phone the open panel can leave the month scrolled past the day: back up a little.
        repeat(3) {
            if (runCatching { selectDay(offDay) }.isSuccess) return
            scrollDown(120)
        }
        selectDay(offDay)
    }
    private fun Screen.inquiryThread() {
        askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        tapText("내 문의:", prefix = true)
        tapText("[알람] 야간 근무 전 알람이 가끔", prefix = true)
    }
    private fun Screen.whatsNew() = content {
        com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases, {}, {}, {})
    }
    @Test fun phoneTeams() = shoot("350_phone_teams", PHONE) { teams() }
    @Test fun smallPhoneTeams() = shoot("351_small_phone_teams", SMALL_PHONE) { teams() }
    @Test fun foldedTeams() = shoot("352_folded_teams", FOLDED) { teams() }
    @Test fun unfoldedTeams() = shoot("353_unfolded_teams", UNFOLDED) { teams() }
    @Test fun phoneTeamsHugeText() = shoot("354_phone_teams_huge_text", PHONE, fontScale = 2f) { teams() }
    @Test fun phoneCustomize() = shoot("355_phone_customize", PHONE) { customize() }
    @Test fun smallPhoneCustomize() = shoot("356_small_phone_customize", SMALL_PHONE) { customize() }
    @Test fun phoneCoverAsk() = shoot("357_phone_cover_ask", PHONE) { coverAsk() }
    @Test fun smallPhoneCoverAsk() = shoot("358_small_phone_cover_ask", SMALL_PHONE) { coverAsk() }
    @Test fun phoneInquiryProgress() {
        inquiriesOn(asked = true)
        shoot("359_phone_inquiry_progress", PHONE) { inquiryThread() }
    }
    @Test fun smallPhoneInquiryProgress() {
        inquiriesOn(asked = true)
        shoot("360_small_phone_inquiry_progress", SMALL_PHONE) { inquiryThread() }
    }
    @Test fun phoneWhatsNew() = shoot("361_phone_whats_new", PHONE) { whatsNew() }
    @Test fun smallPhoneWhatsNew() = shoot("362_small_phone_whats_new", SMALL_PHONE) { whatsNew() }
    @Test fun phoneStatsCustomize() = shoot("363_phone_stats_customize", PHONE) {
        tab(3, wide = false)
        tapText("화면 꾸미기", exact = true, scrolls = 0)
    }
    @Test fun smallPhoneStats() = shoot("364_small_phone_stats", SMALL_PHONE) { tab(3, wide = false) }
    @Test fun smallPhoneSettings() = shoot("365_small_phone_settings", SMALL_PHONE) { tab(4, wide = false) }
    @Test fun smallPhoneMemos() = shoot("366_small_phone_memos", SMALL_PHONE) { tab(2, wide = false) }
    @Test fun smallPhoneMoney() = shoot("367_small_phone_money", SMALL_PHONE) { tab(1, wide = false) }
    private fun Screen.year() {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.YEAR)
        settle()
    }
    @Test fun phoneYear() = shoot("369_phone_year", PHONE) { year() }
    @Test fun foldedYear() = shoot("370_folded_year", FOLDED) { year() }
    @Test fun unfoldedYear() = shoot("371_unfolded_year", UNFOLDED) { year() }
    @Test fun smallPhoneYear() = shoot("372_small_phone_year", SMALL_PHONE) { year() }
    @Test fun phoneLockCover() = shoot("373_phone_lock_cover", PHONE) { content { com.bamnat.app.ui.LockCover {} } }
    @Test fun phonePrivacyLock() = shoot("374_phone_settings_privacy_lock", PHONE) { settingsPage("개인정보와 권한") }

    // ---- [MON-04..MON-10] 1.19.0: 프로, its lock, the trial's question, the thank-you, ad privacy ----
    private val samplePlans = listOf(
        com.bamnat.app.billing.Plan("monthly", "4,990원", 4_990_000_000, "KRW", "P1M", trial = true, offerToken = "m"),
        com.bamnat.app.billing.Plan("yearly", "39,000원", 39_000_000_000, "KRW", "P1Y", trial = true, offerToken = "y"),
    )

    /** After the 정식 출시 switch, with Play's plans, as a phone without 프로 sees it. */
    private fun launched(config: com.bamnat.app.tier.LaunchConfig = com.bamnat.app.tier.LaunchConfig(billingLive = true),
                         then: Screen.() -> Unit = {}): Screen.() -> Unit = {
        val c = app.container
        c.billing = com.bamnat.app.billing.NoBilling(samplePlans)
        c.remoteConfig.save(config.copy(fetchedAt = java.time.Instant.now()))
        c.featureGate.refresh()
        settle()
        then()
    }
    private fun pro(): Screen.() -> Unit = { content { com.bamnat.app.ui.pro.ProScreen(onBack = {}) } }

    @Test fun phoneProBeforeLaunch() = shoot("375_phone_pro_before_launch", PHONE, pro())
    @Test fun phoneProLaunched() = shoot("376_phone_pro_launched", PHONE, launched(then = pro()))
    @Test fun phoneProPlans() = shoot("377_phone_pro_plans", PHONE, launched { pro().invoke(this); reveal("구매 복원", exact = true) })
    @Test fun unfoldedProLaunched() = shoot("378_unfolded_pro_launched", UNFOLDED, launched(then = pro()))
    // Robolectric draws no blur, so this shows the cover Android 11 and older get (ProLock); 12+ blurs under a lighter one.
    @Test fun foldedMoneyStatsLocked() = shoot("379_folded_money_stats_locked", FOLDED, launched {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 30)
        tab(1, wide = false); tapText("분석", exact = true, scrolls = 0); reveal("돈 통계", exact = true)
    })
    @Test fun phoneTrialQuestion() = shoot("380_phone_trial_question", PHONE, launched {
        val start = java.time.Instant.now().minus(java.time.Duration.ofDays(26))
        app.container.proStore.setSub(com.bamnat.app.tier.SubRecord("bamnat_pro", "monthly", com.bamnat.core.tier.PlaySub.State.ACTIVE,
            java.time.Instant.now().plus(java.time.Duration.ofDays(3)), trial = true, autoRenew = true, purchasedAt = start, verified = false,
            checkedAt = java.time.Instant.now()))
    })
    @Test fun phoneLaunchThanks() = shoot("381_phone_launch_thanks", PHONE, launched(com.bamnat.app.tier.LaunchConfig(billingLive = true,
        launchedAt = java.time.Instant.now().minus(java.time.Duration.ofDays(1)), thanksMonths = 3)))
    @Test fun phoneAdPrivacy() = shoot("382_phone_ad_privacy", PHONE) { settingsPage("개인정보와 권한"); tapText("광고 개인정보 선택", exact = true) }
    @Test fun phoneProLargeText() = shoot("384_phone_pro_large_text", PHONE, fontScale = 1.5f, launched(then = pro()))
    @Test fun phoneSettingsProRow() = shoot("383_phone_settings_pro_row", PHONE, launched { tab(4, wide = false); reveal("기능 켜고 끄기", exact = true) })
    @Test fun smallPhoneQuickInput() = shoot("368_small_phone_quick_input", SMALL_PHONE) { tapText("빠른 입력", exact = true) }

    // ---- [PROV-01..PROV-08] 1.20.0: the rest of 프로's four pillars ----
    @Test fun phonePayReport() = shoot("385_phone_pay_report", PHONE) {
        payScreen(Tier.PRO, withProfile = true).invoke(this); reveal("급여 리포트", exact = true)
    }
    @Test fun phonePayReportLocked() = shoot("386_phone_pay_report_locked", PHONE, launched {
        payScreen(Tier.FREE, withProfile = true).invoke(this); reveal("급여 리포트", exact = true)
    })
    @Test fun phoneStatsCompare() = shoot("387_phone_stats_compare", PHONE) {
        runBlocking { app.container.payStore.save(PayViewModel(app.container).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        tab(3, wide = false); reveal("${java.time.YearMonth.now().monthValue}월 비교", exact = true)
    }
    @Test fun phoneStatsYear() = shoot("388_phone_stats_year", PHONE) {
        runBlocking { app.container.payStore.save(PayViewModel(app.container).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        tab(3, wide = false); reveal("${java.time.Year.now().value}년 근무·급여", exact = true)
    }

    /** The link 민지 made this month, with the calendar feed on. */
    private fun Screen.shareWithFeed(then: Screen.() -> Unit = {}) {
        val c = app.container
        c.shareApi = shares
        c.shareLinks.save(com.bamnat.app.share.ShareLinkState("Ab3dEfGh5JkLmNoPqRsTuv", "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv",
            "t", java.time.Instant.now().plus(java.time.Duration.ofDays(150)), "민지", 180, java.time.YearMonth.now()))
        c.shareLinks.feed = true
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, java.time.YearMonth.now(), {}, {}, client = shares) }
        settle()
        then()
    }
    @Test fun phoneShareFeed() = shoot("389_phone_share_feed", PHONE) { shareWithFeed { reveal("구독 주소 복사", exact = true) } }
    @Test fun phoneShareSecondLink() = shoot("390_phone_share_second_link", PHONE) { shareWithFeed { tapText("+ 새 링크", exact = true, scrolls = 0) } }
    @Test fun phoneShareRosterPdf() = shoot("391_phone_share_roster_pdf", PHONE) { shareWithFeed { reveal("${java.time.YearMonth.now().year}년 PDF", exact = true) } }

    @Test fun phoneDataAutoBackup() = shoot("392_phone_data_auto_backup", PHONE) {
        val c = app.container
        c.keyBox = com.bamnat.app.backup.AutoBackupTest.FakeKeyBox()
        val file = java.io.File(app.filesDir, "drive/${com.bamnat.app.backup.AutoBackup.FILE_NAME}").apply { parentFile!!.mkdirs(); delete() }
        runBlocking { c.autoBackup.turnOn(android.net.Uri.fromFile(file), "밤낮1234".toCharArray()) }
        settingsPage("데이터"); reveal("매일 자동 백업", exact = true)
    }
    @Test fun phoneDataPhoneCalendar() = shoot("398_phone_data_phone_calendar", PHONE) {
        settingsPage("데이터"); reveal("휴대폰 캘린더에 근무 쓰기", exact = true)
    }

    @Test fun phoneSureAlarm() = shoot("393_phone_sure_alarm", PHONE) {
        val c = app.container
        val night = runBlocking { c.scheduleRepository.shiftTypes.first() }.firstOrNull { it.name.contains("야") }?.id
        c.settingsRepository.update { it.copy(alarmMission = "math", wakeCheckMinutes = 5,
            shiftMissions = if (night != null) mapOf(night to "shake") else emptyMap()) }
        settingsPage("알람"); reveal("근무마다 다르게", exact = true); reveal("묻는 때", exact = false)
    }
    private fun Screen.mission(kind: com.bamnat.core.alarm.AlarmMission, rangFor: java.time.Duration) {
        val sensors = app.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(sensors).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER))
        val since = System.currentTimeMillis() - rangFor.toMillis()
        content { com.bamnat.app.alarm.MissionScreen(kind, since, 5, onSnooze = {}, onDone = {}) }
    }
    @Test fun phoneMissionMath() = shoot("394_phone_mission_math", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofSeconds(40)) }
    @Test fun phoneMissionShake() = shoot("395_phone_mission_shake", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.SHAKE, java.time.Duration.ofMinutes(2)) }
    @Test fun phoneMissionSkip() = shoot("396_phone_mission_skip", PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofMinutes(6)) }
    @Test fun smallPhoneMissionMath() = shoot("397_small_phone_mission_math", SMALL_PHONE) { mission(com.bamnat.core.alarm.AlarmMission.MATH, java.time.Duration.ofSeconds(40)) }

    // ---- [208] Store pictures (1.18.0): a common phone, no one-time hints, the phone's alarm permissions given ----
    private fun clean(then: Screen.() -> Unit = {}): Screen.() -> Unit {
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT, Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric cannot grant the full-screen permission, so the phone is told it predates Android 14,
        // where it needs none; Robolectric puts the SDK level back before the next test.
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 33)
        com.bamnat.app.ui.calendar.HintStore(app).markDone(com.bamnat.app.ui.calendar.HintStore.SHARE)
        return then
    }
    @Test fun store1Calendar() = shoot("store_1_calendar", PHONE, clean())
    @Test fun store2Teams() = shoot("store_2_teams", PHONE, clean { teams() })
    @Test fun store3Pay() = shoot("store_3_pay", PHONE, clean(payScreen(Tier.PRO, withProfile = true)))
    @Test fun store4CoverAsk() = shoot("store_4_cover", PHONE, clean { coverAsk() })
    @Test fun store5Day() = shoot("store_5_day", PHONE, clean { openDay(dayCell) })
    @Test fun store6Weeks() = shoot("store_6_weeks", PHONE, clean {
        app.container.settingsRepository.update { it.copy(statsLayout = "weeks,three,-tiles,-pay,kinds,trend") }
        tab(3, wide = false)
    })
    @Test fun store7Styles() = shoot("store_7_styles", PHONE) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    @Test fun store8Inquiry() {
        inquiriesOn(asked = true)
        shoot("store_8_inquiry", PHONE, clean { inquiryThread() })
    }
    @Test fun storeTablet() = shoot("store_tablet", UNFOLDED, clean())
    @Test fun storeTabletYear() {
        // The sample pattern starts in July; for the store the same rotation fills the whole year.
        runBlocking {
            val repo = app.container.scheduleRepository
            val p = repo.pattern.first() as WorkPattern.Rotation
            repo.savePattern(p.copy(startDate = LocalDate.of(2026, 1, 1), anchorDate = p.startDate))
        }
        shoot("store_tablet_year", UNFOLDED, clean { year() })
    }

    @Test fun foldedCalendarShare() = shoot("320_folded_calendar_share", FOLDED)
    @Test fun unfoldedCalendarShare() = shoot("321_unfolded_calendar_share", UNFOLDED)
    @Test fun phoneCalendarShare() = shoot("322_phone_calendar_share", PHONE)
    @Test fun phoneCalendarShareHugeText() = shoot("323_phone_calendar_share_huge_text", PHONE, fontScale = 2f)
    @Test fun foldedSwitcherMineOnly() {
        app.container.shareApi = shares
        shoot("324_folded_switcher_mine_only", FOLDED) { tapText("달력 바꾸기", prefix = true) }
    }
    @Test fun foldedReceiveNoLinkYet() {
        app.container.shareApi = shares
        shoot("325_folded_receive_no_link", FOLDED) {
            tapText("달력 바꾸기", prefix = true)
            tapText("동료 근무표 받기", exact = true)
            reveal("링크 부탁하는 메시지 보내기")
        }
    }
    @Test fun foldedIdentityBarWithFaces() = shoot("326_folded_identity_bar_faces", FOLDED) { receivedOn() }

    @Test fun foldedSettingsHubHelp() {
        inquiriesOn(asked = true)
        shoot("327_folded_settings_hub", FOLDED) { tab(4, wide = false) }
    }
    @Test fun foldedSettingsSearch() = shoot("328_folded_settings_search", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람 소리")
    }
    @Test fun foldedSettingsSearchFound() = shoot("329_folded_settings_search_found", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람 소리")
        tapText("알람음", exact = true)
    }
    @Test fun unfoldedSettingsSearchFound() = shoot("330_unfolded_settings_search_found", UNFOLDED) {
        tab(4, wide = true)
        typeInto("설정 찾기", "다크 모드")
        tapText("테마", exact = true)
    }
    @Test fun phoneSettingsSearchHugeText() = shoot("331_phone_settings_search_huge_text", PHONE, fontScale = 2f) {
        tab(4, wide = false)
        typeInto("설정 찾기", "백업")
    }
    @Test fun foldedSettingsCalendarShareRows() {
        app.container.shareApi = shares
        shoot("332_folded_settings_calendar_share", FOLDED) { settingsPage("달력 화면") }
    }
    @Test fun foldedSettingsSearchNothing() = shoot("333_folded_settings_search_nothing", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "화면이 자꾸 꺼져요")
    }

    @Test fun foldedHelpHome() {
        inquiriesOn()
        shoot("334_folded_help_home", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
        }
    }
    @Test fun foldedHelpSearch() {
        inquiriesOn()
        shoot("335_folded_help_search", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            typeInto("기능과 도움말 찾기", "알람이 안 울려요")
            tapText("알람이 안 울리거나 늦게 울려요", exact = true)
        }
    }
    @Test fun foldedHelpFromCalendar() {
        inquiriesOn()
        shoot("336_folded_help_from_calendar", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "달력", help = true))
        }
    }
    @Test fun foldedInquiryFormTips() {
        inquiriesOn()
        shoot("337_folded_inquiry_form_tips", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "근무표 공유", kind = com.bamnat.app.company.InquiryKind.QUESTION))
            typeInto("[근무표 공유] ", "[근무표 공유] 동료에게 근무표 링크를 보내고 싶어요")
            reveal("혹시 이런 내용인가요?")
        }
    }
    @Test fun foldedInquiryMineUnread() {
        inquiriesOn(asked = true)
        shoot("338_folded_inquiry_mine_unread", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
        }
    }
    @Test fun unfoldedHelpHome() {
        inquiriesOn()
        shoot("339_unfolded_help_home", UNFOLDED) {
            tab(4, wide = true)
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        }
    }
    @Test fun phoneHelpHugeText() {
        inquiriesOn()
        shoot("340_phone_help_huge_text", PHONE, fontScale = 2f) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        }
    }
    @Test fun foldedHelpDark() {
        inquiriesOn()
        shoot("341_folded_help_dark", "w369dp-h905dp-port-night-420dpi") {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
            tapText("근무표를 동료·가족에게 보내고 싶어요", exact = true)
        }
    }
    @Test fun foldedHelpByMail() {
        inquiriesOn(enabled = false)
        shoot("342_folded_help_mail", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            reveal("메일로 문의하기")
        }
    }
    @Test fun foldedInquiryByMailForm() {
        inquiriesOn(enabled = false)
        shoot("343_folded_inquiry_mail_form", FOLDED) {
            askFromScreen(com.bamnat.app.ui.settings.InquiryStart(about = "달력"))
            tapText("궁금해요", exact = true)
            reveal("메일에 함께 적는 정보")
        }
    }
    @Test fun foldedSettingsSearchWithHelp() = shoot("346_folded_settings_search_help", FOLDED) {
        tab(4, wide = false)
        typeInto("설정 찾기", "알람이 안 울려요")
    }
    @Test fun phoneSettingsHubNewAnswerHugeText() {
        inquiriesOn(asked = true)
        shoot("347_phone_settings_new_answer_huge_text", PHONE, fontScale = 2f) { tab(4, wide = false) }
    }
    @Test fun foldedSleepNow() = shoot("344_folded_sleep_now", FOLDED) {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.SLEEP)
        settle()
    }
    @Test fun foldedMemosNewMemo() = shoot("345_folded_memos_new_memo", FOLDED) { tab(2, wide = false) }

    // ---- 급여 커스텀·설정 쉽게 (1.15) [PAYS-07..11][SETUP-03..13] ----
    /** [PAYS-07] 우리 회사 항목 만들기 over the pay screen, opened at [kind] or at the list of kinds. */
    private fun itemBuilder(kind: com.bamnat.app.ui.pay.ItemKind? = null, gap: Long? = null): Screen.() -> Unit =
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) {
                com.bamnat.app.ui.pay.CustomItemDialog(vm, profile, snap, java.time.YearMonth.now(), onDismiss = {}, onSave = {},
                    gap = gap, startKind = kind)
            }
        }

    private fun Screen.deductForm() {
        itemBuilder(com.bamnat.app.ui.pay.ItemKind.DEDUCT).invoke(this)
        typeInto("이름 (선택)", "기숙사비")
        typeInto("한 달 금액", "50000")
        repeat(2) { settle() }
    }

    /** [PAYS-07][PAYS-09] A deduction made from the pay screen, then the notice and the invitation to share the way. */
    private fun Screen.madeDeduction() {
        companyOn()
        // As the app opens it: with the way to 회사 설정 보내기.
        payScreen(Tier.PRO, withProfile = true) { vm -> com.bamnat.app.ui.pay.PayScreen(vm, onBack = {}, onShareWay = {}) }.invoke(this)
        tapText("우리 회사 항목 만들기", exact = true)
        tapText("빠지는 돈 (차감)", exact = true)
        typeInto("이름 (선택)", "기숙사비")
        typeInto("한 달 금액", "50000")
        repeat(2) { settle() }
        tapText("만들기", exact = true)
        reveal("만들었어요", exact = false)
    }

    /** [PAYS-08] 명세서로 맞춰 보기 with the way to 회사 설정 보내기, for the invitation after a match. */
    private fun matchWithShare(): Screen.() -> Unit = payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            com.bamnat.app.ui.pay.PayslipMatchDialog(vm, profile, snap, java.time.YearMonth.now(), onDismiss = {}, onShareWay = {})
        }
    }

    /** This month as the app has it, for a payslip a little off from it. */
    private fun appTotalNow(): Long = runBlocking {
        val c = app.container
        PayViewModel(c).calculateOff(c.payStore.profile.value!!, c.scheduleRepository.snapshot.first(), java.time.YearMonth.now()).total
    }

    private fun Screen.payslipGap() {
        matchWithShare().invoke(this)
        // A company allowance the app does not have; no setting explains all of it. A share of the month, not a
        // fixed amount: 52,000원 came within the 0.5% match of a 쉬는 날 근무 change on some days of the month.
        val total = appTotalNow()
        val gross = total + total * 35 / 1000
        tapText("네, 같았어요", exact = true)
        typeInto("지급 총액", gross.toString())
        tapText("맞춰 보기", exact = true)
        repeat(3) { settle() }
        reveal("이 차이를 항목으로 만들기")
    }

    private fun Screen.payslipMatched() {
        companyOn()
        matchWithShare().invoke(this)
        val gross = appTotalNow()
        tapText("네, 같았어요", exact = true)
        typeInto("지급 총액", gross.toString())
        tapText("맞춰 보기", exact = true)
        repeat(3) { settle() }
        tapText("확인했어요", exact = true)
        reveal("명세서와 맞았어요", exact = false)
    }

    /** [SETUP-03][SETUP-05] One setup question with the month worked out from this phone's roster. */
    private fun wizardAt(key: String): Screen.() -> Unit = {
        val c = app.container
        val vm = PayViewModel(c)
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val month = java.time.YearMonth.now()
        val sample = vm.sampleProfile()
        content {
            com.bamnat.app.ui.pay.PayWizard(sample, {}, {}, snapshot = snap, preview = { vm.preview(it, snap, month) }, startAt = key)
        }
        repeat(4) { settle() }
    }

    private fun shiftEditor(): Screen.() -> Unit = {
        val day = runBlocking { app.container.scheduleRepository.shiftTypes.first().first { it.name == "주간" } }
        content { com.bamnat.app.ui.shifts.ShiftTypeEditorDialog(day, onDismiss = {}, onSave = {}, onDelete = {}) }
    }

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
    @Test fun foldedSetupStepsLeft() = shoot("279_folded_setup_steps_left", FOLDED, fresh(null) { tapText("순서가 돌아가요") })
    @Test fun unfoldedSetupStepsLeft() = shoot("280_unfolded_setup_steps_left", UNFOLDED, fresh(null) {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간")
    })
    @Test fun foldedShiftTypesEasy() = shoot("281_folded_shift_types_easy", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
    }
    @Test fun unfoldedShiftTypesEasy() = shoot("282_unfolded_shift_types_easy", UNFOLDED) {
        settingsPage("내 근무", wide = true)
        tapText("근무 종류와 시간", exact = true)
    }
    @Test fun foldedShiftEditorTimeFirst() = shoot("283_folded_shift_editor_time_first", FOLDED, shiftEditor())
    @Test fun foldedShiftEditorLook() = shoot("284_folded_shift_editor_look", FOLDED) {
        shiftEditor().invoke(this)
        tapText("색과 아이콘 (선택)", exact = true)
        reveal("색상", exact = true)
    }
    @Test fun unfoldedShiftEditorTimeFirst() = shoot("285_unfolded_shift_editor_time_first", UNFOLDED, shiftEditor())
    @Test fun foldedPatternEasy() = shoot("286_folded_pattern_easy", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 주기", exact = true)
    }
    @Test fun unfoldedPatternEasy() = shoot("287_unfolded_pattern_easy", UNFOLDED) {
        settingsPage("내 근무", wide = true)
        tapText("근무 주기", exact = true)
    }
    @Test fun foldedPayItemDeductDark() = shoot("288_folded_pay_item_deduct_dark", "w369dp-h905dp-port-night-420dpi") { deductForm() }
    @Test fun phonePayItemDeductHugeText() = shoot("289_phone_pay_item_deduct_huge_text", PHONE, fontScale = 2f) { deductForm() }
    // [PAYS-10][PAYS-11] The ‘?’ in the top bar, and a 추정 badge saying what to put in.
    @Test fun foldedPayEstimateHint() = shoot("267b_folded_pay_estimate_hint", FOLDED) {
        payScreen(Tier.PRO, withProfile = true) { vm -> com.bamnat.app.ui.pay.PayScreen(vm, onBack = {}, onHelp = {}) }.invoke(this)
        tapText("추정 포함", exact = true)
    }
    // [SETUP-10] 맞았어요 said once, and the button out of the way at the end of the list.
    @Test fun foldedCompanyBoardEnd() = shoot("271b_folded_company_board_end", FOLDED) {
        val vm = com.bamnat.app.ui.company.CompanyBoardViewModel(companyOn())
        content { com.bamnat.app.ui.company.CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        settle()
        reveal("예시정밀 2공장", exact = true)
        scrollUp(800)
        settle()
    }
    // [SETUP-12] A first setup left for later, picked up again from 설정 > 내 근무.
    @Test fun foldedSettingsSetupResume() = shoot("280b_folded_settings_setup_resume", FOLDED, fresh(null) {
        tapText("순서가 돌아가요")
        tapText("나중에 할게요", exact = true)
        settingsPage("내 근무")
    })

    // ---- 가계부 (1.15) ----
    /**
     * [LEDG-06][LEDG-08][LEDG-09][LEDG-11] The seeded month made a whole ledger: a pay account, cash
     * and two cards behind the lines, budgets (배달 already over), pay-ins and bills that repeat, a
     * split payment, tags, a memo, installments, a transfer, and the months before for the year.
     */
    private fun ledger() = runBlocking<Unit> {
        val money = app.container.moneyRepository
        val month = today.withDayOfMonth(1)
        fun day(d: Int) = month.plusDays(d.toLong().coerceAtMost(today.dayOfMonth - 1L))
        val bank = money.save(com.bamnat.core.money.MoneyAccount(name = "국민 월급통장", type = com.bamnat.core.money.AccountType.BANK, opening = 1_850_000))
        val cash = money.save(com.bamnat.core.money.MoneyAccount(name = "지갑", type = com.bamnat.core.money.AccountType.CASH, opening = 62_000))
        val credit = money.save(com.bamnat.core.money.MoneyAccount(name = "신한 Deep", type = com.bamnat.core.money.AccountType.CREDIT,
            last4 = "1234", payDay = 14, payFromId = bank.id))
        val check = money.save(com.bamnat.core.money.MoneyAccount(name = "KB 체크", type = com.bamnat.core.money.AccountType.CHECK,
            last4 = "5678", payFromId = bank.id))
        // The card's bill now comes from the card itself.
        money.allFixed().filter { it.kind == MoneyKind.TRANSFER }.forEach { money.delete(it) }
        val byNote = mapOf("출근길 커피" to check, "팀 점심" to credit, "이마트" to credit, "넷플릭스" to credit, "메가커피" to cash,
            "휴대폰 요금" to bank, "배달의민족" to credit, "CU" to cash, "중고 거래" to bank)
        money.saveAll(money.allEntries().map { e ->
            val account = byNote[e.note] ?: when (e.cardLast4) { "1234" -> credit; "5678" -> check; else -> null }
            when (e.note) {
                "팀 점심" -> e.copy(accountId = account?.id, tags = listOf("회식"), memo = "민지·준호와 점심, 내가 계산")
                "이마트" -> e.copy(accountId = account?.id, parts = listOf(com.bamnat.core.money.EntryPart("생활", 18_400)), tags = listOf("장보기"))
                else -> e.copy(accountId = account?.id)
            }
        })
        listOf(
            MoneyEntry(date = day(9), kind = MoneyKind.INCOME, amount = 2_480_000, category = "급여", merchant = "사아자물류", accountId = bank.id),
            MoneyEntry(date = day(4), kind = MoneyKind.TRANSFER, amount = 100_000, category = "내 계좌", merchant = "ATM 출금",
                accountId = bank.id, toAccountId = cash.id),
            MoneyEntry(date = day(7), kind = MoneyKind.EXPENSE, amount = 459_000, category = "생활", merchant = "라마바전자 무선청소기",
                accountId = credit.id, installments = 3),
            MoneyEntry(date = day(2), kind = MoneyKind.EXPENSE, amount = 120_000, category = "여가", merchant = "가나다 펜션",
                accountId = credit.id, tags = listOf("여행")),
            MoneyEntry(date = day(6), kind = MoneyKind.EXPENSE, amount = 12_800, category = "교통", merchant = "택시", accountId = cash.id),
        ).forEach { money.save(it) }
        // Last month to compare with, and the months of the year before it.
        val last = month.minusMonths(1)
        listOf("카페" to 26_000L, "배달" to 52_000L, "장보기·쇼핑" to 143_000L, "통신" to 55_000L, "구독" to 13_500L, "교통" to 38_000L)
            .forEachIndexed { i, (c, a) -> money.save(MoneyEntry(date = last.plusDays(i * 3L + 2), kind = MoneyKind.EXPENSE, amount = a, category = c)) }
        money.save(MoneyEntry(date = last.plusDays(9), kind = MoneyKind.INCOME, amount = 2_450_000, category = "급여", merchant = "사아자물류"))
        (2 until today.monthValue).forEach { k ->
            val m = month.minusMonths(k.toLong())
            money.save(MoneyEntry(date = m.plusDays(9), kind = MoneyKind.INCOME, amount = 2_400_000 + k * 15_000L, category = "급여", merchant = "사아자물류"))
            money.save(MoneyEntry(date = m.plusDays(3), kind = MoneyKind.EXPENSE, amount = 380_000 + (k % 3) * 120_000L, category = "식비"))
            money.save(MoneyEntry(date = m.plusDays(12), kind = MoneyKind.EXPENSE, amount = 455_000 + k * 21_000L, category = "주거"))
        }
        money.setBudget(com.bamnat.core.money.MoneyBudget.TOTAL, 1_600_000)
        money.setBudget("식비", 300_000)
        money.setBudget("카페", 40_000)
        money.setBudget("배달", 15_000)
        money.setBudget("장보기·쇼핑", 200_000)
        money.save(FixedCost(name = "주말 알바비", amount = 80_000, day = 6, kind = MoneyKind.INCOME, category = "부수입",
            repeat = com.bamnat.core.money.Repeat.WEEK, accountId = bank.id))
        money.save(FixedCost(name = "자동차 보험", amount = 642_000, day = 20, category = "보험", repeat = com.bamnat.core.money.Repeat.YEAR,
            monthOfYear = today.plusMonths(1).monthValue, accountId = credit.id))
    }

    /** The money tab on one of its pages, with [ledger] seeded. */
    private fun ledgerPage(page: String?, wide: Boolean = false, then: Screen.() -> Unit = {}): Screen.() -> Unit = {
        ledger()
        tab(1, wide)
        if (page != null) tapText(page, exact = true, scrolls = 0)
        then()
    }

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

    /** [DAILY-05][DAILY-15] 메모: one 새 메모 button, the filters folded into the search box, and 화면 꾸미기. */
    @Test fun e484FoldedMemos() = shoot("484_folded_memos_one_button", FOLDED) { tab(2, wide = false) }
    @Test fun e485UnfoldedMemos() = shoot("485_unfolded_memos_one_button", UNFOLDED) { tab(2, wide = true) }
    @Test fun e486FoldedMemosFilters() = shoot("486_folded_memos_filters_open", FOLDED) {
        tab(2, wide = false)
        tapText("필터 펼치기", exact = true, scrolls = 0)
    }
    @Test fun e487FoldedMemoCustomize() = shoot("487_folded_memo_customize", FOLDED) {
        tab(2, wide = false)
        tapText("화면 꾸미기", exact = true, scrolls = 0)
    }
    @Test fun e488PhoneMemosHugeText() = shoot("488_phone_memos_huge_text", PHONE, 1.5f) { tab(2, wide = false) }

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

    /** [BACK-01] 사진도 백업 in 설정 › 데이터, and the 암호 dialog saying how many photos go in. */
    @Test fun e494FoldedSettingsBackupPhotos() = shoot("494_folded_settings_backup_photos", FOLDED) {
        settingsPage("데이터")
        reveal("사진도 백업", exact = true)
    }
    @Test fun e495FoldedBackupPasswordPhotos() = shoot("495_folded_backup_password_photos", FOLDED) {
        com.bamnat.app.ui.money.ReceiptFiles.dir(app).apply { mkdirs() }.resolve("shot_receipt.jpg").writeBytes(ByteArray(420_000))
        runBlocking {
            app.container.moneyRepository.save(com.bamnat.core.money.MoneyEntry(date = today, kind = com.bamnat.core.money.MoneyKind.EXPENSE,
                amount = 14_000, category = "식비", merchant = "김밥천국", receipt = "shot_receipt.jpg"))
        }
        content { com.bamnat.app.ui.settings.ExportPasswordDialog(onDismiss = {}, onExport = {}) }
        settle()
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

    /** [DAILY-08] 근무 종류 with the drag handle beside the arrows. */
    @Test fun e498FoldedShiftTypesDrag() = shoot("498_folded_shift_types_drag", FOLDED) {
        val vm = com.bamnat.app.ui.shifts.ShiftsViewModel(app.container)
        content { com.bamnat.app.ui.shifts.ShiftTypesScreen(vm, onBack = {}) }
        settle()
    }
    @Test fun e499PhoneShiftTypesDragHugeText() = shoot("499_phone_shift_types_drag_huge_text", PHONE, 1.5f) {
        val vm = com.bamnat.app.ui.shifts.ShiftsViewModel(app.container)
        content { com.bamnat.app.ui.shifts.ShiftTypesScreen(vm, onBack = {}) }
        settle()
    }

    /** [INV-33] An account's own 해외 주식 fee. */
    @Test fun e500FoldedInvestAccountFees() = shoot("500_folded_invest_account_fees", FOLDED, style(WorkStyle.INVEST) {
        val vm = com.bamnat.app.ui.invest.InvestViewModel(app.container, com.bamnat.app.data.PersonalCalendar.MAIN)
        content { com.bamnat.app.ui.invest.AccountSheet(vm, null) {} }
        settle()
    })

    /** [DAILY-07] 근무·급여's weeks, each a way to its week on the calendar. */
    @Test fun e501FoldedStatsWeeks() = shoot("501_folded_stats_weeks_open_week", FOLDED) {
        tab(3, wide = false)
        reveal("주별 근무시간", across = 0.5f)
    }

    /** [DAILY-01][DAILY-03][DAILY-11] Widgets: a day's memo line, every team today, an 알바's 출근 button. */
    @Test fun e502Widgets() = runBlocking<Unit> {
        val c = app.container
        c.memoRepository.save(com.bamnat.core.memo.Memo(date = today, title = "치과 3시 · 보험증 챙기기", createdAtMillis = 1))
        settle()
        e502Shoot(listOf(
            Triple("502_widget_today_memo", com.bamnat.app.widget.TodayWidget() as androidx.glance.appwidget.GlanceAppWidget, 180 to 170),
            Triple("503_widget_week_memo", com.bamnat.app.widget.WeekWidget(), 340 to 120),
            Triple("504_widget_teams", com.bamnat.app.widget.TeamsWidget(), 250 to 180),
        ))
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.HOURLY) }
        settle()
        e502Shoot(listOf(Triple("505_widget_hourly_clock", com.bamnat.app.widget.TodayWidget() as androidx.glance.appwidget.GlanceAppWidget, 200 to 180)))
    }

    private suspend fun e502Shoot(shots: List<Triple<String, androidx.glance.appwidget.GlanceAppWidget, Pair<Int, Int>>>) {
        for ((name, widget, dims) in shots) {
            val (wDp, hDp) = dims
            @OptIn(androidx.glance.ExperimentalGlanceApi::class)
            val rv = widget.compose(app, size = androidx.compose.ui.unit.DpSize(wDp.dp, hDp.dp))
            val density = app.resources.displayMetrics.density
            val w = (wDp * density).toInt()
            val h = (hDp * density).toInt()
            val parent = android.widget.FrameLayout(app)
            parent.addView(rv.apply(app, parent), android.widget.FrameLayout.LayoutParams(w, h))
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

    // ==== 1.22 묶음 E 후속 ====
    /** [DAILY-09] Half open (Flex): the month above the hinge, the day below; a Flip and a Fold on its side. */
    @Test fun e506FlipTabletopCalendar() = shoot("506_flip_tabletop_calendar", "w373dp-h839dp-port-420dpi") { halfOpen() }
    @Test fun e507UnfoldedTabletopCalendar() = shoot("507_unfolded_tabletop_calendar", "w823dp-h707dp-land-420dpi") { halfOpen() }

    /** [DAILY-15] Unfolded 메모: the list on the left 40%, the memo opened from it on the right 60%. */
    @Test fun e508UnfoldedMemoPanes() = shoot("508_unfolded_memo_list_detail", UNFOLDED) {
        tab(2, wide = true)
        tapText("인수인계", exact = true)
    }

    // ==== 1.22 묶음 G ====
    // [ONBOARD-03][ONBOARD-04] 추천 기능 한 번에 켜기, in place of the consent pages of 1.13~1.21.
    private fun recommended(items: Set<ConsentItem> = ConsentItem.entries.toSet(), then: Screen.() -> Unit = {}): Screen.() -> Unit {
        val c = app.container
        c.consentStore.forget()
        c.consentStore.availableForTest = items
        return fresh(null, then)
    }

    @Test fun foldedRecommended() = shoot("520_folded_recommended", FOLDED, run {
        shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        recommended()
    })
    @Test fun unfoldedRecommended() = shoot("521_unfolded_recommended", UNFOLDED, recommended())
    @Test fun phoneRecommendedHugeText() = shoot("522_phone_recommended_huge_text", PHONE, fontScale = 2f, recommended())
    @Test fun recommendedLaterLeadsToTheFirstSetupWithFeaturesOn() = shoot("523_folded_recommended_then_setup", FOLDED,
        recommended(setOf(ConsentItem.CALENDAR)) {
            tapText("허용은 나중에 할게요")
            // The first setup comes next, the screen does not come back, and the recommended features are on.
            tapText("순서가 돌아가요")
            val c = app.container
            assertTrue(c.consentStore.seen.value!!.contains(ConsentItem.CALENDAR))
            assertTrue(c.consentStore.recommendOffered)
            assertEquals(com.bamnat.core.consent.RecommendedPlan.NEW_INSTALL, com.bamnat.app.data.RecommendedSettings.on(c.settingsRepository.current))
        })
    @Test fun foldedRecommendedFromSettings() = shoot("524_folded_recommended_settings", FOLDED) {
        // A phone set up before keeps its own values: two on, two off.
        app.container.settingsRepository.update { it.copy(briefing = true, todayShiftNotice = true, bedtimeNotice = false, commuteNotice = false) }
        com.bamnat.app.ui.settings.SettingsLinks.go(com.bamnat.app.ui.settings.SettingsGo.RECOMMENDED)
        settle()
        tapText("퇴근길 안전")
        tapText("이대로 저장")
        val on = com.bamnat.app.data.RecommendedSettings.on(app.container.settingsRepository.current)
        assertTrue("the switch tapped is saved", com.bamnat.core.consent.RecommendedFeature.COMMUTE in on)
        assertTrue("the one left off stays off", com.bamnat.core.consent.RecommendedFeature.BEDTIME !in on)
    }
    @Test fun phoneWhatsNewRecommends() = shoot("525_phone_whats_new_recommend", PHONE) {
        content { com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases.take(1), {}, {}, {}, recommendOff = 2) }
    }

    // ==== 1.22 묶음 A ====
    // [PAYR-04][PAYR-05] 평일 근무 통계: 예상 실수령 from the 간이세액표, with 가족·자녀 under 4대보험.
    private fun dayTakeHome(wide: Boolean = false): Screen.() -> Unit = {
        val c = app.container
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(taxFamily = 3, taxChildren = 1), keepUndo = false) }
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.DAY) }
        settle()
        tab(3, wide = wide)
        reveal("가족 3명", exact = true)
    }
    @Test fun foldedDayTakeHome() = shoot("400_folded_day_take_home_family", FOLDED, dayTakeHome())
    @Test fun phoneDayTakeHomeHugeText() = shoot("401_phone_day_take_home_family_huge_text", PHONE, fontScale = 2f, dayTakeHome())
    @Test fun unfoldedDayTakeHome() = shoot("405_unfolded_day_take_home_family", UNFOLDED, dayTakeHome(wide = true))
    // [PAYR-06] 임금표: 보통인부 일당 next to the 대한건설협회 시중노임단가.
    @Test fun foldedPlazaRatesGov() = shoot("402_folded_plaza_rates_gov", FOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }
    @Test fun phonePlazaRatesGovHugeText() = shoot("403_phone_plaza_rates_gov_huge_text", PHONE, fontScale = 2f) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }
    @Test fun unfoldedPlazaRatesGov() = shoot("404_unfolded_plaza_rates_gov", UNFOLDED) {
        val vm = com.bamnat.app.ui.company.RatesViewModel(companyOn())
        content { com.bamnat.app.ui.company.RatesScreen(vm, onBack = {}) }
        reveal("정부 공표 전국 평균")
    }

    // ==== 1.22 묶음 C ====
    // [REST-01][REST-03] 근무표 점검 기준: one sheet, a preset away from a hospital's rules, and the switch to turn it off.
    private fun restRules(): Screen.() -> Unit = style(WorkStyle.SHIFT) {
        content {
            com.bamnat.app.ui.calendar.RestRulesSheet(com.bamnat.core.schedule.RestRules(), com.bamnat.core.schedule.RotationSpeed.FAST, {}, {})
        }
        settle()
    }
    @Test fun foldedRestRules() = shoot("430_folded_rest_rules", FOLDED, restRules())
    @Test fun phoneRestRulesHugeText() = shoot("431_phone_rest_rules_huge_text", PHONE, 1.5f, restRules())
    // [REST-01][NURS-05][JOB-03] The calendar's check card: 기준 바꾸기, this month's nights and their pay.
    @Test fun foldedMonthRestCard() = shoot("432_folded_month_rest_card", FOLDED, style(WorkStyle.SHIFT) {
        app.container.settingsRepository.update { it.copy(restRules = com.bamnat.core.schedule.RestRules(maxNightsInRow = 1).encode()) }
        settle()
        reveal("기준 바꾸기", exact = true)
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
        reveal("교대·현업 근무", exact = true)
    }
    // [NURS-06] A received roster read with the user's own rules.
    @Test fun foldedReceivedDutyCheck() = shoot("436_folded_received_duty_check", FOLDED) {
        receivedOn()
        app.container.settingsRepository.update { it.copy(restRules = com.bamnat.core.schedule.RestRules(maxNightsInRow = 1).encode()) }
        settle()
        tapText("민지의 근무표 보기", exact = true)
        reveal("님 근무표 점검")
    }
    // [REST-01] Settings keeps one row for it, beside 휴식 점검.
    @Test fun foldedSettingsRestRules() = shoot("437_folded_settings_rest_rules", FOLDED) {
        settingsPage("잠과 휴식")
        reveal("근무표 점검 기준", exact = true)
    }
}

/** [SHR-02] The share server for screenshots: links people sent, answered at once. */
private class FakeShareApi : com.bamnat.app.share.ShareApi {
    override val enabled = true
    val links = mutableMapOf<String, com.bamnat.app.share.ReceivedShare>()
    override suspend fun create(payload: org.json.JSONObject) = com.bamnat.app.share.ShareLinkState("Ab3dEfGh5JkLmNoPqRsTuv",
        "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv", "t", java.time.Instant.now().plus(java.time.Duration.ofDays(30)), payload.optString("title"))
    override suspend fun update(state: com.bamnat.app.share.ShareLinkState, payload: org.json.JSONObject) = state
    override suspend fun delete(state: com.bamnat.app.share.ShareLinkState) = Unit
    override suspend fun read(id: String) = links[id] ?: throw com.bamnat.app.share.ShareLinkException("not_found")
}

/** [COMM-04] The company rules server for screenshots: made-up companies, answers at once. */
private class FakeCompanyApi : com.bamnat.app.company.CompanyApi {
    override val enabled = true
    var file = "{}"
    private val now = java.time.Instant.now()

    private fun status(id: String, status: String, company: String, dept: String, points: Int, persons: Int, ask: Boolean = false,
        review: Boolean = false, reason: String? = null, reward: com.bamnat.app.company.Reward? = null, daysAgo: Long = 0) =
        com.bamnat.app.company.ReportStatus(id, status, ask, "c-$company", company, dept, if (ask) "" else "s-$id", !ask, points, persons, 3, review,
            now.minus(java.time.Duration.ofDays(daysAgo)), null, reason, null, reward, 3)

    val mine = listOf(
        status("r3", "answered", "가나다화학", "", 0, 0, ask = true, reason = "알려 주신 방식을 앱에 넣을게요. 업데이트되면 바로 설정할 수 있어요", daysAgo = 1),
        status("r2", "pending", "라마바전자", "생산1팀", 2, 1, daysAgo = 3),
        status("r1", "adopted", "(주)예시정밀", "생산팀", 4, 3, reward = com.bamnat.app.company.Reward(3, "owed"), daysAgo = 20),
    )

    override suspend fun search(query: String) =
        if (com.bamnat.app.company.CompanyText.norm(query).let { "예시" in it }) listOf(
            com.bamnat.app.company.CompanyHit("c1", "(주)예시정밀", null, 1, 1),
            com.bamnat.app.company.CompanyHit("c2", "예시정밀 2공장", "예시정밀2", 0, 1),
        ) else emptyList()

    override suspend fun company(id: String): com.bamnat.app.company.CompanyPage {
        val main = listOf(
            "근무" to "주간 08:00~20:00 휴게 60분 · 야간 20:00~08:00 휴게 60분 · 오후 14:00~22:00 · 휴무(쉬는 날)",
            "순서 5일" to "주간 주간 야간 야간 휴무",
            "연장·휴무일 근무" to "잔업(근무 뒤 2시간) · 특근(09:00~18:00) · 대근(모든 근무 대신)",
        )
        val pay = listOf(
            "사업장" to "5인 이상 · 월 180시간",
            "가산율" to "법대로",
            "대근" to "8시간에 기본급 10% + 연장 가산 50%(8시간 넘게) + 야간 가산 50%",
            "특근" to "1회 정액 (금액은 각자 넣어요)",
            "수당" to "자격수당(매달) · 야간수당(근무마다) · 상여(2·4·6·8·10·12월 기본급 100%)",
        )
        return com.bamnat.app.company.CompanyPage(id, "(주)예시정밀", listOf("예시정밀"), listOf(
            com.bamnat.app.company.SharedSetup("s1", true, 5, 3, 1, 3, "생산팀", 2, file, main + pay, trust = 75, agree = 75, posts = 4),
            com.bamnat.app.company.SharedSetup("s2", false, 2, 1, 0, 3, "포장팀", null, file, main.take(2), trust = 67, agree = 100),
        ))
    }

    override suspend fun terms() = com.bamnat.app.company.CompanyTerms(3, 3)

    override suspend fun report(device: String, company: String, dept: String, verified: Boolean, note: String, setup: org.json.JSONObject, ask: Boolean) =
        status("r9", if (ask) "ask" else "pending", company, dept, if (verified) 2 else 1, 1, ask = ask)

    override suspend fun status(id: String) = mine.firstOrNull { it.id == id }
    override suspend fun takeBack(device: String, id: String) = Unit
    override suspend fun forgetMine(device: String) = mine.size
    override suspend fun vote(device: String, company: String, setup: String, ok: Boolean, verified: Boolean, why: String?) =
        com.bamnat.app.company.VoteResult(4, 3, 1, 3, true)

    // [COMM-06][COMM-07] 회사 규칙 광장 and the talk on a rule set.
    private fun item(id: String, company: String, dept: String, adopted: Boolean, points: Int, persons: Int, bad: Int, trust: Int, agree: Int,
        posts: Int, hours: Long, gist: String, cycle: String) = com.bamnat.app.company.BoardItem(
        "c-$id", company, "s-$id", dept, adopted, points, persons, bad, 3, trust, agree, posts, if (adopted) 1 else null,
        now.minus(java.time.Duration.ofHours(hours)), gist, cycle, true)

    override suspend fun board(adopted: Boolean, from: Int, q: String, job: String, region: String, sort: String) = com.bamnat.app.company.Board(
        (if (adopted) listOf(
            item("a1", "(주)예시정밀", "생산팀", true, 5, 3, 1, 75, 75, 4, 30, "주간 08:00~20:00 · 야간 20:00~08:00 · 휴무(쉬는 날)", "순서 5일"),
            item("a2", "사아자물류", "", true, 4, 3, 0, 100, 100, 0, 80, "주간 09:00~18:00 · 휴무(쉬는 날)", "순서 7일"),
        ) else listOf(
            item("v1", "가나다화학", "품질팀", false, 2, 2, 0, 67, 100, 3, 1, "주간 08:30~17:30 휴게 60분 · 휴일(쉬는 날)", "순서 7일"),
            item("v2", "라마바전자", "생산1팀", false, 3, 2, 1, 67, 67, 5, 5, "주간 07:00~19:00 · 야간 19:00~07:00 · 휴무(쉬는 날)", "순서 8일"),
            item("v3", "예시정밀 2공장", "", false, 1, 1, 0, 33, 100, 0, 26, "오전 06:00~14:00 · 오후 14:00~22:00 · 야간 22:00~06:00", "순서 6일"),
        )).filter { q.isBlank() || it.company.contains(q.trim()) },
        null,
    )

    override suspend fun talk(device: String, company: String, setup: String) = com.bamnat.app.company.Talk(listOf(
        com.bamnat.app.company.TalkPost("p1", 1, "sender", true, "저희 품질팀은 이렇게 돌아요. 10월 명세서랑 맞춰 봤어요.", now.minus(java.time.Duration.ofHours(20)), false),
        com.bamnat.app.company.TalkPost("p2", 2, "ok", false, "저도 같은 팀인데 맞아요. 격주 토요일은 특근으로 넣고 있어요.", now.minus(java.time.Duration.ofHours(6)), false),
        com.bamnat.app.company.TalkPost("p3", 3, "bad", false, "올해부터 야간 시작이 18:30으로 바뀌었어요. 확인해 주세요!", now.minus(java.time.Duration.ofHours(2)), false),
        com.bamnat.app.company.TalkPost("p4", 4, null, false, "18:30은 2공장 이야기 같아요. 본사는 그대로예요.", now.minus(java.time.Duration.ofMinutes(12)), true,
            parent = "p3", agree = 2),
        // [PLZ-09] A proposal written in fields, with its votes and where its setup stands.
        com.bamnat.app.company.TalkPost("p5", 5, null, true, "11월부터 주간이 한 시간 줄었어요. 맞으면 맞아요 눌러 주세요.", now.minus(java.time.Duration.ofMinutes(5)), false,
            nick = "야간요정", kind = "proposal", agree = 2, disagree = 0, trust = 67,
            change = listOf(com.bamnat.app.company.ChangeLine("shift", "주간 08:30~17:30", "주간 08:30~16:30", "11월 공지로 바뀌었어요")),
            proposal = com.bamnat.app.company.ProposalState("s-new", false, 2, 3)),
    ).map { if (it.id == "p2") it.copy(nick = "품질지킴이", agree = 3, trust = 100, myVote = true) else it })

    // [PLZ-01]~[PLZ-15] The plaza that grows by itself.
    override suspend fun postVote(device: String, id: String, ok: Boolean?) = com.bamnat.app.company.PostVote(3, 0, 100, ok)
    override suspend fun plazas() = listOf(
        com.bamnat.app.company.PlazaJob("factory", "제조·생산", 12, 5, 0),
        com.bamnat.app.company.PlazaJob("nurse", "간호사·병원", 4, 3, 2),
        com.bamnat.app.company.PlazaJob("construction", "건설 일용직·기능공", 1, 2, 9),
    )
    override suspend fun history(company: String, setup: String) = listOf(
        com.bamnat.app.company.RuleVersion(2, "s1", "amend", now.minus(java.time.Duration.ofDays(3)), "active", 3, "p5",
            listOf("근무: 주간 08:00~19:00 휴게 60분"), listOf("근무: 주간 08:00~20:00 휴게 60분")),
        com.bamnat.app.company.RuleVersion(1, "s0", "auto", now.minus(java.time.Duration.ofDays(60)), "superseded", 3, null, emptyList(), emptyList()),
    )
    private fun rate(id: String, trade: String, unit: String, amount: Long, status: String, agree: Int, disagree: Int, mine: Boolean = false,
        myVote: Boolean? = null, nick: String? = null, note: String = "", hours: Long = 3) = com.bamnat.app.company.Rate(
        id, "construction", "31", trade, unit, 2026, amount, note, status, agree, disagree, if (agree + disagree == 0) 0 else 100 * agree / maxOf(3, agree + disagree),
        mine, myVote, nick, now.minus(java.time.Duration.ofHours(hours)))
    override suspend fun rates(device: String, job: String, region: String, unit: String, trade: String) = com.bamnat.app.company.RateBoard(
        listOf(
            com.bamnat.app.company.RateGroup("construction", "31", "형틀목수", "day", 2026, 5, 230_000, 250_000, 280_000),
            com.bamnat.app.company.RateGroup("construction", "31", "보통인부", "day", 2026, 3, 150_000, 160_000, 170_000),
        ),
        listOf(
            rate("r1", "철근공", "day", 260_000, "pending", 2, 0, nick = "현장반장", note = "식대 별도, 숙련 5년", hours = 1),
            rate("r2", "형틀목수", "day", 250_000, "adopted", 4, 1, myVote = true, hours = 20),
            rate("r3", "보통인부", "day", 160_000, "pending", 1, 0, mine = true, hours = 30),
        ),
    )
    override suspend fun activity(device: String) = com.bamnat.app.company.Activity(
        "야간요정",
        listOf(com.bamnat.app.company.ActivityReport("r1", "c1", "(주)예시정밀", "생산팀", "s1", "adopted", 2, 3, 0, 14, now.minus(java.time.Duration.ofDays(20))),
            com.bamnat.app.company.ActivityReport("r2", "c2", "라마바전자", "생산1팀", "s2", "pending", null, 1, 1, 0, now.minus(java.time.Duration.ofDays(3)))),
        listOf(com.bamnat.app.company.ActivityProposal("p5", "c-v1", "가나다화학", "s-v1", "11월부터 주간이 한 시간 줄었어요.", 2, 0, 67, now)),
        emptyList(),
        listOf(com.bamnat.app.company.ActivityReward(3, "paid", "rule", now.minus(java.time.Duration.ofDays(3)))),
    )
}
