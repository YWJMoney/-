package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * A 4조2교대 manufacturing company as one worker described it [PAYX-07]: 12-hour shifts with the
 * break counted as work, 월 180시간, 상여 800% in even months, 자격수당 monthly, 대근 paid in
 * blocks (주간 8h 100% + 4h 150%; 야간 4h 150% → 4h 175% → 4h 200%), 공휴일 shifts paid like 대근.
 * The amounts are this test's assumptions, not a published example.
 */
class FourTeamShiftTest {
    private val day = ShiftType(1, "주간", "주", 0xFF000000, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(2, "야간", "야", 0xFF000000, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(3, "휴무", "휴", 0xFF000000, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 1)
    private val cycle = listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)
    private fun roster(vararg e: DayEntry) = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(start, cycle), e.toList())

    private val since = LocalDate.of(2026, 1, 1)
    private val profile = PayProfile(
        occupation = Occupation.MANUFACTURING, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(since, 2_500_000))),
        items = listOf(
            PayItem("license", "자격수당", ValueTimeline(listOf(DatedValue(since, 100_000))), Cadence.MONTHLY, ordinary = true),
            PayItem("bonus", "상여금", ValueTimeline(listOf(DatedValue(since, 0))), Cadence.BONUS, ordinary = true,
                months = setOf(2, 4, 6, 8, 10, 12), percentOfBase = 8.0 / 6),
        ),
        monthlyHours = 180, dailyLimitMinutes = null, weeklyLimitMinutes = null,
        rules = CompanyRules(
            extra = mapOf(ExtraWork.COVER to ExtraRule(ExtraBasis.TIERED, nightPremium = false)),
            publicHolidayAs = ExtraWork.COVER, holidayLeaveFree = true,
        ),
    )
    private val nov = YearMonth.of(2026, 11)

    @Test
    fun `ordinary hourly includes the 800 percent bonus and the license allowance`() {
        // (2,500,000 + 100,000 + 2,500,000 × 8 ÷ 12) ÷ 180 = 23,703.7
        assertEquals(23_704, PayCalculator().calculate(profile, nov, roster()::resolve) { false }.ordinaryHourly)
    }

    @Test
    fun `the roster itself adds no overtime`() {
        val pay = PayCalculator().calculate(profile, nov, roster()::resolve) { false }
        assertEquals(0, pay.lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.amount })
    }

    @Test
    fun `day and night cover are paid in blocks`() {
        val dayCover = LocalDate.of(2026, 11, 3)   // 휴
        val nightCover = LocalDate.of(2026, 11, 4) // 휴
        val r = roster(
            DayEntry(dayCover, secondShiftId = day.id, extraWork = ExtraWork.COVER),
            DayEntry(nightCover, secondShiftId = night.id, extraWork = ExtraWork.COVER),
        )
        val pay = PayCalculator().calculate(profile, nov, r::resolve) { false }
        assertEquals(23_704L * 14, pay.day(dayCover)!!.parts.single { it.kind == LineKind.EXTRA }.amount)   // 8 + 4 × 1.5
        assertEquals(23_704L * 21, pay.day(nightCover)!!.parts.single { it.kind == LineKind.EXTRA }.amount) // 4×1.5 + 4×1.75 + 4×2
    }

    @Test
    fun `a public holiday shift is paid like a cover and leave on it keeps the annual days`() {
        val holiday = LocalDate.of(2026, 11, 1) // 주간 in the cycle
        val pay = PayCalculator().calculate(profile, nov, roster()::resolve) { it == holiday }
        assertEquals(23_704L * 14, pay.day(holiday)!!.parts.single { it.kind == LineKind.EXTRA }.amount)
        assertTrue(pay.lines.none { it.kind == LineKind.HOLIDAY })
        val r = roster(DayEntry(holiday, leave = Leave()))
        val book = LeaveBook.balance(2026, 15.0, LocalDate.of(2026, 12, 31), r::resolve, free = { it == holiday })
        assertEquals(0.0, book.used, 1e-9)
    }
}
