package com.bamnat.core

import com.bamnat.core.alarm.AlarmCodec
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.schedule.ExtraStamp
import com.bamnat.core.schedule.PatternPreset
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.schedule.QuickOrder
import com.bamnat.core.schedule.ShiftEvents
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.MonthOverview
import com.bamnat.core.work.WorkStyle
import java.time.YearMonth
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.schedule.ShiftTypeSet
import com.bamnat.core.schedule.ShiftTypeSets
import com.bamnat.core.layout.Contrast
import com.bamnat.core.work.Heat
import com.bamnat.core.work.WatchDay
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import java.time.LocalDateTime
import com.bamnat.core.work.ShiftHealth
import org.junit.Assert.assertNull
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** 1.23 묶음 I: calendar, day sheet, patterns, widgets and watch (순번 295~300). */
class CalendarI123Test {
    private val monday = LocalDate.of(2026, 9, 28)
    private val wed = LocalDate.of(2026, 10, 7)
    private val weekdays = listOf(1L, 1L, 1L, 1L, 1L, null, null)

    private fun resolver(vararg entries: DayEntry) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, weekdays), entries.toList())

    /**
     * [DAYW-08] I 리뷰 P1: the day sheet saved the shrunk end of a 반차 day as the day's own end, and the
     * resolver took the leave off it again. Saving the planned times keeps the leave coming off once.
     */
    @Test
    fun `editing a half day's times starts from the planned shift, so leave comes off once`() {
        val halfDay = DayEntry(wed, leave = Leave(LeaveKind.ANNUAL, 2))
        val d = resolver(halfDay).resolve(wed)
        assertEquals(LocalTime.of(13, 30), d.end)
        assertEquals(LocalTime.of(9, 0), d.plannedStart)
        assertEquals(LocalTime.of(18, 0), d.plannedEnd)
        assertEquals(60, d.plannedBreakMinutes)
        assertTrue(d.leaveShrunk)

        // The sheet moves the start to 08:00 and keeps the planned end and break.
        val edited = halfDay.copy(customStart = LocalTime.of(8, 0), customEnd = d.plannedEnd, customBreakMinutes = d.plannedBreakMinutes)
        val after = resolver(edited).resolve(wed)
        // 08~18 with a 1h break is 9h; half (270m) is leave and half the break (30m) stays,
        // so work ends 270 + 30 = 300 minutes after 08:00, at 13:00.
        assertEquals(LocalTime.of(8, 0), after.start)
        assertEquals(LocalTime.of(13, 0), after.end)
        assertEquals(270, after.scheduledMinutes)
        assertEquals(LocalTime.of(18, 0), after.plannedEnd)

        // What the old sheet did: the shrunk end saved back comes off a second time.
        val wrong = resolver(halfDay.copy(customStart = LocalTime.of(8, 0), customEnd = d.end, customBreakMinutes = d.breakMinutes)).resolve(wed)
        assertTrue(wrong.end!!.isBefore(after.end))
    }

    @Test
    fun `changing only the break of a half day halves the planned break once`() {
        val halfDay = DayEntry(wed, leave = Leave(LeaveKind.ANNUAL, 2))
        val d = resolver(halfDay).resolve(wed)
        val edited = resolver(halfDay.copy(customBreakMinutes = d.plannedBreakMinutes)).resolve(wed)
        assertEquals(d.end, edited.end)
        assertEquals(d.breakMinutes, edited.breakMinutes)
        assertEquals(d.scheduledMinutes, edited.scheduledMinutes)
    }

    @Test
    fun `a day without leave has its planned times equal to its times`() {
        val d = resolver().resolve(wed)
        assertEquals(d.start, d.plannedStart)
        assertEquals(d.end, d.plannedEnd)
        assertEquals(d.breakMinutes, d.plannedBreakMinutes)
        assertFalse(d.leaveShrunk)
    }

    /** [DAYW-09] I 리뷰 P13: an alarm on the evening before is kept through save and rings the day before. */
    @Test
    fun `a day alarm on the evening before survives the codec and rings the day before`() {
        val list = listOf(DayAlarm(LocalTime.of(23, 0), dayBefore = true), DayAlarm(LocalTime.of(20, 30)))
        val raw = AlarmCodec.encodeDayAlarms(list)!!
        assertEquals("-60:1;1230:1", raw)
        assertEquals(list, AlarmCodec.decodeDayAlarms(raw))
        val nights = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(3L)), listOf(DayEntry(wed, customAlarms = list)))
        val rings = AlarmPlanner.alarmsOn(nights, wed).map { it.triggerAt }
        assertEquals(listOf(wed.minusDays(1).atTime(23, 0), wed.atTime(20, 30)), rings)
    }

    /** [CAL-38] Extra-work stamps: 잔업 only after a shift, a 대근 only on a day off, never twice. */
    @Test
    fun `extra stamps go only where their kind fits`() {
        val kinds = ExtraKinds()
        val overtime = kinds.of(ExtraKind.OVERTIME_ID)
        val cover = kinds.of(ExtraKind.COVER_ID)
        val sat = LocalDate.of(2026, 10, 10)
        val r = resolver()
        val ot = ExtraEntry(overtime.id, minutes = 120)
        assertNull(ExtraStamp.skip(overtime, ot, r.resolve(wed), 4))
        assertEquals(ExtraStamp.Skip.NO_SHIFT, ExtraStamp.skip(overtime, ot, r.resolve(sat), 4))
        val c = ExtraEntry(cover.id, LocalTime.of(22, 0), LocalTime.of(7, 0), 60, coveredShiftId = 3)
        assertEquals(ExtraStamp.Skip.WORK_DAY, ExtraStamp.skip(cover, c, r.resolve(wed), 4))
        assertNull(ExtraStamp.skip(cover, c, r.resolve(sat), 4))
        val again = resolver(DayEntry(sat, extras = listOf(c)))
        assertEquals(ExtraStamp.Skip.SAME, ExtraStamp.skip(cover, c, again.resolve(sat), 4))
        assertEquals("근무 없는 2일, 이미 있는 1일", ExtraStamp.skippedText(mapOf(ExtraStamp.Skip.NO_SHIFT to 2, ExtraStamp.Skip.SAME to 1)))
    }

    /** [CAL-44][CAL-39] The year map: nights, the third night in a row, and a start less than 11 hours after the last. */
    @Test
    fun `heat marks nights, long runs and short rests`() {
        // 야 야 야 then 주 the next morning: 07:00 to 09:00 is a 2 hour rest.
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(3L, 3L, 3L, 1L, null, null, null)), emptyList())
        val heat = ShiftHealth.heat(monday, monday.plusDays(6), r::resolve)
        assertEquals(Heat.NIGHT, heat[monday])
        assertEquals(Heat.NIGHT_RUN, heat[monday.plusDays(2)])
        assertEquals(Heat.SHORT_REST, heat[monday.plusDays(3)])
        assertEquals(Heat.REST, heat[monday.plusDays(5)])
        assertTrue(monday.plusDays(3) in ShiftHealth.shortRestDays(2026, r::resolve))
    }

    /** [WEAR-02] One contrast rule: dark ink on light shifts, white on dark ones, and lifted colours read on black. */
    @Test
    fun `the shared contrast rule picks readable ink and lifts dark colours on black`() {
        assertEquals(Contrast.DARK_INK, Contrast.inkOn(0xFFF2994AL)) // 오후
        assertEquals(Contrast.WHITE, Contrast.inkOn(0xFF5E35B1L)) // 야간
        for (c in listOf(0xFF5E35B1L, 0xFF1E88E5L, 0xFF0E9F6EL, 0xFFFFEB3BL, 0xFF000080L)) {
            assertTrue(Contrast.ratio(Contrast.onBlack(c), Contrast.BLACK) >= 4.5)
            val ink = Contrast.inkOn(c)
            assertTrue(Contrast.ratio(c, ink) >= Contrast.ratio(c, if (ink == Contrast.WHITE) Contrast.DARK_INK else Contrast.WHITE))
        }
        // A colour that already reads is kept as it is.
        assertEquals(0xFFFFEB3BL, Contrast.onBlack(0xFFFFEB3BL))
    }

    /** [WEAR-01][WEAR-03] The new watch fields travel and an older copy still reads with safe defaults. */
    @Test
    fun `watch payload carries week start, week hours, clock and today's alarm`() {
        val day = WatchDay(wed, "야간", "야", 0xFF5E35B1L, LocalTime.of(22, 0), LocalTime.of(7, 0), icon = "moon")
        val p = WatchPayload(listOf(day), LocalDateTime.of(2026, 10, 7, 9, 0), weekStart = 7, weekMinutes = 38 * 60 + 30,
            weekLimitMinutes = 52 * 60, clock = WatchPayload.Clock.OUT, alarmToday = true)
        val back = WatchDays.decode(WatchDays.encode(p))!!
        assertEquals(p, back)
        assertEquals("이번 주 38시간 30분 / 52시간", WatchDays.weekText(back))
        // What a 1.22 phone sent: six header fields and seven per day.
        val old = "v1\t2026-10-07T09:00\t\t\t내 달력\t0\n2026-10-07\t야간\t야\t4284364209\t22:00\t07:00\t0"
        val read = WatchDays.decode(old)!!
        assertEquals(1, read.weekStart)
        assertEquals(-1, read.weekMinutes)
        assertNull(WatchDays.weekText(read))
        assertEquals(WatchPayload.Clock.NONE, read.clock)
        assertNull(read.days.single().icon)
    }

    @Test
    fun `the watch month starts on the phone's day and pages as far as the days reach`() {
        // October 2026 starts on a Thursday: 3 blanks from Monday, 4 from Sunday.
        assertEquals(3, WatchDays.monthGrid(2026, 10, 1).first().takeWhile { it == null }.size)
        assertEquals(4, WatchDays.monthGrid(2026, 10, 7).first().takeWhile { it == null }.size)
        assertEquals(listOf("일", "월", "화", "수", "목", "금", "토"), WatchDays.weekHead(7))
        val days = listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15)).map { WatchDay(it, "주간", "주", 0) }
        assertEquals(-1..2, WatchDays.monthRange(days, LocalDate.of(2026, 10, 23)))
    }

    /** [DAYW-10] Two pieces of extra work on a day off both reach the watch. */
    @Test
    fun `a day off with two extras says both on the watch`() {
        val sat = LocalDate.of(2026, 10, 10)
        val kinds = ExtraKinds()
        val r = resolver(DayEntry(sat, extras = listOf(
            ExtraEntry(ExtraKind.SPECIAL_ID, LocalTime.of(9, 0), LocalTime.of(13, 0)),
            ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(22, 0), LocalTime.of(7, 0), 60, coveredShiftId = 3),
        )))
        val w = WatchDays.day(r.resolve(sat))
        assertEquals("${kinds.name(ExtraKind.SPECIAL_ID)} 외 1건", w.name)
    }

    /** [DAYW-10][SCHED-05] A set's shift with a name the user has but other times is offered, not skipped. */
    @Test
    fun `a set's same-name shift with other times can be taken over`() {
        val presetDay = Fixtures.day.copy(id = 0, start = LocalTime.of(8, 0), end = LocalTime.of(17, 0))
        val set = ShiftTypeSet("공장", "", listOf(presetDay, Fixtures.evening.copy(id = 0), Fixtures.off.copy(id = 0)))
        val diff = ShiftTypeSets.differing(set, Fixtures.all)
        assertEquals(listOf(Fixtures.day to presetDay), diff)
        assertTrue(ShiftTypeSets.missing(set, Fixtures.all).isEmpty())
        val retimed = ShiftTypeSets.retimed(Fixtures.day, presetDay)
        assertEquals(LocalTime.of(8, 0), retimed.start)
        assertEquals(Fixtures.day.id, retimed.id)
        assertEquals(Fixtures.day.alarms, retimed.alarms)
    }

    /** [DAYW-10] The pattern screen shows the presets the user's shifts can fill first. */
    @Test
    fun `presets the user's shifts fill come first`() {
        val dn = PatternPreset("2교대", "", "DDNNOO")
        val x = PatternPreset("당번", "", "XBO")
        assertTrue(PatternPresets.fits(dn, Fixtures.all))
        assertFalse(PatternPresets.fits(x, Fixtures.all))
        val ordered = PatternPresets.ordered(Fixtures.all)
        val firstMiss = ordered.indexOfFirst { !PatternPresets.fits(it, Fixtures.all) }
        if (firstMiss >= 0) assertTrue(ordered.drop(firstMiss).none { PatternPresets.fits(it, Fixtures.all) })
    }

    /** [SCHED-04] A weekday worker: 공휴일 off with no alarm, and every other Saturday worked. */
    @Test
    fun `a weekly order can rest on public holidays and work every other Saturday`() {
        val oct3 = LocalDate.of(2026, 10, 3) // 개천절, a Saturday
        val p = WorkPattern.Weekly.fromSteps(monday, listOf(1L, 1L, 1L, 1L, 1L, 1L, null), holidaysOff = true, altSaturday = LocalDate.of(2026, 10, 10))
        val r = ScheduleResolver(Fixtures.all, p, emptyList())
        assertFalse(r.resolve(LocalDate.of(2026, 10, 9)).isWorkDay) // 한글날, a Friday
        assertTrue(r.resolve(LocalDate.of(2026, 10, 8)).isWorkDay)
        assertTrue(r.resolve(LocalDate.of(2026, 10, 10)).isWorkDay)
        assertFalse(r.resolve(LocalDate.of(2026, 10, 17)).isWorkDay)
        assertTrue(r.resolve(LocalDate.of(2026, 10, 24)).isWorkDay)
        assertFalse(r.resolve(oct3).isWorkDay)
        assertTrue(AlarmPlanner.alarmsOn(r, LocalDate.of(2026, 10, 9)).isEmpty())
        // Without the options the same days are worked as before.
        val plain = ScheduleResolver(Fixtures.all, p.copy(holidaysOff = false, altSaturday = null), emptyList())
        assertTrue(plain.resolve(LocalDate.of(2026, 10, 9)).isWorkDay)
        assertTrue(plain.resolve(LocalDate.of(2026, 10, 17)).isWorkDay)
    }

    /** [SCHED-02] Fixing the order in place says which past days with records it would move. */
    @Test
    fun `fixing the order in place lists the recorded past days it moves`() {
        val today = LocalDate.of(2026, 10, 20)
        val current = WorkPattern.Rotation(monday, listOf(1L, 1L, 3L, 3L, null, null))
        val draft = WorkPattern.Rotation(monday, listOf(1L, 3L, 3L, null, null, 1L))
        val leaveDay = monday.plusDays(1) // 주 → 야
        val sameDay = monday // 주 → 주
        val handSet = monday.plusDays(2)
        val entries = listOf(
            DayEntry(leaveDay, leave = Leave(LeaveKind.ANNUAL, 4)),
            DayEntry(sameDay, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60))),
            DayEntry(handSet, override = ShiftOverride.Off, leave = Leave(LeaveKind.ANNUAL, 4)),
            DayEntry(today.plusDays(1), leave = Leave(LeaveKind.ANNUAL, 4)),
        )
        assertEquals(listOf(leaveDay), PatternTimeline.recordedDaysMoved(current, draft, entries, today))
        assertTrue(PatternTimeline.recordedDaysMoved(current, current, entries, today).isEmpty())
    }

    /** [SCHED-03] Restarting the order on a day keeps the days before and offers the hand-set days after. */
    @Test
    fun `realigning restarts the order on the day and keeps the past`() {
        val rotation = WorkPattern.Rotation(monday.minusYears(1), listOf(1L, 1L, 3L, 3L, null, null), anchorDate = monday)
        val day = LocalDate.of(2026, 10, 14)
        val moved = PatternTimeline.realign(rotation, day, 2)
        assertEquals(3L, moved.shiftTypeIdOn(day))
        val timeline = PatternTimeline.changeFrom(listOf(rotation), moved, day)
        assertEquals(rotation.shiftTypeIdOn(day.minusDays(1)), PatternTimeline.on(timeline, day.minusDays(1))!!.shiftTypeIdOn(day.minusDays(1)))
        assertEquals(3L, PatternTimeline.on(timeline, day)!!.shiftTypeIdOn(day))
        val entries = listOf(DayEntry(day.minusDays(1), override = ShiftOverride.Off), DayEntry(day.plusDays(3), override = ShiftOverride.Off),
            DayEntry(day.plusDays(4), leave = Leave()))
        assertEquals(listOf(day.plusDays(3)), PatternTimeline.handChangedFrom(entries, day))
    }

    /**
     * [CAL-31] I 리뷰 P7·P8: 2028 is past the checked table, so its 설·추석 come from the lunar rules. The 2028
     * 설 is the case P8 warned of: the new moon falls minutes after midnight in Korea (UTC+9) and just before it in
     * China, so 음력 1월 1일 is 1월 27일 here (한국천문연구원 생활달력 2028, astro.kasi.re.kr/life/post/calendarData)
     * against 1월 26일 in Chinese tables. 추석 (음력 8월 15일) is 10월 3일 and overlaps 개천절, so one 대체공휴일
     * follows on 10월 5일, not two.
     */
    @Test
    fun `2028 설 and 추석 from the rules match the almanac and overlap once`() {
        assertTrue(KoreanHolidays.isComputed(2028))
        assertFalse(KoreanHolidays.isComputed(2027))
        assertEquals("설날", KoreanHolidays.nameOf(LocalDate.of(2028, 1, 27)))
        for (d in listOf(26, 28)) assertEquals("설날 연휴", KoreanHolidays.nameOf(LocalDate.of(2028, 1, d)))
        assertFalse(KoreanHolidays.isHoliday(LocalDate.of(2028, 1, 25)))
        for (d in listOf(2, 3, 4, 5)) assertTrue("10/$d", KoreanHolidays.isHoliday(LocalDate.of(2028, 10, d)))
        assertEquals("대체공휴일", KoreanHolidays.nameOf(LocalDate.of(2028, 10, 5)))
        assertFalse(KoreanHolidays.isHoliday(LocalDate.of(2028, 10, 6)))
    }

    /**
     * [CAL-31] I 리뷰 P7: the checked table has to reach next year. This fails on the first of January of a year
     * whose next year is missing, which is the reminder to add the coming year from the 월력요항 (release checklist).
     */
    @Test
    fun `the checked holiday table reaches next year`() {
        val next = LocalDate.now().year + 1
        assertTrue("공휴일 표를 ${next}년까지 늘려 주세요 (KoreanHolidays.years)", KoreanHolidays.years.last >= next)
    }

    /** [CAL-32] A Sunday night shift counted on Sunday, or split at midnight into the next week. */
    @Test
    fun `a night over the week's edge can be split at midnight`() {
        val sunday = LocalDate.of(2026, 10, 4)
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(sunday, listOf(3L), endDate = sunday), emptyList())
        val calc = WorkHoursCalculator(r)
        // 22:00~07:00 with an hour off is 8 hours; 7 of the 9 hours fall after midnight, so 6h13m goes to Monday.
        val lastWeek = calc.week(sunday)
        assertEquals(8 * 60, lastWeek.totalMinutes)
        val moved = Math.round(8 * 60 * 7 / 9.0).toInt()
        assertEquals(8 * 60 - moved, calc.weekSplitAtMidnight(sunday))
        assertEquals(moved, calc.weekSplitAtMidnight(sunday.plusDays(1)))
        assertEquals(0, calc.week(sunday.plusDays(1)).totalMinutes)
    }

    /** [CAL-35] The month against the last in a few words. */
    @Test
    fun `this month against last month`() {
        val m = YearMonth.of(2026, 10)
        val now = MonthOverview(m, CalendarFocus.SHIFTS, workDays = 22, workMinutes = 176 * 60)
        val last = MonthOverview(m.minusMonths(1), CalendarFocus.SHIFTS, workDays = 20, workMinutes = 184 * 60)
        assertEquals("지난달보다 +2일 · \u22128시간", now.versus(last))
        assertNull(now.versus(now.copy(month = m.minusMonths(1))))
        assertNull(now.versus(MonthOverview(m.minusMonths(1), CalendarFocus.SHIFTS)))
    }

    /** [CAL-34][CAL-40] A week at a glance: hours, nights and what is left before 52. */
    @Test
    fun `a week at a glance counts nights and the hours left before 52`() {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, listOf(3L, 3L, 3L, 1L, 1L, null, null)), emptyList())
        val g = WorkHoursCalculator(r).glance(monday)
        assertEquals(5 * 8 * 60, g.minutes)
        assertEquals(3, g.nights)
        assertEquals(12 * 60, g.leftToLegal)
        assertEquals("40시간 · 야간 3번 · 52시간까지 12시간", g.text)
    }

    /** [CAL-37] A new user's extra kinds in their kind of work's order; use in the last 60 days wins. */
    @Test
    fun `quick extras follow the kind of work until use says otherwise`() {
        val ids = listOf(ExtraKind.OVERTIME_ID, ExtraKind.SPECIAL_ID, ExtraKind.COVER_ID, 101L)
        assertEquals(listOf(ExtraKind.COVER_ID, ExtraKind.SPECIAL_ID, ExtraKind.OVERTIME_ID, 101L), QuickOrder.extras(ids, { it }, emptyList(), WorkStyle.SHIFT))
        assertEquals(listOf(ExtraKind.SPECIAL_ID, ExtraKind.OVERTIME_ID, ExtraKind.COVER_ID, 101L), QuickOrder.extras(ids, { it }, emptyList(), WorkStyle.DAY))
        assertEquals(101L, QuickOrder.extras(ids, { it }, listOf(101L, 101L, ExtraKind.COVER_ID), WorkStyle.SHIFT).first())
        assertEquals(ids, QuickOrder.extras(ids, { it }, emptyList(), null))
    }

    /** [WIDG-01][WIDG-05] The widgets redraw when a shift starts or ends, and count down to the next one. */
    @Test
    fun `widgets know the next start or end and count down to it`() {
        // 주간 09:00~18:00 on weekdays.
        val r = resolver()
        val days = r.resolveRange(wed.minusDays(1), wed.plusDays(7))
        val morning = wed.atTime(7, 30)
        assertEquals(wed.atTime(9, 0), ShiftEvents.nextChange(days, morning))
        assertEquals("주간까지 1시간 30분 · 09:00", ShiftEvents.countdown(days, morning))
        val working = wed.atTime(15, 50)
        assertEquals(wed.atTime(18, 0), ShiftEvents.nextChange(days, working))
        assertEquals("주간 중 · 18:00에 끝나요 (2시간 10분 남음)", ShiftEvents.countdown(days, working))
        assertEquals("주간까지 15시간 · 내일 09:00", ShiftEvents.countdown(days, wed.atTime(18, 0)))
        assertNull(ShiftEvents.countdown(resolver().resolveRange(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)), LocalDate.of(2026, 10, 10).atTime(9, 0)))
    }
}
