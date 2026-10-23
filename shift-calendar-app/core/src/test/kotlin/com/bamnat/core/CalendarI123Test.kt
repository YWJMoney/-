package com.bamnat.core

import com.bamnat.core.alarm.AlarmCodec
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.schedule.ExtraStamp
import com.bamnat.core.work.Heat
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
}
