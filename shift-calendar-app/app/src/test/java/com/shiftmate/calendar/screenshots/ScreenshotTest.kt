package com.shiftmate.calendar.screenshots

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
import com.shiftmate.calendar.MainActivity
import com.shiftmate.calendar.ShiftMateApp
import com.shiftmate.core.memo.ChecklistItem
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.model.AlarmSpec
import com.shiftmate.core.model.DayAlarm
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.WorkPattern
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
import java.time.LocalDate
import java.time.LocalTime

/** Renders the main screens with sample data at Galaxy Fold sizes into build/screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<ShiftMateApp>()
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
            alarmEnabled = true, alarms = listOf(AlarmSpec(90), AlarmSpec(60), AlarmSpec(30, enabled = false))))
        val night = repo.saveShiftType(types.getValue("야간").copy(start = LocalTime.of(20, 0), end = LocalTime.of(8, 0)))
        val evening = types.getValue("오후").id
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, night, night, off)))

        // A swapped shift, overtime, a day of holiday work and a skipped alarm this month.
        repo.setOverride(today.plusDays(3), ShiftOverride.Shift(evening))
        repo.setOvertime(today.minusDays(2), 120)
        val pattern = WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(day, day, night, night, off))
        val nextOff = (1..10).map { today.minusDays(it.toLong()) }.first { pattern.shiftTypeIdOn(it) == off }
        repo.setHolidayWork(nextOff, 4 * 60)
        repo.setAlarmSkipped(today.plusDays(6), true)
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

        /** Opens one of the four top-level tabs (0 = calendar, 2 = stats, 3 = settings). */
        fun tab(index: Int, wide: Boolean) = gesture { w, h, d ->
            val p = if (wide) 40 * d to (45 + index * 62) * d
            else w * (index + 0.5f) / 4 to h - 40 * d
            listOf(p, p)
        }

        /** Taps a point given in dp from the top-left, or from the right edge when [fromRight]. */
        fun tap(xDp: Float, yDp: Float, fromRight: Boolean = false) = gesture { w, _, d ->
            val p = (if (fromRight) w - xDp * d else xDp * d) to yDp * d
            listOf(p, p)
        }

        /** Drags the content up by [dp] (slowly, so it does not fling). */
        fun scrollUp(dp: Int, across: Float = 0.15f) = gesture { w, h, d ->
            val x = w * across
            val from = h * 0.8f
            (0..40).map { x to from - dp * d * it / 40 } + listOf(x to from - dp * d)
        }
    }

    @Test fun foldedCalendar() = shoot("1_folded_calendar", FOLDED)
    @Test fun foldedDayDetail() = shoot("2_folded_day", FOLDED) { scrollUp(900) }
    @Test fun foldedStats() = shoot("3_folded_stats", FOLDED) { tab(2, wide = false) }
    @Test fun foldedSettings() = shoot("4_folded_settings", FOLDED) { tab(3, wide = false) }
    @Test fun unfoldedCalendar() = shoot("5_unfolded_calendar", UNFOLDED)
    @Test fun unfoldedStats() = shoot("7_unfolded_stats", UNFOLDED) { tab(2, wide = true) }
    @Test fun unfoldedDayDetail() = shoot("6_unfolded_day", UNFOLDED) { scrollUp(500, across = 0.75f) }
    @Test fun unfoldedSettings() = shoot("8_unfolded_settings", UNFOLDED) { tab(3, wide = true) }

    @Test fun foldedCalendarMemos() = shoot("9_folded_memos_calendar", FOLDED) { tab(1, wide = false) }
    @Test fun foldedGeneralMemos() = shoot("10_folded_memos_general", FOLDED) {
        tab(1, wide = false)
        tap(276f, TAB_ROW_Y)
    }
    @Test fun foldedTrash() = shoot("11_folded_trash", FOLDED) {
        tab(1, wide = false)
        tap(28f, 32f, fromRight = true)
    }
    @Test fun unfoldedMemos() = shoot("12_unfolded_memos", UNFOLDED) { tab(1, wide = true) }
    @Test fun foldedShiftTypes() = shoot("13_folded_shift_types", FOLDED) {
        tab(3, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
    }
    @Test fun foldedPattern() = shoot("14_folded_pattern", FOLDED) {
        tab(3, wide = false)
        tap(180f, SETTINGS_PATTERN_Y)
    }
    @Test fun foldedDayAlarmEditor() = shoot("15_folded_day_alarm_editor", FOLDED) {
        scrollUp(900)
        tap(180f, DAY_ALARM_EDIT_Y)
    }

    @Test fun foldedShiftTypeEditor() = shoot("16_folded_shift_type_editor", FOLDED) {
        tab(3, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 185f)
    }

    @Test fun foldedShiftTypeAlarms() = shoot("17_folded_shift_type_alarms", FOLDED) {
        tab(3, wide = false)
        tap(180f, SETTINGS_SHIFT_TYPES_Y)
        tap(150f, 185f)
        scrollUp(600)
    }
    @Test fun foldedDayAlarmNotice() = shoot("18_folded_day_alarm_notice", FOLDED) {
        scrollUp(900)
        tap(180f, DAY_ALARM_EDIT_Y)
        tap(315f, 578f)
    }

    private companion object {
        // Touch points in dp, read off the folded screenshots.
        const val TAB_ROW_Y = 148f
        const val SETTINGS_SHIFT_TYPES_Y = 206f
        const val SETTINGS_PATTERN_Y = 139f
        const val DAY_ALARM_EDIT_Y = 335f

        // Galaxy Z Fold6: cover screen 968x2376 px and main screen 1856x2160 px at 420 dpi.
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
    }
}

