package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayScale
import com.bamnat.core.pay.PayslipCheck
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.VerifiedChange
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PAYX-17..22] 급여 2단계 golden cases (docs/pay-golden-cases.md 주제 12, 1.22 묶음 D). 통상시급 12,000원 (2,508,000원
 * ÷ 209), 5인 이상. Arithmetic from 근로기준법 제51·55·56·57조, not a published example; not a substitute for a 공인노무사.
 */
class PayGolden2Test {
    private val nov = YearMonth.of(2026, 11)
    private val monday = LocalDate.of(2026, 11, 2)
    private val saturday = LocalDate.of(2026, 11, 7)
    private val sunday = LocalDate.of(2026, 11, 8)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private val day = ShiftType(10, "주간", "주", 0xFF000000, t(9), t(18), 60, t(12))
    private val off = ShiftType(11, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private fun weekly(vararg e: DayEntry) =
        ScheduleResolver(listOf(day, off), WorkPattern.Rotation(monday, List(5) { day.id } + List(2) { off.id }), e.toList())

    private val moel = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 12_000L * 209))))
    private val sundayOff = CompanyRules(weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY))
    private fun special(date: LocalDate, minutes: Int, comp: Boolean = false) =
        DayEntry(date, extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, minutes = minutes, compLeave = comp)))
    private fun MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }

    // ---- PAYX-17 탄력 2주 ----

    @Test
    fun `G2-1 two weeks of 48 and 32 hours average 40, no overtime`() {
        // 6일 × 8시간, 4일 × 8시간 from 11/2, the company's 2주 starting that Monday (제51조 제1항).
        val cycle = List(6) { day.id } + off.id + List(4) { day.id } + List(3) { off.id }
        val r = ScheduleResolver(listOf(day, off), WorkPattern.Rotation(monday, cycle), emptyList())
        val p = moel.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.TWO_WEEKS, flexStart = monday, rules = sundayOff)
        val result = PayCalculator().calculate(p, nov, r::resolve)
        assertEquals(0, result.sum(LineKind.OVERTIME))
        assertTrue(result.warnings.none { "48시간" in it })
        assertEquals(LocalDate.of(2026, 11, 16), FlexPeriod.TWO_WEEKS.start(LocalDate.of(2026, 11, 20), monday))
        assertEquals(LocalDate.of(2026, 11, 29), FlexPeriod.TWO_WEEKS.end(LocalDate.of(2026, 11, 20), monday))
    }

    @Test
    fun `G2-2 a 56 hour week in a two week period is past the 48 hour cap`() {
        val cycle = List(7) { day.id } + List(3) { day.id } + List(4) { off.id }
        val r = ScheduleResolver(listOf(day, off), WorkPattern.Rotation(monday, cycle), emptyList())
        val p = moel.copy(scheduleIsRegular = true, flexPeriod = FlexPeriod.TWO_WEEKS, flexStart = monday)
        assertTrue(PayCalculator().calculate(p, nov, r::resolve).warnings.any { "48시간" in it && "제51조" in it })
    }

    // ---- PAYX-18 보상휴가 ----

    @Test
    fun `G2-3 eight hours on the weekly holiday paid in time off is twelve hours of leave and no pay`() {
        val p = moel.copy(rules = sundayOff)
        val paid = PayCalculator().calculate(p, nov, weekly(special(sunday, 480))::resolve)
        val comp = PayCalculator().calculate(p, nov, weekly(special(sunday, 480, comp = true))::resolve)
        assertEquals(144_000, paid.sum(LineKind.HOLIDAY))
        assertEquals(0, comp.sum(LineKind.HOLIDAY))
        assertEquals(paid.total - 144_000, comp.total)
        // 8시간 × 150% = 12시간 (제57조: 가산임금을 갈음하는 휴가).
        assertEquals(mapOf(sunday to 720), comp.compLeave)
        assertTrue(comp.lines.any { it.kind == LineKind.LEAVE && it.amount == 0L && "제57조" in (it.basis ?: "") })
    }

    // ---- PAYX-19 공휴일과 쉬는 날이 겹칠 때 (지금 동작을 고정) ----

    @Test
    fun `G2-4 ten hours on a holiday that falls on a day off is holiday work, 192,000`() {
        val p = moel.copy(rules = sundayOff)
        val r = weekly(special(saturday, 600))
        val holiday = PayCalculator().calculate(p, nov, r::resolve, isHoliday = { it == saturday })
        assertEquals(192_000, holiday.sum(LineKind.HOLIDAY))
        // The same Saturday as a plain 휴무일 after a 40 hour week is all overtime.
        val plain = PayCalculator().calculate(p, nov, r::resolve, isHoliday = { false })
        assertEquals(180_000, plain.sum(LineKind.OVERTIME))
    }

    @Test
    fun `G2-5 a holiday on an hourly worker's day off adds no paid holiday`() {
        val p = PayProfile(Occupation.PART_TIME, WageType.HOURLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 11_000))), rules = sundayOff)
        val none = PayCalculator().calculate(p, nov, weekly()::resolve, isHoliday = { false })
        val onSaturday = PayCalculator().calculate(p, nov, weekly()::resolve, isHoliday = { it == saturday })
        assertEquals(none.total, onSaturday.total)
    }

    @Test
    fun `G2-6 a holiday on the weekly holiday is paid once`() {
        val p = PayProfile(Occupation.PART_TIME, WageType.HOURLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 11_000))), rules = sundayOff)
        val none = PayCalculator().calculate(p, nov, weekly()::resolve, isHoliday = { false })
        val onSunday = PayCalculator().calculate(p, nov, weekly()::resolve, isHoliday = { it == sunday })
        assertEquals(none.total, onSunday.total)
    }

    // ---- PAYX-20 호봉표 ----

    @Test
    fun `G2-7 a pay scale steps up each March and stops at the top`() {
        val steps = PayScale.parse("1호봉 2,400,000\n2호봉 2,480,000원\n3 2560000\n메모 줄")
        assertEquals(mapOf(1 to 2_400_000L, 2 to 2_480_000L, 3 to 2_560_000L), steps)
        val scale = PayScale(steps, stepMonth = 3, step = 1, since = LocalDate.of(2026, 1, 1))
        val base = scale.applyTo(ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 1, 1), 2_300_000)), raise = RaiseRule(1, percent = 3.0)), LocalDate.of(2030, 1, 1))
        assertNull(base.raise)
        assertEquals(2_300_000, base.on(YearMonth.of(2025, 12)).value)
        assertEquals(2_400_000, base.on(YearMonth.of(2026, 2)).value)
        assertEquals(2_480_000, base.on(YearMonth.of(2026, 3)).value)
        assertEquals(2_560_000, base.on(YearMonth.of(2029, 6)).value)
        assertEquals(3, scale.stepOn(LocalDate.of(2029, 6, 1)))
    }

    // ---- PAYX-21 구간 가산의 시작점 ----

    @Test
    fun `G2-8 tiers counted from the day's first work put a night cover after a day shift at 200 percent`() {
        // ExtraWorkGoldenTest T10: 4조2교대 주간 07~19 then a 19~07 대근, tiers 4h 150% → 4h 175% → 200%.
        val dayShift = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
        val night = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
        val start = LocalDate.of(2026, 11, 1)
        val nov2 = LocalDate.of(2026, 11, 2)
        val cover = ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = night.id)
        val r = ScheduleResolver(listOf(dayShift, night, off), WorkPattern.Rotation(start, listOf(1L, 1L, 11L, 11L, 2L, 2L, 11L, 11L)),
            listOf(DayEntry(nov2, extras = listOf(cover))))
        val salary = PayProfile(Occupation.MANUFACTURING, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_508_000))))
        fun rule(fromDay: Boolean) = salary.copy(rules = CompanyRules(extra = mapOf(ExtraKind.COVER_ID to
            ExtraRule(ExtraBasis.TIERED, nightPremium = false, tiersFromDayStart = fromDay))))
        // From the piece: 주간 연장 54,000 + 4 × 18,000 + 4 × 21,000 + 3 × 24,000 = 282,000.
        assertEquals(282_000, PayCalculator().calculate(rule(false), nov, r::resolve).day(nov2)!!.amount)
        // From the day: 11시간 already worked, so all 11 hours are 200%: 54,000 + 11 × 24,000 = 318,000.
        assertEquals(318_000, PayCalculator().calculate(rule(true), nov, r::resolve).day(nov2)!!.amount)
    }

    // ---- PAYX-22 명세서와 맞춘 달 ----

    @Test
    fun `G2-9 a change that moves a matched month is told, and can start from this pay period`() {
        val old = moel.copy(verified = PayslipCheck(YearMonth.of(2026, 9), 2_508_000, 2_508_000, 0))
        val new = old.copy(base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_600_000))))
        val total = { p: PayProfile, m: YearMonth -> p.base.on(m).value }
        val change = VerifiedChange.check(old, new, total)
        assertNotNull(change)
        assertEquals(2_508_000, change!!.before)
        assertEquals(2_600_000, change.after)
        val scoped = VerifiedChange.startingOn(old, new, LocalDate.of(2026, 11, 1))!!
        assertEquals(2_508_000, scoped.base.on(YearMonth.of(2026, 9)).value)
        assertEquals(2_600_000, scoped.base.on(YearMonth.of(2026, 11)).value)
        assertNull(VerifiedChange.check(old, old, total))
        // A rule with no date (a company rule) cannot start later.
        assertNull(VerifiedChange.startingOn(old, new.copy(rules = sundayOff), LocalDate.of(2026, 11, 1)))
    }
}
