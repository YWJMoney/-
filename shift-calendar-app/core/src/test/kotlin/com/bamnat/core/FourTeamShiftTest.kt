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
import com.bamnat.core.pay.FlexPeriod
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

    // [PAYX-07] The same roster with the legal 8h/40h limits left on but "근무표 시간은 모두 기본 근무".
    private val legal = profile.copy(dailyLimitMinutes = 480, weeklyLimitMinutes = 2400, rules = CompanyRules())
    private fun overtimeMinutes(p: PayProfile, r: ScheduleResolver) =
        PayCalculator().calculate(p, nov, r::resolve) { false }.lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.minutes }

    @Test
    fun `without the schedule option the 12 hour roster counts 4 hours a shift as overtime`() {
        // 15 shifts in November 2026 (cycle starts on the 1st), 4 hours each over the daily limit, at least.
        assertTrue(overtimeMinutes(legal, roster()) >= 15 * 240)
    }

    @Test
    fun `schedule as regular work adds no overtime for the 12 hour roster`() {
        assertEquals(0, overtimeMinutes(legal.copy(scheduleIsRegular = true), roster()))
    }

    @Test
    fun `schedule as regular work still pays recorded extra hours and a second shift as overtime`() {
        val p = legal.copy(scheduleIsRegular = true)
        assertEquals(90, overtimeMinutes(p, roster(DayEntry(LocalDate.of(2026, 11, 1), overtimeMinutes = 90))))
        // A 4-hour 잔업 shift on top of a working day is all overtime, not room under the daily limit.
        val extra = ShiftType(4, "잔업", "잔", 0xFF000000, LocalTime.of(19, 0), LocalTime.of(23, 0))
        val r = ScheduleResolver(listOf(day, night, off, extra), WorkPattern.Rotation(start, cycle),
            listOf(DayEntry(LocalDate.of(2026, 11, 1), secondShiftId = extra.id)))
        assertEquals(240, overtimeMinutes(p, r))
    }

    @Test
    fun `schedule as regular work warns how much the legal count would add`() {
        val pay = PayCalculator().calculate(legal.copy(scheduleIsRegular = true), nov, roster()::resolve) { false }
        assertTrue(pay.warnings.any { it.startsWith("근무표 시간을 모두 기본 근무로") })
    }

    @Test
    fun `schedule as regular work keeps the night premium`() {
        val pay = PayCalculator().calculate(legal.copy(scheduleIsRegular = true), nov, roster()::resolve) { false }
        assertTrue(pay.lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount } > 0)
    }

    @Test
    fun `a one month flexible period makes only the hours over a 40 hour average overtime`() {
        // November 2026: 16 shifts × 12h = 11,520분; 30일 ÷ 7 × 40시간 = 10,286분 → 1,234분 연장, no warning.
        val p = legal.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.MONTH)
        assertEquals(1_234, overtimeMinutes(p, roster()))
        val pay = PayCalculator().calculate(p, nov, roster()::resolve) { false }
        assertTrue(pay.warnings.none { it.startsWith("근무표 시간을 모두 기본 근무로") })
    }

    @Test
    fun `a three month period is settled in its last month`() {
        val p = legal.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.QUARTER)
        // The roster starts on 1 November, so October has no shifts: the quarter is under the average.
        assertEquals(0, overtimeMinutes(p, roster()))
        val early = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(LocalDate.of(2026, 10, 1), cycle), emptyList())
        // October to December 2026: 92 days, 46 shifts × 12h = 33,120분; 92 ÷ 7 × 40시간 = 31,543분 → 1,577분.
        val dec = PayCalculator().calculate(p, YearMonth.of(2026, 12), early::resolve) { false }
        assertEquals(1_577, dec.lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.minutes })
        assertEquals(0, PayCalculator().calculate(p, nov, early::resolve) { false }.lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.minutes })
    }
}
