package com.bamnat.app.screenshots

import org.junit.Assert.assertTrue
import com.bamnat.app.share.MonthImage
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.R
import com.bamnat.core.memo.Memo
import com.bamnat.app.ui.Fmt
import android.view.View
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.glance.appwidget.compose
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import java.time.LocalDate
import com.bamnat.core.work.WorkStyle

/** [QA-05] Screenshots: 달력·하루·근무 유형·패턴·빠른 입력·위젯·스토어 사진. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CalendarScreenshotTest : ScreenshotBase() {
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
    @Test fun unfoldedCalendar() = shoot("5_unfolded_calendar", UNFOLDED)
    @Test fun unfoldedDayDetail() = shoot("6_unfolded_day", UNFOLDED) { scrollUp(500, across = 0.75f) }
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
    @Test fun foldedShiftIcons() = shoot("38_folded_shift_icons", FOLDED) {
        settingsPage("내 근무")
        tapText("근무 종류와 시간", exact = true)
        tapText("주간", exact = true)
        scrollUp(330)
    }
    @Test fun foldedDayPreview() = shoot("97_folded_day_preview", FOLDED) { selectDay(dayCell) }
    // The seeded 대근 day is up to 10 days ahead, so it can sit low in the month: scroll to it (date-proof).
    @Test fun foldedCoverDay() = shoot("98_folded_cover_day", FOLDED) { openDayBelow(coverDay) }
    @Test fun monthImage() = runBlocking {
        val snap = app.container.scheduleRepository.snapshot.first()
        val bmp = MonthImage.render(snap, java.time.YearMonth.from(today), java.time.DayOfWeek.SUNDAY)
        val out = File("build/screenshots/40_month_share_image.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.height > bmp.width)
    }

    @Test fun foldedDayRestWarning() = shoot("78_folded_day_rest_warning", FOLDED) {
        nightIntoDay().invoke(this); openDay(today.minusDays(3)); reveal("몸", exact = true)
    }
    @Test fun foldedDayHistory() = shoot("79_folded_day_change_history", FOLDED) {
        nightIntoDay().invoke(this); openDay(today.minusDays(3)); openGroup("기록"); reveal("기록", exact = true)
    }
    @Test fun foldedMonthRest() = shoot("80_folded_month_rest_check", FOLDED) { nightIntoDay().invoke(this); scrollUp(700) }
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
    @Test fun unfoldedCalendarHugeText() = shoot("172_unfolded_calendar_huge_text", UNFOLDED, fontScale = 2f)
    @Test fun phoneLandscapeCalendar() = shoot("173_phone_landscape_calendar", PHONE_LANDSCAPE)
    @Test fun calShiftFolded() = shoot("290_folded_shift_calendar", FOLDED, withPay())
    @Test fun calShiftUnfolded() = shoot("291_unfolded_shift_calendar", UNFOLDED, withPay())
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
    /** [CAL-17] The first tap on a date says once that a long press changes it in place. */
    @Test fun calLongPressTip() {
        hints(seen = false, com.bamnat.app.ui.calendar.HintStore.LONG_PRESS)
        hints(seen = true, com.bamnat.app.ui.calendar.HintStore.SHARE)
        app.container.settingsRepository.update { it.copy(sleepTips = false) }
        shoot("295_folded_long_press_tip", FOLDED) { selectDay(secondRowMonday()) }
    }
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
    // [CAL-06] What every mark means, in the calendar's own kind of work.
    @Test fun calLegendShift() = shoot("305_folded_legend_shift", FOLDED) { tapText("달력 표시 안내", exact = true, scrolls = 0) }
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

    // [CAL-06][VIS-02] 200% text: the summary's figures and the cells still fit.
    @Test fun calShiftHugeText() = shoot("313_phone_shift_huge_text", PHONE, 2f, withPay())
    /** [CAL-06] Any date, picked on a full calendar from the month list. */
    @Test fun calDateJump() = shoot("315_folded_date_jump", FOLDED) {
        tapText(Fmt.month(java.time.YearMonth.now()), exact = true, scrolls = 0)
        tapText("날짜 고르기", exact = true, scrolls = 0)
    }
    /** [CAL-18] The day's chips past the screen's edge: the end fades and "+N ›" says how many more. */
    @Test fun calDayChipsMore() = shoot("319_folded_day_chips_more", FOLDED) { openDay(today) }

    @Test fun smallPhoneQuickInput() = shoot("368_small_phone_quick_input", SMALL_PHONE) { tapText("빠른 입력", exact = true) }

    @Test fun store1Calendar() = shoot("store_1_calendar", PHONE, clean())
    @Test fun store5Day() = shoot("store_5_day", PHONE, clean { openDay(dayCell) })
    @Test fun store6Weeks() = shoot("store_6_weeks", PHONE, clean {
        app.container.settingsRepository.update { it.copy(statsLayout = "weeks,three,-tiles,-pay,kinds,trend") }
        tab(3, wide = false)
    })
    @Test fun store7Styles() = shoot("store_7_styles", PHONE) {
        content { com.bamnat.app.ui.work.WorkStyleScreen(current = null, onPick = {}) }
    }
    @Test fun storeTablet() = shoot("store_tablet", UNFOLDED, clean())
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

    // ==== 1.22 묶음 E 후속 ====
    /** [DAILY-09] Half open (Flex): the month above the hinge, the day below; a Flip and a Fold on its side. */
    @Test fun e506FlipTabletopCalendar() = shoot("506_flip_tabletop_calendar", "w373dp-h839dp-port-420dpi") { halfOpen() }
    @Test fun e507UnfoldedTabletopCalendar() = shoot("507_unfolded_tabletop_calendar", "w823dp-h707dp-land-420dpi") { halfOpen() }

    // [REST-01][NURS-05][JOB-03] The calendar's check card: 기준 바꾸기, this month's nights and their pay.
    @Test fun foldedMonthRestCard() = shoot("432_folded_month_rest_card", FOLDED, style(WorkStyle.SHIFT) {
        app.container.settingsRepository.update { it.copy(restRules = com.bamnat.core.schedule.RestRules.Preset.STRICT.rules.encode()) }
        settle()
        reveal("기준 바꾸기", exact = true)
    })
    // [REST-02] The same sign over the month folded to one line, and opened day by day with its source.
    @Test fun foldedMonthRestCardOpen() = shoot("438_folded_month_rest_card_open", FOLDED, style(WorkStyle.SHIFT) {
        app.container.settingsRepository.update { it.copy(restRules = com.bamnat.core.schedule.RestRules.Preset.STRICT.rules.encode()) }
        settle()
        reveal("기준 바꾸기", exact = true)
        tapText("날짜별로 보기")
    })
    /** [LEAVE-06] 오전 반차 (1.22 전체 리뷰): the part off comes from the start, so 남은 근무 is the afternoon. */
    @Test fun foldedDayMorningHalf() = shoot("509_folded_day_morning_half", FOLDED) {
        val repo = app.container.scheduleRepository
        val day = runBlocking {
            val r = repo.snapshot.first().resolver
            // An earlier day, so it sits in the month's upper rows whatever the date.
            (1..12).map { today.minusDays(it.toLong()) }.first { r.resolve(it).shiftType?.name == "주간" && r.resolve(it).leave == null && !r.resolve(it).hasExtraWork && it.month == today.month }
        }
        runBlocking { repo.setLeave(day, Leave(LeaveKind.ANNUAL, 2, fromStart = true)) }
        app.container.settingsRepository.update { it.copy(dayGroupOrder = listOf("alarm", "memo", "leave")) }
        settle()
        openDay(day)
        openGroup("휴가")
        reveal("남은 근무")
    }
}
