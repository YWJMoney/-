package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [EXTRA-07][EXTRA-08] 대근 for several shifts, and pay as an hour's pay plus ticked premiums, worked
 * out by hand. Same workplace as [ExtraWorkGoldenTest]: 기본급 2,508,000원, 통상시급 12,000원
 * (÷ 209), 5인 이상, 4조2교대 주주휴휴야야휴휴 from 11/1. A case's amount is what the work adds to
 * the month. The company way (대근 8시간에 기본급 10%, 8시간 넘으면 0.5, 야간 0.5 가산) is the one
 * a user described on 2026-10-17; 1시간 = 2,508,000 × 10% ÷ 8 = 31,350원.
 */
class ExtraPremiumTest {
    private val nov = YearMonth.of(2026, 11)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
    private val night = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
    private val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 1)
    private val four = listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)
    private fun roster(vararg e: DayEntry) = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(start, four), e.toList())

    private val salary = PayProfile(
        occupation = Occupation.MANUFACTURING, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_508_000))),
    )

    /** 11/3 휴무 19:00~07:00 야간 대근, 휴게 02~03: 11시간, 그중 22~06시 야간 7시간. */
    private val nov3 = LocalDate.of(2026, 11, 3)
    private val nightCover = ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = night.id)

    private fun cover(rule: ExtraRule, rules: CompanyRules = CompanyRules()) =
        salary.copy(rules = rules.copy(extra = mapOf(ExtraKind.COVER_ID to rule)))

    private fun pay(p: PayProfile, r: ScheduleResolver, month: YearMonth = nov) = PayCalculator().calculate(p, month, r::resolve)
    private fun added(p: PayProfile, vararg e: DayEntry, month: YearMonth = nov) =
        pay(p, roster(*e), month).total - pay(p, roster(), month).total

    private val law = ExtraRule.lawUnit(overtime = 0.5, night = 0.5, holiday = 0.5, restAsHoliday = false)

    /** The company way: 8시간에 기본급 10%, 이 대근 8시간 넘는 시간 50%, 야간 50%, 휴일·휴무일 가산 없음. */
    private val company = law.copy(
        unit = PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480,
        premiums = law.premiums.map {
            when (it.key) {
                Premium.OVERTIME -> it.copy(scope = PremiumScope.Over(480))
                Premium.HOLIDAY -> it.copy(on = false)
                else -> it
            }
        },
    )

    @Test
    fun `U1 the law's ticks pay a night cover on a day off what the law does, 192,000`() {
        // 11 × 12,000 + 연장 3 × 6,000 + 야간 7 × 6,000, as ExtraWorkGoldenTest T1.
        assertEquals(192_000, added(cover(law), DayEntry(nov3, extras = listOf(nightCover))))
        assertEquals(192_000, added(salary, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `U2 the company way pays 501,600 for the same cover`() {
        // 11 × 31,350 = 344,850 + 8시간 넘는 3 × 15,675 = 47,025 + 야간 7 × 15,675 = 109,725.
        val p = pay(cover(company), roster(DayEntry(nov3, extras = listOf(nightCover))))
        assertEquals(501_600, p.total - pay(cover(company), roster()).total)
        val part = p.day(nov3)!!.parts.single { it.label == "대근" }
        assertEquals(501_600, part.amount)
        assertTrue(part.formula, part.formula.startsWith("11시간 × 31,350원(기본급 2,508,000원 × 10% ÷ 8시간) = 344,850원"))
        assertTrue(part.formula, "연장 가산 3시간 × 50% = 47,025원" in part.formula)
        assertTrue(part.formula, "야간 가산 7시간 × 50% = 109,725원" in part.formula)
        // The cover's night is paid by the rule, not again in the month's night line.
        fun night(m: MonthPay) = m.lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount }
        assertEquals(night(pay(cover(company), roster())), night(p))
    }

    @Test
    fun `U3 four night hours past the eighth are 10 percent of 기본급, as the user said`() {
        // Night 23~07 (the company's), no break: 12시간 대근 − 8시간 대근 = 4 × 31,350 × (1 + 0.5 + 0.5) = 250,800.
        val rules = CompanyRules(nightStart = t(23), nightEnd = t(7))
        val twelve = added(cover(company, rules), DayEntry(nov3, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(19), t(7)))))
        val eight = added(cover(company, rules), DayEntry(nov3, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(19), t(3)))))
        assertEquals(564_300, twelve)
        assertEquals(2_508_000 / 10, twelve - eight)
        // An 8시간 대근 in the day is exactly 기본급의 10%.
        assertEquals(250_800, added(cover(company, rules), DayEntry(nov3, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(9), t(17))))))
    }

    @Test
    fun `U4 a premium the user adds for 19 to 23 counts those four hours`() {
        val late = company.copy(premiums = company.premiums + Premium("c1", "심야 가산", 0.25, scope = PremiumScope.Window(t(19), t(23))))
        // + 4 × 31,350 × 25% = 31,350.
        assertEquals(501_600 + 31_350, added(cover(late), DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `U5 a premium for days off only leaves 잔업 on a work day alone`() {
        val own = law.copy(premiums = law.premiums + Premium("c1", "쉬는 날 가산", 0.5, scope = PremiumScope.All, offDaysOnly = true))
        val p = salary.copy(rules = CompanyRules(extra = mapOf(ExtraKind.OVERTIME_ID to own)))
        // 11/1 주간 07~19 then 잔업 2시간: 2 × 12,000 + 연장 2 × 6,000, the same as the law.
        val overtime = DayEntry(start, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)))
        assertEquals(36_000, added(p, overtime))
        assertEquals(36_000, added(salary, overtime))
    }

    @Test
    fun `U6 a day off that is a public holiday takes one premium, not both`() {
        // 12/25 (성탄절) is a 휴 day of the roster.
        val dec25 = LocalDate.of(2026, 12, 25)
        val dec = YearMonth.of(2026, 12)
        fun with(holiday: Boolean, rest: Boolean) = cover(law.copy(premiums = law.premiums.map {
            when (it.key) {
                Premium.HOLIDAY -> it.copy(on = holiday)
                Premium.REST -> it.copy(on = rest)
                else -> it
            }
        }))
        val entry = DayEntry(dec25, extras = listOf(nightCover))
        assertTrue(roster().resolve(dec25).isRestDay)
        val both = added(with(holiday = true, rest = true), entry, month = dec)
        assertEquals(added(with(holiday = false, rest = true), entry, month = dec), both)
        assertTrue(added(with(holiday = true, rest = false), entry, month = dec) < both)
        assertTrue(added(with(holiday = false, rest = false), entry, month = dec) < added(with(holiday = true, rest = false), entry, month = dec))
    }

    @Test
    fun `U7 one payment each time keeps premiums on 통상시급`() {
        // 1회 80,000원 (8시간까지) + 3 × 12,000 + 연장 3 × 6,000 + 야간 7 × 6,000 = 176,000.
        val once = law.copy(unit = PayUnit.PER_TIME, amount = 80_000, coverMinutes = 480)
        assertEquals(176_000, added(cover(once), DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `U8 under five employees the law's ticks are all off`() {
        val small = ExtraRule.lawUnit(overtime = 0.0, night = 0.0, holiday = 0.0, restAsHoliday = true)
        assertTrue(small.premiums.none { it.on })
        assertTrue(small.premiums.all { it.rate == 0.5 })
        assertEquals(132_000, added(cover(small).copy(fiveOrMoreEmployees = false), DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `U9 hours credited at least count only toward the hour's pay`() {
        val credited = law.copy(minMinutes = 480)
        // 11/3 09~13: 8시간 인정 × 12,000; no premium on 4 worked hours.
        assertEquals(96_000, added(cover(credited), DayEntry(nov3, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(9), t(13))))))
    }

    @Test
    fun `U10 the rule says itself in a line`() {
        assertEquals("8시간에 기본급의 10% + 연장 50% + 야간 50%", company.describe())
        assertEquals(ExtraBasis.UNIT, company.basis)
        assertFalse(company.isLegal)
        assertTrue(company.ownsNight)
        assertEquals("1회 80,000원 (8시간까지) + 연장 50% + 야간 50% + 휴일 50%",
            law.copy(unit = PayUnit.PER_TIME, amount = 80_000, coverMinutes = 480).describe())
    }

    @Test
    fun `U11 one 대근 covers several shifts and reads back`() {
        val both = ExtraTiming.AsShift(setOf(day.id, night.id))
        assertTrue(both.covers(night.id))
        assertEquals(null, both.shiftTypeId)
        assertEquals(night.id, ExtraTiming.AsShift(night.id).shiftTypeId)
        assertFalse(ExtraTiming.AsShift(day.id).covers(night.id))
        assertTrue(ExtraTiming.AsShift().covers(night.id))
        val kinds = ExtraKinds(listOf(
            ExtraKind(ExtraKind.COVER_ID, "대근", "대", 0xFF0E9F6E, both),
            ExtraKind(101, "야간 대근", "야", 0xFF5E35B1, ExtraTiming.AsShift(night.id)),
            ExtraKind(102, "아무 대근", "아", 0xFF5E35B1, ExtraTiming.AsShift()),
        ))
        val text = kinds.encode()
        assertTrue(text, "|S,1;2|" in text)
        assertEquals(kinds, ExtraKinds.decode(text))
        // A kind for a shift since deleted offers every shift rather than none.
        assertEquals(listOf(day, night), ExtraTiming.AsShift(99).shifts(listOf(day, night)))
        assertEquals(listOf(night), ExtraTiming.AsShift(night.id).shifts(listOf(day, night)))
    }

    @Test
    fun `U12 a 공휴일 night shift paid the 대근 way comes to what a night cover does`() {
        // 2027-01-01 (신정) is the roster's second 야간; the whole shift counts by the day it starts.
        val newYear = LocalDate.of(2027, 1, 1)
        val rules = CompanyRules(publicHolidayAs = ExtraKind.COVER_ID, holidayByShiftStart = true)
        val day = pay(cover(company, rules), roster(), YearMonth.of(2027, 1)).day(newYear)!!
        // 11 × 31,350 + 8시간 넘는 3 × 15,675 + 야간 7 × 15,675, as U2.
        assertEquals(501_600, day.parts.single { it.label == "대근 방식 (공휴일 근무)" }.amount)
        // Its hours leave the law's holiday and night lines of that day.
        assertTrue(day.parts.toString(), day.parts.none { it.kind == LineKind.HOLIDAY || it.kind == LineKind.NIGHT })
    }
}
