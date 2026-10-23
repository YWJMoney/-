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
}
