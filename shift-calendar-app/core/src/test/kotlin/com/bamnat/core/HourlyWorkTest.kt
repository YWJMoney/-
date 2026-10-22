package com.bamnat.core

import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.LegalBreak
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.HourlyWork
import com.bamnat.core.work.WorkLogCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [HRLY-01..HRLY-08] A part-timer's attendance, 주휴, cells, take-home pay, breaks, probation and records. */
class HourlyWorkTest {
    private val oct = YearMonth.of(2026, 10)
    private val wed = LocalDate.of(2026, 10, 14)

    /** 월·수·금 오후 14~22 (휴게 30분): 7시간 30분 a day, 22시간 30분 a week. */
    private fun resolver(entries: List<DayEntry> = emptyList(), legalBreaks: Boolean = false) = ScheduleResolver(
        Fixtures.all,
        listOf(WorkPattern.Weekly(LocalDate.of(2026, 9, 1), mapOf(
            DayOfWeek.MONDAY to Fixtures.evening.id, DayOfWeek.WEDNESDAY to Fixtures.evening.id, DayOfWeek.FRIDAY to Fixtures.evening.id,
        ))),
        entries,
        legalBreaks = legalBreaks,
    )

    private val partTime = PayProfile(
        occupation = Occupation.PART_TIME, wageType = WageType.HOURLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_320))),
        fiveOrMoreEmployees = false,
    )

    private fun weeklyHolidayMinutes(profile: PayProfile, r: ScheduleResolver) =
        PayCalculator().calculate(profile, oct, r::resolve).lines.singleOrNull { it.label == "주휴수당" }?.minutes ?: 0

    @Test
    fun `an absent day has no hours or pay but keeps its shift, late keeps the hours`() {
        val absent = resolver(listOf(DayEntry(wed, attendance = Attendance.ABSENT))).resolve(wed)
        assertEquals(0, absent.scheduledMinutes)
        assertFalse(absent.isWorkDay)
        assertEquals(Fixtures.evening, absent.shiftType)
        val late = resolver(listOf(DayEntry(wed, attendance = Attendance.LATE))).resolve(wed)
        assertEquals(450, late.scheduledMinutes)
        val pay = PayCalculator().calculate(partTime, oct, resolver(listOf(DayEntry(wed, attendance = Attendance.ABSENT)))::resolve)
        assertTrue(pay.day(wed)!!.parts.none { it.kind == LineKind.BASE })
    }

    @Test
    fun `attendance is kept in the day's change history`() {
        val before = DayEntry(wed)
        assertEquals("결근", com.bamnat.core.model.DayChanges.describe(before, before.copy(attendance = Attendance.ABSENT), { "근무" }))
        val skipped = before.copy(alarmSkipped = true)
        assertEquals("지각 취소", com.bamnat.core.model.DayChanges.describe(skipped.copy(attendance = Attendance.LATE), skipped, { "근무" }))
        assertEquals("시간 18:05–22:00", com.bamnat.core.model.DayChanges.describe(before,
            before.copy(customStart = LocalTime.of(18, 5), customEnd = LocalTime.of(22, 0)), { "근무" }))
    }

    @Test
    fun `a week with an absence earns no weekly holiday pay, late or early does`() {
        // Weeks from Monday ending in October: 9/28, 10/5, 10/12, 10/19, each 22.5h → 4.5h of 주휴.
        assertEquals(4 * 270, weeklyHolidayMinutes(partTime, resolver()))
        assertEquals(3 * 270, weeklyHolidayMinutes(partTime, resolver(listOf(DayEntry(wed, attendance = Attendance.ABSENT)))))
        assertEquals(4 * 270, weeklyHolidayMinutes(partTime, resolver(listOf(DayEntry(wed, attendance = Attendance.EARLY)))))

        val weeks = HourlyWork.weeks(oct, DayOfWeek.MONDAY, resolver(listOf(DayEntry(wed, attendance = Attendance.ABSENT)))::resolve)
        val absentWeek = weeks.single { it.start == LocalDate.of(2026, 10, 12) }
        assertEquals(1, absentWeek.absent)
        assertFalse(absentWeek.holidayPay)
        assertTrue(weeks.single { it.start == LocalDate.of(2026, 10, 5) }.holidayPay)

        // The badge counts as 주휴수당 does: extra work is not 소정근로, paid leave is attended.
        val mon = LocalDate.of(2026, 10, 5)
        val extra = DayEntry(LocalDate.of(2026, 10, 7), extras = listOf(com.bamnat.core.model.ExtraEntry(com.bamnat.core.model.ExtraKind.OVERTIME_ID, minutes = 120)))
        val paidLeave = DayEntry(LocalDate.of(2026, 10, 9), leave = com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.ANNUAL))
        assertEquals(1350, HourlyWork.week(mon, resolver(listOf(extra, paidLeave))::resolve).minutes)
        val unpaid = paidLeave.copy(leave = com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.UNPAID))
        assertEquals(900, HourlyWork.week(mon, resolver(listOf(unpaid))::resolve).minutes)
    }

    @Test
    fun `a paid holiday counts its scheduled day once toward weekly holiday hours`() {
        // [PAY-13] 5인 이상: 2026-10-09 (금) 한글날 is a paid 공휴일. Week 10/5: 월·수 7.5h each plus the 금요일.
        val big = partTime.copy(fiveOrMoreEmployees = true)
        val fri = LocalDate.of(2026, 10, 9)
        val others = 3 * 270
        // Normal weeks (10/12 on, and 9/28): 22.5h → 4.5h each; the holiday worked 14~22 counts 7.5h once.
        assertEquals(others + 270, weeklyHolidayMinutes(big, resolver()))
        // Worked on a night shift 22~07: the hours past midnight are regular, yet the day still counts 8h once
        // (7.5 + 7.5 + 8 = 23h → 4.6h), not 8h of 유급휴일 plus them.
        assertEquals(others + 276, weeklyHolidayMinutes(big, resolver(listOf(DayEntry(fri, override = ShiftOverride.Shift(Fixtures.night.id))))))
        // Paid leave on the holiday: the 유급휴일 already holds those hours.
        val leave = DayEntry(fri, leave = com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.ANNUAL))
        assertEquals(others + 270, weeklyHolidayMinutes(big, resolver(listOf(leave))))
        // Not worked (쉬는 날): no 소정 hours that day, 15h → 3h.
        assertEquals(others + 180, weeklyHolidayMinutes(big, resolver(listOf(DayEntry(fri, override = ShiftOverride.Off)))))
    }

    @Test
    fun `the weekly holiday week can start on another weekday`() {
        // From Sunday: 9/27, 10/4, 10/11, 10/18, 10/25 all end in October.
        assertEquals(5 * 270, weeklyHolidayMinutes(partTime.copy(holidayWeekStart = DayOfWeek.SUNDAY), resolver()))
        assertEquals(DayOfWeek.MONDAY, partTime.holidayWeekFrom)
        // The calendar row from Sunday 10/11 carries the 주휴 week ending in it.
        val w = HourlyWork.weekEndingIn(LocalDate.of(2026, 10, 11), DayOfWeek.MONDAY, resolver()::resolve)
        assertEquals(LocalDate.of(2026, 10, 5), w.start)
    }

    @Test
    fun `cells show the time range and the day's pay`() {
        val day = resolver().resolve(wed)
        assertEquals("14–22", HourlyWork.timeRange(day))
        val custom = resolver(listOf(DayEntry(wed, customStart = LocalTime.of(18, 30), customEnd = LocalTime.of(23, 0)))).resolve(wed)
        assertEquals("18:30–23", HourlyWork.timeRange(custom))
        assertEquals(77_400L, HourlyWork.dayPay(day, partTime))
        assertEquals("7.7만", HourlyWork.brief(77_400))
        assertEquals("12만", HourlyWork.brief(120_000))
        assertEquals("8,000", HourlyWork.brief(8_000))
        assertNull(HourlyWork.timeRange(resolver().resolve(LocalDate.of(2026, 10, 13))))
        assertEquals(0L, HourlyWork.dayPay(day, partTime.copy(wageType = WageType.MONTHLY)))
    }

    @Test
    fun `take home pay for each tax mode`() {
        assertEquals(1_000_000L, TakeHome.estimate(1_000_000, TaxMode.NONE).net)
        val w = TakeHome.estimate(1_000_000, TaxMode.WITHHOLDING_3_3)
        assertEquals(listOf("원천징수 3.3%" to 33_000L), w.deductions)
        assertEquals(967_000L, w.net)

        val i = TakeHome.estimate(2_000_000, TaxMode.INSURANCE_4)
        val d = i.deductions.toMap()
        assertEquals(95_000L, d["국민연금"])
        assertEquals(71_900L, d["건강보험"])
        assertEquals(9_440L, d["장기요양"])
        assertEquals(18_000L, d["고용보험"])
        // 간이세액표 says 19,520원 for 200만 원 and one person; the approximation is close and labelled 참고용.
        val tax = d.getValue("근로소득세 (참고용)")
        assertTrue(tax in 15_000L..25_000L)
        assertEquals(tax / 10 / 10 * 10, d["지방소득세 (참고용)"])
        assertEquals(i.gross - i.deductions.sumOf { it.second }, i.net)
        // A small month pays no income tax at all.
        assertTrue(TakeHome.estimate(800_000, TaxMode.INSURANCE_4).deductions.none { it.first.startsWith("근로소득세") })
        // 국민연금 is on the 기준소득월액, kept between 40만 and 637만 원.
        assertEquals(19_000L, TakeHome.estimate(300_000, TaxMode.INSURANCE_4).deductions.toMap()["국민연금"])
        assertEquals(302_570L, TakeHome.estimate(10_000_000, TaxMode.INSURANCE_4).deductions.toMap()["국민연금"])
    }

    @Test
    fun `the legal break comes off days without one`() {
        assertEquals(0, LegalBreak.minutesFor(239))
        assertEquals(30, LegalBreak.minutesFor(240))
        // 8 hours on the clock with 30 minutes off is 7½ hours worked: 30 minutes is enough.
        assertEquals(30, LegalBreak.minutesFor(480))
        assertEquals(30, LegalBreak.minutesFor(509))
        assertEquals(60, LegalBreak.minutesFor(510))
        val longDay = LocalDate.of(2026, 10, 13)
        val entries = listOf(
            DayEntry(longDay, override = ShiftOverride.Shift(Fixtures.long.id)),
            DayEntry(wed, override = ShiftOverride.Shift(Fixtures.long.id), customStart = LocalTime.of(18, 0), customEnd = LocalTime.of(22, 0)),
            DayEntry(LocalDate.of(2026, 10, 15), override = ShiftOverride.Shift(Fixtures.long.id), customBreakMinutes = 0),
        )
        val r = resolver(entries, legalBreaks = true)
        assertEquals(11 * 60, r.resolve(longDay).scheduledMinutes)
        assertEquals(210, r.resolve(wed).scheduledMinutes)
        // A break set to 0 on purpose stays 0; a shift with its own break keeps it.
        assertEquals(12 * 60, r.resolve(LocalDate.of(2026, 10, 15)).scheduledMinutes)
        assertEquals(450, r.resolve(LocalDate.of(2026, 10, 16)).scheduledMinutes)
        assertEquals(12 * 60, resolver(entries).resolve(longDay).scheduledMinutes)
        // The pay profile's switch does the same for pay.
        val pay = PayCalculator().calculate(partTime.copy(legalBreaks = true), oct, resolver(entries)::resolve)
        assertEquals(11 * 60, pay.day(longDay)!!.minutes.worked)
    }

    @Test
    fun `days before probation ends pay 90 percent`() {
        val profile = partTime.copy(probationEnd = LocalDate.of(2026, 10, 15))
        val pay = PayCalculator().calculate(profile, oct, resolver()::resolve)
        // 10/2, 5, 7, 9, 12, 14: six days of 7.5h × 10,320원 × 10%, and 주휴 at the 수습 rate too: 4.5h for
        // the weeks of 9/28 and 10/5, two thirds of 4.5h for the week of 10/12 (10/16 is after it): 12h × 10,320원 × 10%.
        assertEquals(-46_440L - 12_384L, pay.lines.single { it.label == "수습 감액" }.amount)
        assertEquals(-7_740L, pay.day(wed)!!.parts.single { it.label == "수습 감액" }.amount)
        // Every hour-based part of a day on probation is at the 수습 rate, 잔업 included, as the calendar's day pay is.
        val ot = DayEntry(wed, extras = listOf(com.bamnat.core.model.ExtraEntry(com.bamnat.core.model.ExtraKind.OVERTIME_ID, minutes = 120)))
        val otDay = PayCalculator().calculate(profile, oct, resolver(listOf(ot))::resolve).day(wed)!!
        val hourPay = otDay.parts.filter { it.label != "수습 감액" && it.kind in setOf(LineKind.BASE, LineKind.EXTRA_HOURS, LineKind.OVERTIME, LineKind.NIGHT, LineKind.HOLIDAY) }
            .sumOf { it.amount }
        assertTrue(hourPay > 77_400L)
        assertEquals(-Math.round(hourPay * 0.1), otDay.parts.single { it.label == "수습 감액" }.amount)
        assertTrue(pay.day(LocalDate.of(2026, 10, 16))!!.parts.none { it.label == "수습 감액" })
        assertTrue(pay.warnings.none { it.contains("수습") })
        assertEquals(69_660L, HourlyWork.dayPay(resolver().resolve(wed), profile))
        // 10,000원 × 90% is under 90% of the minimum wage: warned.
        val low = profile.copy(base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_000))))
        assertTrue(PayCalculator().calculate(low, oct, resolver()::resolve).warnings.any { it.contains("수습") })
        // [PAYR-02] Hired in June, a probation still cutting in October is past the three months: warned.
        val long = profile.copy(hireDate = LocalDate.of(2026, 6, 1))
        assertTrue(PayCalculator().calculate(long, oct, resolver()::resolve).warnings.any { it.contains("3개월") })
        val short = profile.copy(hireDate = LocalDate.of(2026, 8, 1))
        assertTrue(PayCalculator().calculate(short, oct, resolver()::resolve).warnings.none { it.contains("3개월") })
    }

    @Test
    fun `clock in rounds to five minutes and the work log is a csv`() {
        assertEquals(LocalTime.of(18, 0), HourlyWork.clock(LocalTime.of(17, 58, 10)))
        assertEquals(LocalTime.of(18, 5), HourlyWork.clock(LocalTime.of(18, 3)))
        assertEquals(LocalTime.of(18, 0), HourlyWork.clock(LocalTime.of(18, 2, 20)))
        // Just before midnight it stays on today.
        assertEquals(LocalTime.of(23, 55), HourlyWork.clock(LocalTime.of(23, 58)))
        val r = resolver(listOf(DayEntry(wed, attendance = Attendance.ABSENT)))
        val csv = WorkLogCsv.write(r.resolveRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14))) { HourlyWork.dayPay(it, partTime) }
        val rows = csv.removePrefix("﻿").trim().lines()
        assertEquals("날짜,출근,퇴근,휴게(분),근무 시간,출결,일급", rows[0])
        assertEquals("2026-10-12,14:00,22:00,30,7.50,,77400", rows[1])
        assertEquals("2026-10-14,,,,0.00,결근,0", rows[2])
        assertEquals(3, rows.size)
        assertEquals("밤낮_근무기록_2026-10.csv", WorkLogCsv.fileName(oct))
    }
}

/** [PAYR-01] 실수령 follows the month's year: 2027 국민연금 10% (근로자 5%), 2026.7 기준소득월액 41만~659만. */
class TakeHomeYearTest {
    private fun pension(gross: Long, on: java.time.LocalDate) =
        com.bamnat.core.pay.TakeHome.estimate(gross, com.bamnat.core.pay.TaxMode.INSURANCE_4, on = on).deductions.toMap()["국민연금"]

    @Test fun ratesFollowTheYear() {
        org.junit.Assert.assertEquals(95_000L, pension(2_000_000, java.time.LocalDate.of(2026, 3, 1)))
        org.junit.Assert.assertEquals(100_000L, pension(2_000_000, java.time.LocalDate.of(2027, 1, 1)))
        // Limits: 2026.6 still 637만, 2026.7 659만; 2025 rates 9% (4.5%).
        org.junit.Assert.assertEquals(302_570L, pension(10_000_000, java.time.LocalDate.of(2026, 6, 30)))
        org.junit.Assert.assertEquals(313_020L, pension(10_000_000, java.time.LocalDate.of(2026, 7, 1)))
        org.junit.Assert.assertEquals(19_470L, pension(300_000, java.time.LocalDate.of(2026, 8, 1)))
        org.junit.Assert.assertEquals(90_000L, pension(2_000_000, java.time.LocalDate.of(2025, 5, 1)))
    }

    @Test fun careKeepsTheLastKnownShareUntilFixed() {
        val d = com.bamnat.core.pay.TakeHome.estimate(3_000_000, com.bamnat.core.pay.TaxMode.INSURANCE_4, on = java.time.LocalDate.of(2027, 2, 1)).deductions.toMap()
        org.junit.Assert.assertEquals(107_850L, d["건강보험"])
        org.junit.Assert.assertEquals(14_170L, d["장기요양"])
    }
}
