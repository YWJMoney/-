package com.bamnat.app.screenshots

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
import com.bamnat.app.MainActivity
import com.bamnat.app.BamnatApp
import androidx.activity.compose.setContent
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.pay.CompanyRulesEditor
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.app.ui.settings.ExtraKindsScreen
import com.bamnat.app.ui.settings.ExtraKindsViewModel
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
import com.bamnat.core.time.Today

/**
 * [QA-05] What every screenshot class shares: the sample data ([seed]), [shoot] and the [Screen] that taps and scrolls.
 * Renders the main screens with sample data at Galaxy Fold sizes into build/screenshots. Each bundle adds its
 * shots in a class of its own that extends this one, so Gradle can run the classes in parallel forks.
 */
abstract class ScreenshotBase {
    @get:Rule val compose = createEmptyComposeRule()

    /** [DAILY-09] Stands in for the fold's hinge sensor; nothing is published unless a test asks. */
    @get:Rule val fold = androidx.window.testing.layout.WindowLayoutInfoPublisherRule()

    internal val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    init { stopTheClock() }

    /** [QA-08] Every shot is drawn on 2026-10-14 (수) 10:30 in Seoul, whatever day the test runs (결정 D66). */
    internal val today = Today.date()
    /** The day most day screenshots open: 잔업 2시간 and a memo with an amount. */
    internal val dayCell = today.minusDays(2)
    /** 특근 09–18 on a day off, before today; 야간 대근 on a day off after it. */
    internal lateinit var restDayExtra: LocalDate
    internal lateinit var coverDay: LocalDate
    /** Today, or the next working day when today is off: a day off has no leave to take. */
    internal lateinit var halfDay: LocalDate

    /** A 대근 of [t]: that shift's hours, as the calendar adds it. */
    internal fun cover(t: ShiftType) =
        ExtraEntry(ExtraKind.COVER_ID, t.start, t.end, t.breakMinutes, t.breakStart.takeIf { t.breakMinutes > 0 }, coveredShiftId = t.id)

    @Before
    fun seed() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val c = app.container
        // [QA-05] No shot asks the live server: its 정식 출시 answer could land after a shot set its own (launched()).
        c.tierApi = com.bamnat.app.tier.TierClient(baseUrl = "")
        // [ONBOARD-01] Through the consent pages already; the shots of them start over.
        c.consentStore.markSeen(ConsentItem.entries)
        // [CAL-17] The one-time tip on the first date tap has its own shot (295); the others tap dates past it.
        com.bamnat.app.ui.calendar.HintStore(app).markDone(com.bamnat.app.ui.calendar.HintStore.LONG_PRESS)
        // [GUIDE-02][GUIDE-03] A settled user: the first-days card and the tips are put away, so the shots show the screens.
        com.bamnat.app.data.GuideStore.get(app).let { g ->
            listOf("guide.checklist", "guide.tips").forEach { id -> g.setOn(com.bamnat.core.guide.FeatureRegistry.get(id)!!, "main", false) }
        }
        // [EASY-05] Today's questions already asked, so the shots show the screens; 묶음 N's shots clear it.
        c.asks.set(com.bamnat.app.data.PersonalCalendar.MAIN, com.bamnat.core.profile.AskState(day = today, shownToday = com.bamnat.core.profile.AskScheduler.PER_DAY))
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
        val posted = Today.millis()
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

    internal fun shoot(name: String, qualifiers: String, actions: Screen.() -> Unit = {}) = shoot(name, qualifiers, 1f, actions)

    /** [VIS-02] With the phone's text size at [fontScale]. */
    internal fun shoot(name: String, qualifiers: String, fontScale: Float, actions: Screen.() -> Unit = {}) {
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
    internal fun shownDialogs() = ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()

    /**
     * Lets Room deliver on its background threads and the UI draw, then finishes animations. State
     * written outside composition (a tap, new content) is applied by hand: after many tests in one
     * JVM, Compose's own apply loop can stop being run by the reset main looper, and then a tap or a
     * new screen is never drawn.
     *
     * [QA-05] It stops once nothing has moved for [QUIET_ROUNDS] rounds in a row (no task waiting on the main
     * looper, no recomposition or frame pending) instead of always sleeping a second; a screen that keeps
     * moving (an endless animation, data still arriving) still gets the old second at most.
     */
    internal fun settle() {
        val looper = shadowOf(Looper.getMainLooper())
        val until = System.nanoTime() + SETTLE_MAX_NANOS
        var quiet = 0
        var rounds = 0
        while (true) {
            Thread.sleep(10)
            Snapshot.sendApplyNotifications()
            val idleBefore = looper.isIdle
            looper.idle()
            compose.mainClock.advanceTimeBy(100)
            rounds++
            val pending = Recomposer.runningRecomposers.value.any { it.hasPendingWork }
            quiet = if (idleBefore && !pending) quiet + 1 else 0
            if (rounds >= SETTLE_MIN_ROUNDS && quiet >= QUIET_ROUNDS) return
            if (System.nanoTime() > until) return
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
            // [QA-05] settle() ends once the screen is still, so what is still on its way (Play's plans, a slow
            // query on a busy machine) gets up to a few more seconds before the shot gives up.
            val end = System.nanoTime() + REACH_WAIT_NANOS
            while (System.nanoTime() < end) {
                settle()
                find(text, exact, prefix)?.let { return it }
            }
            // [QA-08] 363: the first shot of a cold worker on a busy CI runner can still show an empty window
            // after the usual wait (phoneShareFeed, CI on d6b7228 and 330916a): nothing drawn yet waits longer.
            if (shownWords().isEmpty()) {
                val more = System.nanoTime() + EMPTY_WAIT_NANOS
                while (System.nanoTime() < more) {
                    settle()
                    find(text, exact, prefix)?.let { return it }
                }
            }
            // [QA-05] On a busy machine a fling can carry the list past the text, so look back down step by step.
            repeat(scrolls) {
                scrollDown(350, across)
                find(text, exact, prefix)?.let { return it }
            }
            error("화면에서 ‘$text’를 찾지 못했어요(열린 창 ${shownDialogs().size}개). 보이는 글자: " + shownWords().joinToString(" | ").take(1500))
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

        /** [SETUP-15] The system back, as the phone's back gesture sends it. */
        fun back() {
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            settle()
        }

        /** Whether [text] is on screen now, without scrolling or waiting. */
        fun shows(text: String, exact: Boolean = false): Boolean = find(text, exact, prefix = false) != null

        /** Taps what announces [label] as its tap ("오늘의 흐름 펼치기"), for a row whose own words change with the day. */
        fun tapAction(label: String) {
            var at: Offset? = null
            scenario.onActivity { activity ->
                val root = rootOf(topWindow(activity)) ?: return@onActivity
                at = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    .firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }?.boundsInWindow?.center
            }
            touch(at ?: error("화면에서 ‘$label’ 동작을 찾지 못했어요"))
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

        /**
         * [DAY-01] Opens a day: a tap selects it and shows its preview, a tap on the chosen day opens it whole. A day in
         * the month's last rows sits under the cards above the grid on a short screen on some dates, so it scrolls to it.
         */
        fun openDay(date: LocalDate) {
            val title = Fmt.dayTitle(date)
            if (find(title, exact = false, prefix = true)?.config?.getOrNull(SemanticsProperties.Selected) != true) tapText(title, prefix = true, scrolls = 6)
            tapText(title, prefix = true, scrolls = 6)
        }

        /** [SCALE-01] [openDay] on a screen too short to show the whole month: scrolls down to the day first. */
        fun openDayBelow(date: LocalDate) {
            val title = Fmt.dayTitle(date)
            tapText(title, prefix = true, scrolls = 6)
            tapText(title, prefix = true, scrolls = 6)
        }

        /** Selects a day without opening it; like [openDay] it scrolls when the day sits under the cards on a short screen. */
        fun selectDay(date: LocalDate) = tapText(Fmt.dayTitle(date), prefix = true, scrolls = 6)

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
            // Wide, the list is the left part of the screen: it scrolls there.
            tapText(title, exact = true, across = if (wide) 0.2f else 0.5f)
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
            // Starts at 60% of the height, above a button pinned to the bottom (at 200% text on a phone it reaches
            // up to the 80% line the drag used to start on), lower only when a long drag needs the room.
            val from = maxOf(h * 0.6f, minOf(h * 0.8f, h * 0.05f + dp * d))
            (0..40).map { x to from - dp * d * it / 40 } + listOf(x to from - dp * d)
        }

        /** Drags the row that shows [text] to the left by [dp] (slowly) until [until] shows, to reach a sideways key. */
        fun swipeRowLeft(text: String, until: String, dp: Int = 200, times: Int = 5) {
            val y = reach(text, exact = false, prefix = false, scrolls = 0, across = 0.5f).boundsInWindow.center.y
            repeat(times) {
                if (find(until, exact = true, prefix = false) != null) return
                gesture { w, _, d ->
                    val from = w * 0.85f
                    (0..40).map { from - dp * d * it / 40 to y } + listOf(from - dp * d to y)
                }
            }
        }
    }

    /** [EXTRA-03] 대근 1회 기본급의 10% for 8 hours with the 연장·야간 가산 on top, 특근 a fixed amount. */
    /** [EXTRA-08] 대근 as the thread's company pays it: 8시간에 기본급 10%, 이 일 8시간 넘으면 50%, 야간 50%. */
    internal val companyCover = ExtraRule.lawUnit(overtime = 0.5, night = 0.5, holiday = 0.5, restAsHoliday = false).let { law ->
        law.copy(unit = com.bamnat.core.pay.PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480, premiums = law.premiums.map {
            if (it.key == com.bamnat.core.pay.Premium.OVERTIME) it.copy(scope = com.bamnat.core.pay.PremiumScope.Over(480)) else it
        })
    }
    /** How 1.8 and 1.9.0 stored the same 대근, before the boxes. */
    internal val legacyCover = ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = 0.1, coverMinutes = 480, legalPremiums = true)
    internal val companyRules = CompanyRules(
        extra = mapOf(
            ExtraKind.COVER_ID to companyCover,
            // A way from 1.9.0, kept as it was.
            ExtraKind.SPECIAL_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 150_000),
        ),
        annualLeaveDays = 15.0,
    )

    internal fun payScreen(
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

    // ---- M3 [FEAT-16][FEAT-17][PAYX-09..13] ----
    internal fun rulesEditor(rules: (nightId: Long) -> CompanyRules): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(previewTier = null) }
        val vm = PayViewModel(c)
        val nightId = runBlocking { c.scheduleRepository.snapshot.first().shiftTypes.first { it.name == "야간" }.id }
        runBlocking { c.payStore.save(vm.sampleProfile().copy(rules = rules(nightId)), keepUndo = false) }
        settle()
        content {
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
        }
        settle()
    }
    // ---- M9 [EXTRA-01..06][DAY-01..03][VIS-01] ----
    /** The 잔업·특근·대근 screen with pay set up, so the editor shows the company's way and the preview. */
    internal fun kindsScreen(rules: (nightId: Long) -> CompanyRules = { companyRules }): Screen.() -> Unit = {
        val c = app.container
        c.settingsRepository.update { it.copy(previewTier = null) }
        val nightId = runBlocking { c.scheduleRepository.snapshot.first().shiftTypes.first { it.name == "야간" }.id }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = rules(nightId)), keepUndo = false) }
        settle()
        val vm = ExtraKindsViewModel(c)
        content { ExtraKindsScreen(vm, onBack = {}, onOpenPay = {}) }
        settle()
    }
    /** [SLP-14] 오늘의 흐름 through a first night and the day after, at fixed times. */
    internal fun flowCards(vararg cards: com.bamnat.core.sleep.TodayFlow.Card): Screen.() -> Unit = {
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
    internal val flowResolver by lazy {
        val d = ShiftType(11, "주간", "주", 0xFF2F80ED, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
        val n = ShiftType(12, "야간", "야", 0xFF6C5CE7, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
        val o = ShiftType(13, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true)
        com.bamnat.core.schedule.ScheduleResolver(listOf(d, n, o),
            WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(d.id, n.id, n.id, o.id, o.id)),
            listOf(com.bamnat.core.model.DayEntry(LocalDate.of(2026, 11, 12), alarmSkipped = true)))
    }
    internal fun flowAt(d: Int, h: Int, m: Int, log: List<com.bamnat.core.sleep.SleepEntry> = emptyList()) =
        com.bamnat.core.sleep.TodayFlow.of(java.time.LocalDateTime.of(2026, 11, d, h, m), flowResolver, true, log)
    // [PAYS-01] Answers left 모르겠어요 show as a range, and the sheet says what each one moves.
    internal val unknownAnswers = setOf(
        com.bamnat.core.pay.Unknowns.FIVE_OR_MORE, com.bamnat.core.pay.Unknowns.REST_DAY_WORK, com.bamnat.core.pay.Unknowns.MONTHLY_HOURS,
    )
    // [PAYS-02] 명세서로 맞춰 보기: the form, then what the closest settings change.
    internal fun payslipMatch(): Screen.() -> Unit = payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) com.bamnat.app.ui.pay.PayslipMatchDialog(vm, profile, snap, Today.month(), onDismiss = {})
    }
    // [SHR-11] A shop shares its 매출 and a freelancer their 일정 and 일감 수입, with the figures shown before sending.
    internal fun shareSheet(style: WorkStyle): Screen.() -> Unit = style(style) {
        val snap = runBlocking { app.container.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, Today.month(), {}, {}) }
    }
    // [RECV-05] 받을 돈 for each kind of work: written debts, unpaid jobs, the job and receivable sheets.
    internal fun owed(s: WorkStyle, wide: Boolean = false, then: Screen.() -> Unit = {}): Screen.() -> Unit = style(s) {
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
    internal fun dutyTyping(): Screen.() -> Unit = style(WorkStyle.SHIFT) {
        val c = app.container
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val t = snap.shiftTypes.associateBy { it.name }
        val month = java.time.YearMonth.of(2026, 11)
        val codes = listOf("주간", "주간", "오후", "주간", "야간", "야간", "주간", "휴무", "휴무", "오후", "오후", "야간")
        val initial = codes.mapIndexed { i, n -> month.atDay(i + 1) to ShiftOverride.Shift(t.getValue(n).id) as ShiftOverride }.toMap()
        content { com.bamnat.app.ui.calendar.DutyTypingSheet(snap, month, onDismiss = {}, onSave = {}, initial = initial) }
        settle()
    }
    /** [INV-30][INV-31][INV-32] Trades found in 체결 notifications, waiting for 확인, and the coin/stock badges. */
    internal fun foundTrades(then: Screen.() -> Unit = {}): Screen.() -> Unit = style(WorkStyle.INVEST) {
        val c = app.container
        val repo = com.bamnat.app.data.InvestRepository.of(c.database)
        val at = Today.millis()
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
    // ---- M4 [SLP-01][MKT-07][MKT-08][PAY-12][FEAT-08][COMM-00] ----
    /** A night straight into a day shift two days ago, so the rest check has something to say. */
    internal fun nightIntoDay(): Screen.() -> Unit = {
        val repo = app.container.scheduleRepository
        runBlocking {
            val types = repo.shiftTypes.first().associateBy { it.name }
            repo.setOverride(today.minusDays(4), ShiftOverride.Shift(types.getValue("야간").id))
            repo.setOverride(today.minusDays(3), ShiftOverride.Shift(types.getValue("주간").id))
        }
        settle()
    }
    // ---- M7 [PAYX-14][PAYX-15][UX-01] ----
    internal val closeOn20: (com.bamnat.core.pay.PayProfile) -> com.bamnat.core.pay.PayProfile =
        { it.copy(period = com.bamnat.core.pay.PayPeriod(endDay = 20, payDay = 25), rules = it.rules.copy(holidayByShiftStart = true)) }
    /** 4조 2교대 from its template, the user's own 주주휴휴야야휴휴, today the second night. */
    internal fun Screen.setupToToday() {
        tapText("순서가 돌아가요"); tapText("4조 2교대 12시간"); tapText("네, 시간이 맞아요")
        tapText("4조 2교대 8일 (주휴야휴)"); tapText("다음", exact = true)
        tapText("야간", exact = true); tapText("둘째 날")
    }

    internal fun fresh(s: WorkStyle?, then: Screen.() -> Unit = {}): Screen.() -> Unit {
        runBlocking { app.container.scheduleRepository.clearPattern() }
        app.container.settingsRepository.update { it.copy(workStyle = s) }
        return then
    }

    internal fun style(s: WorkStyle, then: Screen.() -> Unit = {}): Screen.() -> Unit {
        app.container.settingsRepository.update { it.copy(workStyle = s) }
        return then
    }

    // ---- M12 [COMM-01][COMM-02][COMM-03][EXTRA-06] 회사 찾기와 나누기, with a made-up company server ----
    internal val company = FakeCompanyApi()

    /** A coworker's setup as the server hands it out: this phone's own schedule and a company's pay, no amounts. */
    internal fun coworkerFile(): String = runBlocking {
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

    internal fun companyOn(): com.bamnat.app.AppContainer = app.container.also {
        it.companyApi = company
        company.file = coworkerFile()
    }

    /** Lets the view model's own waits pass, e.g. the pause in typing before a search. */
    internal fun waitFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        settle()
    }
    internal fun votePay(): Screen.() -> Unit = {
        val c = companyOn()
        c.companyStore.setApplied(com.bamnat.app.company.AppliedSetup("c1", "(주)예시정밀", "생산팀", "s1", 2, true, Today.instant()))
        val nudges = com.bamnat.app.ui.company.CompanyNudgeViewModel(c)
        payScreen(Tier.PRO, withProfile = true) { vm ->
            PayScreen(vm, onBack = {}, top = { com.bamnat.app.ui.company.CompanyNudgeCards(nudges, onShare = {}, onFix = {}) })
        }.invoke(this)
    }
    // [SHR-01][SHR-02][SHR-04] A link with chosen parts, and calendars received from others beside the user's own.
    internal val shares = FakeShareApi()
    internal val minjiId = "Mj7dEfGh5JkLmNoPqRsTuv"
    internal val junhoId = "Jh2kWxYz8AbCdEfGhJkLmN"

    /** 민지's link as the server keeps it: 4조2교대 with a memo, 잔업, 연차 and her pay, from this month. */
    internal fun minjiShare(): com.bamnat.app.share.ReceivedShare {
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
        return com.bamnat.app.share.ReceivedShare(body, Today.instant().plus(java.time.Duration.ofDays(30)), Today.instant())
    }

    /** 준호's link from an older app: weekdays 09–18, nothing ticked. */
    internal fun junhoShare(): com.bamnat.app.share.ReceivedShare {
        val weekday = ShiftType(1, "평일", "평", 0xFF2E7D32, LocalTime.of(9, 0), LocalTime.of(18, 0))
        val off = ShiftType(2, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
        val from = today.withDayOfMonth(1)
        val monday = from.minusDays((from.dayOfWeek.value - 1).toLong())
        val snap = com.bamnat.app.data.ScheduleSnapshot(listOf(weekday, off), WorkPattern.Rotation(monday, listOf(1L, 1L, 1L, 1L, 1L, 2L, 2L)), emptyList())
        val body = com.bamnat.app.share.ShareLinkPayload.build(snap, from, "준호", 30)
        return com.bamnat.app.share.ReceivedShare(body, Today.instant().plus(java.time.Duration.ofDays(7)), Today.instant())
    }

    /** The share server answers at once; 준호 then 민지 were received, unless [minji] is false. */
    internal fun receivedOn(minji: Boolean = true): com.bamnat.app.AppContainer = app.container.also { c ->
        c.shareApi = shares
        shares.links[minjiId] = minjiShare()
        shares.links[junhoId] = junhoShare()
        c.received.put(junhoId, shares.links.getValue(junhoId))
        if (minji) c.received.put(minjiId, shares.links.getValue(minjiId))
    }

    /** A day both are off on in this month's grid, today or later when there is one. */
    internal fun restTogether(): LocalDate {
        val mine = runBlocking { app.container.scheduleRepository.snapshot.first() }
        val theirs = com.bamnat.app.share.SharedSchedule.parse(minjiShare().schedule)
        val days = (1..today.lengthOfMonth()).map { today.withDayOfMonth(it) }.filter { d ->
            val m = mine.resolver.resolve(d)
            theirs.isRest(d) == true && (m.isOff || m.leave?.isFullDay == true) && m.extras.isEmpty()
        }
        return days.firstOrNull { !it.isBefore(today) } ?: days.first()
    }

    // ---- [MCAL-01][MCAL-02][MCAL-03][MCAL-04] The user's own calendars ----
    /** An added part-time calendar, 월·수·금 오전 알바, set up and listed; the main one stays on screen unless [show]. */
    internal fun addedCalendar(name: String = "편의점 알바", show: Boolean = false): String = runBlocking {
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

    internal companion object {
        const val RULES_PERIOD_SCROLL = 420

        /** [SETUP-15] The first card on "무슨 일 하세요?". */
        const val SHIFT_CARD = "교대 근무 · 순서가 돌아가요"

        /** [QA-05] [settle]: rounds of 10 ms with nothing to do before a screen counts as still, and the cap. */
        const val QUIET_ROUNDS = 5
        const val SETTLE_MIN_ROUNDS = 5
        const val SETTLE_MAX_NANOS = 1_000_000_000L
        const val REACH_WAIT_NANOS = 5_000_000_000L
        /** [QA-08] Once more, for a screen that has drawn nothing at all yet. */
        const val EMPTY_WAIT_NANOS = 15_000_000_000L

        // Galaxy Z Fold6: cover screen 968x2376 px and main screen 1856x2160 px at 420 dpi.
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
        // [SCALE-01] A common phone (Galaxy S24 at 1080x2340), a small one (720x1280 class) and a phone on its side.
        const val PHONE = "w360dp-h780dp-port-450dpi"
        const val SMALL_PHONE = "w320dp-h568dp-port-xhdpi"
        const val PHONE_LANDSCAPE = "w780dp-h360dp-land-450dpi"

        /** [QA-08] The fixed day and time of every shot, and the zone the phone is in. */
        val SEOUL: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")
        val SHOT_TIME: java.time.ZonedDateTime = java.time.ZonedDateTime.of(2026, 10, 14, 10, 30, 0, 0, SEOUL)
        private val systemZone: java.util.TimeZone = java.util.TimeZone.getDefault()

        /**
         * Before the class's first app object reads the date (Robolectric makes the app before the test object), and
         * kept for the whole class; the other test classes of the same worker get the system clock back.
         */
        @JvmStatic @org.junit.BeforeClass
        fun stopTheClock() {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(SEOUL))
            Today.fix(SHOT_TIME)
        }

        @JvmStatic @org.junit.AfterClass
        fun startTheClock() {
            Today.reset()
            java.util.TimeZone.setDefault(systemZone)
        }
    }

    // ---- 달력 (1.15) ----
    // [CAL-05..CAL-14] The calendar for each kind of work: its month summary, list, legend, quick input marks
    // and ranges, holidays, added and received calendars, large text, dark mode and the home screen widgets.

    /** [CAL-05] Field or shop work as such a user keeps it: no shifts, the seeded jobs and sales, and what [more] adds. */
    internal fun plain(s: WorkStyle, more: suspend (com.bamnat.app.AppContainer) -> Unit = {}, then: Screen.() -> Unit = {}): Screen.() -> Unit {
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
    internal val moreJobs: suspend (com.bamnat.app.AppContainer) -> Unit = { c ->
        c.moneyRepository.save(Job(date = today.plusDays(6), endDate = today.plusDays(8), title = "베란다 방수", client = "이OO 고객",
            amount = 900_000, deposit = 200_000, depositPaid = true))
        c.moneyRepository.save(Job(date = today.minusDays(9), title = "싱크대 교체", amount = 350_000, balancePaid = true))
    }

    /** Every open day of the month so far, closed on Mondays. */
    internal val moreSales: suspend (com.bamnat.app.AppContainer) -> Unit = { c ->
        for (d in 1 until today.dayOfMonth - 1) {
            val date = today.withDayOfMonth(d)
            if (date.dayOfWeek == java.time.DayOfWeek.MONDAY) continue
            c.moneyRepository.save(DailySales(date, sales = 520_000L + d * 137_000L % 760_000L, costs = 150_000L + d * 31_000L % 90_000L))
        }
    }

    /** [CAL-06] Pay set up on Pro, so the summary over a shift calendar shows the month's pay. */
    internal fun withPay(then: Screen.() -> Unit = {}): Screen.() -> Unit {
        val c = app.container
        c.settingsRepository.update { it.copy(previewTier = null) }
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        return then
    }

    /** Hints read once when the calendar opens: set before the screen starts. */
    internal fun hints(seen: Boolean, vararg keys: String) {
        val store = com.bamnat.app.ui.calendar.HintStore(app)
        if (seen) keys.forEach(store::markDone)
        else app.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE).edit().apply { keys.forEach { remove(it) } }.commit()
    }

    /** [CAL-09] 연차 painted across a week: only the working days take it, and the bar says so with undo. */
    /** Monday of the month's second row (weeks start on Sunday): above the quick input bar on every screen. */
    internal fun secondRowMonday(): LocalDate {
        val first = today.withDayOfMonth(1)
        return first.plusDays((7 - first.dayOfWeek.value % 7).toLong() + 1)
    }
    // [CAL-12] A freelancer's link: jobs and a memo, no shifts, so no "휴" on every empty day.
    internal val jobsLinkId = "Fr9gHj3KlMnPqRsTuVwXyZ"
    internal fun jobsShare(): com.bamnat.app.share.ReceivedShare {
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
        return com.bamnat.app.share.ReceivedShare(body, Today.instant().plus(java.time.Duration.ofDays(30)), Today.instant())
    }
    internal fun jobsLinkOn() {
        val c = app.container
        c.shareApi = shares
        shares.links[jobsLinkId] = jobsShare()
        c.received.put(jobsLinkId, shares.links.getValue(jobsLinkId))
    }
    /** [CAL-11] The three widgets in the words of field and shop work. */
    internal fun widgetShots(prefix: String) = runBlocking {
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
    // ---- 앱 검토·공유·설정·문의 (1.15) ----
    // [UX-03] 공유 in sight on every screen size, [UX-04] settings found by words, [UX-05] help before a question,
    // [UX-06] quick wins on the sleep and memo screens.

    /** [UX-05] The question server for screenshots: two questions from this phone, one answered, and 모두의 문의. */
    internal class FakeInquiryApi(override val enabled: Boolean = true) : com.bamnat.app.company.InquiryApi {
        private val now = Today.instant()
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
    internal fun inquiriesOn(enabled: Boolean = true, asked: Boolean = false) = app.container.also { c ->
        c.inquiryApi = FakeInquiryApi(enabled)
        c.inquiryStore.asked = asked
    }

    /** [UX-05] Opens 도움말과 문의 the way another screen does: the link, then the navigation. */
    internal fun Screen.askFromScreen(start: com.bamnat.app.ui.settings.InquiryStart) {
        com.bamnat.app.ui.settings.InquiryLinks.ask(start)
        settle()
    }

    // ---- 출시 전 개선 (1.18) [CREW-01][CUST-01][CAL-20][INQ-02][GUIDE-08], on a common phone and a small one ----
    internal fun Screen.teams() {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.TEAMS)
        settle()
    }
    /** Through the help search's link, which opens it over the calendar. */
    internal fun Screen.customize() {
        com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRegistry.get("calendar.customize")!!)
        settle()
    }
    /** A 휴 after the seeded [coverDay], which already has its 대근. */
    internal val offDay: LocalDate get() = coverDay.plusDays(5)
    internal fun Screen.coverAsk() {
        tapText("빠른 입력", exact = true)
        tapText("야간 칠하기", exact = true, scrolls = 0)
        // On a small phone the open panel can leave the month scrolled past the day: back up a little.
        repeat(3) {
            if (runCatching { selectDay(offDay) }.isSuccess) return
            scrollDown(120)
        }
        selectDay(offDay)
    }
    internal fun Screen.inquiryThread() {
        askFromScreen(com.bamnat.app.ui.settings.InquiryStart(help = true))
        tapText("내 문의:", prefix = true)
        tapText("[알람] 야간 근무 전 알람이 가끔", prefix = true)
    }
    internal fun Screen.whatsNew() = content {
        com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases, {}, {}, {})
    }
    internal fun Screen.year() {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.YEAR)
        settle()
    }
    // ---- [MON-04..MON-10] 1.19.0: 프로, its lock, the trial's question, the thank-you, ad privacy ----
    internal val samplePlans = listOf(
        com.bamnat.app.billing.Plan("monthly", "4,990원", 4_990_000_000, "KRW", "P1M", trial = true, offerToken = "m"),
        com.bamnat.app.billing.Plan("yearly", "39,000원", 39_000_000_000, "KRW", "P1Y", trial = true, offerToken = "y"),
    )

    /** After the 정식 출시 switch, with Play's plans, as a phone without 프로 sees it. */
    internal fun launched(config: com.bamnat.app.tier.LaunchConfig = com.bamnat.app.tier.LaunchConfig(billingLive = true),
                         then: Screen.() -> Unit = {}): Screen.() -> Unit = {
        val c = app.container
        c.billing = com.bamnat.app.billing.NoBilling(samplePlans)
        c.remoteConfig.save(config.copy(fetchedAt = Today.instant()))
        c.featureGate.refresh()
        settle()
        then()
    }
    internal fun pro(): Screen.() -> Unit = { content { com.bamnat.app.ui.pro.ProScreen(onBack = {}) } }

    /** The link 민지 made this month, with the calendar feed on. */
    internal fun Screen.shareWithFeed(then: Screen.() -> Unit = {}) {
        val c = app.container
        c.shareApi = shares
        c.shareLinks.save(com.bamnat.app.share.ShareLinkState("Ab3dEfGh5JkLmNoPqRsTuv", "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv",
            "t", Today.instant().plus(java.time.Duration.ofDays(150)), "민지", 180, Today.month()))
        c.shareLinks.feed = true
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        content { com.bamnat.app.ui.calendar.ShareDialog(snap, Today.month(), {}, {}, client = shares) }
        settle()
        then()
    }
    internal fun Screen.mission(kind: com.bamnat.core.alarm.AlarmMission, rangFor: java.time.Duration) {
        val sensors = app.getSystemService(android.hardware.SensorManager::class.java)
        shadowOf(sensors).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER))
        val since = Today.millis() - rangFor.toMillis()
        content { com.bamnat.app.alarm.MissionScreen(kind, since, 5, onSnooze = {}, onDone = {}) }
    }
    // ==== 1.22 묶음 B [SRV-04][SRV-05][SRV-06][STAT-01][COMM-08][COMM-09][COMM-10]: 410–429 ====
    /** The developer's notice of the day, and [off] switches paused. */
    internal fun opsOn(vararg off: com.bamnat.core.ops.ServerSwitch) {
        val now = Today.instant()
        app.container.remoteConfig.save(com.bamnat.app.tier.LaunchConfig(
            notices = listOf(com.bamnat.core.ops.AppNotice("nTonight01", "오늘 밤 2시 30분 동안 점검해요",
                "광장과 근무표 링크 만들기가 새벽 2시부터 2시 30분까지 쉬어요. 달력·알람·급여는 그대로 돼요.", important = true,
                from = now.minus(java.time.Duration.ofHours(2)).toEpochMilli(), until = now.plus(java.time.Duration.ofDays(1)).toEpochMilli(),
                minApp = null, maxApp = null)),
            off = off.toSet(), fetchedAt = now,
        ))
    }
    internal fun writer(n: Int, nick: String?) = com.bamnat.app.company.TalkPost("p$n", n, null, true,
        "야간 뒤 휴무가 하루뿐이라 힘들어요", Today.instant().minusSeconds(3600), mine = false, nick = nick)

    // ---- [208] Store pictures (1.18.0): a common phone, no one-time hints, the phone's alarm permissions given ----
    internal fun clean(then: Screen.() -> Unit = {}): Screen.() -> Unit {
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT, Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric cannot grant the full-screen permission, so the phone is told it predates Android 14,
        // where it needs none; Robolectric puts the SDK level back before the next test.
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 33)
        com.bamnat.app.ui.calendar.HintStore(app).markDone(com.bamnat.app.ui.calendar.HintStore.SHARE)
        return then
    }
    // ---- 급여 커스텀·설정 쉽게 (1.15) [PAYS-07..11][SETUP-03..13] ----
    /** [PAYS-07] 우리 회사 항목 만들기 over the pay screen, opened at [kind] or at the list of kinds. */
    internal fun itemBuilder(kind: com.bamnat.app.ui.pay.ItemKind? = null, gap: Long? = null): Screen.() -> Unit =
        payScreen(Tier.PRO, withProfile = true) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) {
                com.bamnat.app.ui.pay.CustomItemDialog(vm, profile, snap, Today.month(), onDismiss = {}, onSave = {},
                    gap = gap, startKind = kind)
            }
        }

    internal fun Screen.deductForm() {
        itemBuilder(com.bamnat.app.ui.pay.ItemKind.DEDUCT).invoke(this)
        typeInto("이름 (선택)", "기숙사비")
        typeInto("한 달 금액", "50000")
        repeat(2) { settle() }
    }

    /** [PAYS-07][PAYS-09] A deduction made from the pay screen, then the notice and the invitation to share the way. */
    internal fun Screen.madeDeduction() {
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
    internal fun matchWithShare(): Screen.() -> Unit = payScreen(Tier.PRO, withProfile = true) { vm ->
        val profile = vm.profile.collectAsState().value
        val snap = vm.snapshot.collectAsState().value
        if (profile != null && snap != null) {
            com.bamnat.app.ui.pay.PayslipMatchDialog(vm, profile, snap, Today.month(), onDismiss = {}, onShareWay = {})
        }
    }

    /** This month as the app has it, for a payslip a little off from it. */
    internal fun appTotalNow(): Long = runBlocking {
        val c = app.container
        PayViewModel(c).calculateOff(c.payStore.profile.value!!, c.scheduleRepository.snapshot.first(), Today.month()).total
    }

    internal fun Screen.payslipGap() {
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

    internal fun Screen.payslipMatched() {
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
    internal fun wizardAt(key: String): Screen.() -> Unit = {
        val c = app.container
        val vm = PayViewModel(c)
        val snap = runBlocking { c.scheduleRepository.snapshot.first() }
        val month = Today.month()
        val sample = vm.sampleProfile()
        content {
            com.bamnat.app.ui.pay.PayWizard(sample, {}, {}, snapshot = snap, preview = { vm.preview(it, snap, month) }, startAt = key)
        }
        repeat(4) { settle() }
    }

    internal fun shiftEditor(): Screen.() -> Unit = {
        val day = runBlocking { app.container.scheduleRepository.shiftTypes.first().first { it.name == "주간" } }
        content { com.bamnat.app.ui.shifts.ShiftTypeEditorDialog(day, onDismiss = {}, onSave = {}, onDelete = {}) }
    }

    // ---- 가계부 (1.15) ----
    /**
     * [LEDG-06][LEDG-08][LEDG-09][LEDG-11] The seeded month made a whole ledger: a pay account, cash
     * and two cards behind the lines, budgets (배달 already over), pay-ins and bills that repeat, a
     * split payment, tags, a memo, installments, a transfer, and the months before for the year.
     */
    internal fun ledger() = runBlocking<Unit> {
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
    internal fun ledgerPage(page: String?, wide: Boolean = false, then: Screen.() -> Unit = {}): Screen.() -> Unit = {
        ledger()
        tab(1, wide)
        if (page != null) tapText(page, exact = true, scrolls = 0)
        then()
    }

    internal suspend fun e502Shoot(shots: List<Triple<String, androidx.glance.appwidget.GlanceAppWidget, Pair<Int, Int>>>) {
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

    // ==== 1.22 묶음 G ====
    // [ONBOARD-03][ONBOARD-04] 추천 기능 한 번에 켜기, in place of the consent pages of 1.13~1.21.
    internal fun recommended(
        items: Set<ConsentItem> = ConsentItem.entries.toSet(),
        /** [SETUP-15] The card tapped on "무슨 일 하세요?" first; null taps "백업이나 회사 파일로 시작할게요". */
        firstStyle: String? = SHIFT_CARD,
        then: Screen.() -> Unit = {},
    ): Screen.() -> Unit {
        val c = app.container
        c.consentStore.forget()
        c.consentStore.availableForTest = items
        return fresh(null) {
            tapText(firstStyle ?: "백업이나 회사 파일로 시작할게요", exact = true)
            then()
        }
    }

    // ==== 1.22 묶음 A ====
    // [PAYR-04][PAYR-05] 평일 근무 통계: 예상 실수령 from the 간이세액표, with 가족·자녀 under 4대보험.
    internal fun dayTakeHome(wide: Boolean = false): Screen.() -> Unit = {
        val c = app.container
        runBlocking { c.payStore.save(PayViewModel(c).sampleProfile().copy(taxFamily = 3, taxChildren = 1), keepUndo = false) }
        c.settingsRepository.update { it.copy(workStyle = WorkStyle.DAY) }
        settle()
        tab(3, wide = wide)
        reveal("8~20세 자녀", exact = true)
    }
    // ==== 1.22 묶음 C ====
    // [REST-01][REST-03] 근무표 점검 기준: one sheet, a preset away from a hospital's rules, and the switch to turn it off.
    internal fun restRules(): Screen.() -> Unit = style(WorkStyle.SHIFT) {
        content {
            com.bamnat.app.ui.calendar.RestRulesSheet(com.bamnat.core.schedule.RestRules(), com.bamnat.core.schedule.RotationSpeed.FAST, {}, {})
        }
        settle()
    }
    // ==== 1.22 묶음 D ====
    /**
     * [LEAVE-01..06][PAYX-18] Pay set up with a hire date 3년 4개월 ago, 이월 2일, the seeded 반차 and 연차, and a 특근
     * taken as 보상휴가: the 연차 screen as 설정 › 연차, the pay screen's card and the notice open it.
     */
    internal fun leaveSeed(adjust: (com.bamnat.core.pay.PayProfile) -> com.bamnat.core.pay.PayProfile = { it }) {
        val c = app.container
        twoOnTwoOff()
        c.settingsRepository.update { it.copy(previewTier = null) }
        runBlocking {
            c.payStore.save(adjust(PayViewModel(c).sampleProfile().copy(rules = companyRules.copy(annualLeaveDays = null),
                hireDate = today.minusYears(3).minusMonths(4))), keepUndo = false)
            val year = today.withDayOfYear(1)
            c.leaveRepository.add(com.bamnat.core.leave.LedgerEntry(kind = com.bamnat.core.leave.EntryKind.CARRY, date = year, days = 2.0,
                expires = year.plusYears(1).minusDays(1), note = "작년에서 넘어온 날"))
            val repo = c.scheduleRepository
            val pattern = repo.snapshot.first()
            val compDay = (11..25).map { today.minusDays(it.toLong()) }.first { pattern.resolver.resolve(it).isOff }
            repo.addExtra(compDay, ExtraEntry(ExtraKind.SPECIAL_ID, LocalTime.of(9, 0), LocalTime.of(14, 0), compLeave = true))
            repo.setLeave((15..40).map { today.minusDays(it.toLong()) }.first { !pattern.resolver.resolve(it).isOff }, Leave())
        }
        settle()
    }

    /** 4조 2교대 (주주휴휴야야휴휴): two working days between two days off, so 연차 2일 makes 6 days off [LEAVE-04]. */
    internal fun twoOnTwoOff() = runBlocking {
        val repo = app.container.scheduleRepository
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = types.getValue("주간").id
        val night = types.getValue("야간").id
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, off, off, night, night, off, off)))
    }

    internal fun leaveScreen(then: Screen.() -> Unit = {}): Screen.() -> Unit = {
        leaveSeed()
        val vm = com.bamnat.app.ui.leave.LeaveViewModel(app.container, com.bamnat.app.data.PersonalCalendar.MAIN)
        content { com.bamnat.app.ui.leave.LeaveScreen(vm, onBack = {}, onEditRules = {}) }
        settle()
        then()
    }

    /** [PAYX-16][PAYX-17] Ready examples, one opened; a 2주 탄력제 with the day it starts. */
    internal fun leaveRulesEditor(adjust: (com.bamnat.core.pay.PayProfile) -> com.bamnat.core.pay.PayProfile = { it }) =
        payScreen(Tier.PRO, withProfile = true, adjust = adjust) { vm ->
            val profile = vm.profile.collectAsState().value
            val snap = vm.snapshot.collectAsState().value
            if (profile != null && snap != null) CompanyRulesEditor(vm, profile, snap, Today.month(), onClose = {}, onOpenKinds = {})
        }
}

/** [SHR-02] The share server for screenshots: links people sent, answered at once. */
internal class FakeShareApi : com.bamnat.app.share.ShareApi {
    override val enabled = true
    val links = mutableMapOf<String, com.bamnat.app.share.ReceivedShare>()
    override suspend fun create(payload: org.json.JSONObject) = com.bamnat.app.share.ShareLinkState("Ab3dEfGh5JkLmNoPqRsTuv",
        "https://bamnat-share.example.workers.dev/s/Ab3dEfGh5JkLmNoPqRsTuv", "t", Today.instant().plus(java.time.Duration.ofDays(30)), payload.optString("title"))
    override suspend fun update(state: com.bamnat.app.share.ShareLinkState, payload: org.json.JSONObject) = state
    override suspend fun delete(state: com.bamnat.app.share.ShareLinkState) = Unit
    override suspend fun read(id: String) = links[id] ?: throw com.bamnat.app.share.ShareLinkException("not_found")
}

/** [COMM-04] The company rules server for screenshots: made-up companies, answers at once. */
internal class FakeCompanyApi : com.bamnat.app.company.CompanyApi {
    override val enabled = true
    var file = "{}"
    /** [COMM-14] Two reports for (주)예시정밀 생산팀 side by side, on the board and the company screen. */
    var twins = false
    private val now = Today.instant()

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
        ) + if (!twins) emptyList() else listOf(
            com.bamnat.app.company.SharedSetup("s3", false, 3, 2, 0, 3, "생산팀", null, file,
                listOf("근무" to "주간 08:00~20:00 휴게 60분 · 야간 20:00~08:00 휴게 60분 · 휴무(쉬는 날)", "순서 4일" to "주간 야간 휴무 휴무") + pay, trust = 67, agree = 67),
            com.bamnat.app.company.SharedSetup("s4", false, 2, 2, 0, 3, "생산팀", null, file,
                listOf(main[0], "순서 4일" to "주간 야간 휴무 휴무") + pay.take(3), trust = 67, agree = 100),
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
        ) + if (!twins) emptyList() else listOf(
            com.bamnat.app.company.BoardItem("c1", "(주)예시정밀", "s3", "생산팀", false, 3, 2, 0, 3, 67, 67, 2, null,
                now.minus(java.time.Duration.ofHours(50)), "주간 08:00~20:00 · 야간 20:00~08:00 · 휴무(쉬는 날)", "순서 4일", true),
            com.bamnat.app.company.BoardItem("c1", "(주)예시정밀", "s4", "생산팀", false, 2, 2, 0, 3, 67, 100, 0, null,
                now.minus(java.time.Duration.ofHours(8)), "주간 08:00~20:00 · 야간 20:00~08:00 · 휴무(쉬는 날)", "순서 4일", true),
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
