package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.DayWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [DAYW-01..DAYW-05] Day work: exceptions only, 지금 퇴근, take-home, 징검다리 연휴, 시간외 상한. */
class DayWorkTest {
    private val weekdays = WorkPattern.Weekly(LocalDate.of(2026, 1, 1),
        DayOfWeek.entries.associateWith { if (it.value <= 5) Fixtures.day.id else null })
    private fun resolver(entries: List<DayEntry> = emptyList()) = ScheduleResolver(Fixtures.all, weekdays, entries)
    private val mon = LocalDate.of(2026, 10, 19)

    @Test
    fun `only days that differ from the weekly pattern keep their badge`() {
        val r = resolver(listOf(
            DayEntry(mon.plusDays(1), leave = Leave()),
            DayEntry(mon.plusDays(2), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60))),
            DayEntry(mon.plusDays(3), override = ShiftOverride.Shift(Fixtures.evening.id)),
            DayEntry(mon.plusDays(4), customStart = LocalTime.of(9, 0), customEnd = LocalTime.of(15, 0)),
        ))
        assertTrue(DayWork.isRoutine(r.resolve(mon)))
        (1L..4L).forEach { assertFalse(DayWork.isRoutine(r.resolve(mon.plusDays(it)))) }
        // A weekend off is no working day at all.
        assertFalse(DayWork.isRoutine(r.resolve(mon.plusDays(5))))
    }

    @Test
    fun `leaving now writes overtime after the end and an earlier end before it, to 10 minutes`() {
        val day = resolver().resolve(mon)
        val late = DayWork.leaveNow(day, mon.atTime(19, 4)) as DayWork.LeaveNow.Overtime
        assertEquals(60, late.minutes)
        assertEquals(ExtraKind.OVERTIME_ID, late.entry.kindId)
        assertEquals(LocalTime.of(18, 0), late.entry.start)
        assertEquals(LocalTime.of(19, 0), late.entry.end)
        val early = DayWork.leaveNow(day, mon.atTime(17, 26)) as DayWork.LeaveNow.EarlyEnd
        assertEquals(LocalTime.of(9, 0), early.start)
        assertEquals(LocalTime.of(17, 30), early.end)
        assertEquals(DayWork.LeaveNow.OnTime, DayWork.leaveNow(day, mon.atTime(18, 3)))
        assertEquals(DayWork.LeaveNow.OnTime, DayWork.leaveNow(resolver().resolve(mon.plusDays(5)), mon.plusDays(5).atTime(20, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 19, 20, 0), DayWork.round(LocalDateTime.of(2026, 10, 19, 19, 55)))
    }

    @Test
    fun `take home with 4 insurances uses the 2026 employee rates`() {
        val r = TakeHome.estimate(3_000_000, TaxMode.INSURANCE_4)
        val d = r.deductions.toMap()
        assertEquals(142_500L, d["국민연금"])
        assertEquals(107_850L, d["건강보험"])
        assertEquals(14_170L, d["장기요양"])
        assertTrue((d[TakeHome.TAX_LABEL] ?: 0) > 0)
        assertEquals(3_000_000 - r.deductions.sumOf { it.second }, r.net)
        // More dependents mean less income tax.
        assertTrue(TakeHome.estimate(3_000_000, TaxMode.INSURANCE_4, dependents = 3).net > r.net)
    }

    @Test
    fun `take home with 3_3 percent and with nothing off`() {
        val r = TakeHome.estimate(1_000_000, TaxMode.WITHHOLDING_3_3)
        assertEquals(967_000L, r.net)
        assertEquals(1_000_000L, TakeHome.estimate(1_000_000, TaxMode.NONE).net)
        assertEquals(0L, TakeHome.estimate(0, TaxMode.INSURANCE_4).net)
    }

    @Test
    fun `bridges join a holiday to days off with one or two days of leave`() {
        val r = resolver()
        val found = DayWork.bridges(LocalDate.of(2026, 10, 20), isWorkDay = { r.resolve(it).isWorkDay })
        // 2026-12-25 (금) 성탄절: Thursday off makes Thursday to Sunday.
        val xmas = found.first { it.holidays.contains("성탄절") }
        assertEquals(listOf(LocalDate.of(2026, 12, 24)), xmas.leave)
        assertEquals(4, xmas.days)
        assertEquals("연차 1일로 4일 쉬어요", xmas.text)
        // 2027-05-05 (수) 어린이날: one day is not enough, two make five.
        val may = found.first { it.holidays.contains("어린이날") }
        assertEquals(listOf(LocalDate.of(2027, 5, 3), LocalDate.of(2027, 5, 4)), may.leave)
        assertEquals(5, may.days)
        assertTrue(found.all { it.leave.size in 1..2 && it.days >= DayWork.MIN_BRIDGE_DAYS })
        assertTrue(found.zipWithNext().all { (a, b) -> !a.from.isAfter(b.from) })
    }

    @Test
    fun `the overtime cap says what is left and when it is passed`() {
        val cap = DayWork.OvertimeCap(usedMinutes = 50 * 60, capHours = 57)
        assertEquals(7 * 60, cap.leftMinutes)
        assertFalse(cap.over)
        val over = DayWork.OvertimeCap(usedMinutes = 60 * 60, capHours = 57)
        assertTrue(over.over)
        assertEquals(3 * 60, over.overMinutes)
        assertEquals(0, over.leftMinutes)
    }
}
