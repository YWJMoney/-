package com.bamnat.core

import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.model.DayChanges
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.Leave
import com.bamnat.core.model.OffDayTreatment
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CandidateKind
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.RuleImport
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [EXTRA-01..03] Extra work worked out by hand (docs/review 4-7). 통상시급 12,000원 (2,508,000원 ÷ 209),
 * 5인 이상, 법정 야간 22~06시: 150% = 18,000, 200% = 24,000, 야간 가산 50% = 6,000. A case's amount is
 * what the extra work adds to the month, so the salary itself never enters it. Arithmetic from the
 * law (근로기준법 제50·53·55·56조), not a published example; not a substitute for a 공인노무사.
 */
class ExtraWorkGoldenTest {
    private val nov = YearMonth.of(2026, 11)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    // 4조2교대 주주휴휴야야휴휴 from Sunday 11/1: 주간 07~19 (휴게 12~13), 야간 19~07 (휴게 02~03).
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
    private val night = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
    private val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 1)
    private val four = listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)
    private val three = listOf(day.id, day.id, night.id, night.id, off.id, off.id)
    private fun roster(cycle: List<Long> = four, vararg e: DayEntry) =
        ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(start, cycle), e.toList())

    // 사무직 월~금 09~18 (휴게 12~13) from Monday 11/2.
    private val office = ShiftType(10, "사무", "사", 0xFF1E88E5, t(9), t(18), 60, t(12))
    private val monday = LocalDate.of(2026, 11, 2)
    private fun officeWeek(vararg e: DayEntry) =
        ScheduleResolver(listOf(office, off), WorkPattern.Rotation(monday, List(5) { office.id } + List(2) { off.id }), e.toList())

    private val salary = PayProfile(
        occupation = Occupation.MANUFACTURING, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_508_000))),
    )
    private fun with(rules: CompanyRules) = salary.copy(rules = rules)

    /** 11/3 휴무 19:00~07:00 야간 대근: 11시간, 그중 야간 7시간 (02~03 휴게가 야간 안). */
    private val nightCover = ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = night.id)
    private val nov3 = LocalDate.of(2026, 11, 3)

    private fun pay(p: PayProfile, r: ScheduleResolver) = PayCalculator().calculate(p, nov, r::resolve)
    /** What [e] adds to the month. */
    private fun added(p: PayProfile, cycle: List<Long>, vararg e: DayEntry) = pay(p, roster(cycle, *e)).total - pay(p, roster(cycle)).total
    private fun MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }

    // ---- 휴무일 대근 ----

    @Test
    fun `T1 a night cover on a day off is 192,000 by law`() {
        // 8 × 12,000 (8·40시간 안) + 3 × 18,000 (하루 8시간 넘음) + 야간 7 × 6,000; 그 주 소정 24 + 8 = 32시간.
        val p = pay(salary, roster(four, DayEntry(nov3, extras = listOf(nightCover))))
        assertEquals(192_000, p.total - pay(salary, roster()).total)
        assertEquals(96_000, p.sum(LineKind.EXTRA_HOURS))
        assertEquals(480, p.minutes.single { it.date == nov3 }.extraRegular)
        assertEquals(180, p.minutes.single { it.date == nov3 }.overtime)
    }

    @Test
    fun `T2 the same cover as holiday work is 258,000`() {
        assertEquals(258_000, added(with(CompanyRules(restDayWork = RestDayWork.HOLIDAY)), four, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `T3 the same cover at a small workplace is 132,000`() {
        assertEquals(132_000, added(salary.copy(fiveOrMoreEmployees = false), four, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `T4 a cover in a week that already has 40 hours is all overtime, 240,000`() {
        // 3조 주주야야휴휴: 11/2~11/8 소정 40시간. 11/5 대근 11 × 18,000 + 42,000; 11/7·8 근무는 그대로.
        val d = LocalDate.of(2026, 11, 5)
        val withCover = pay(salary, roster(three, DayEntry(d, extras = listOf(nightCover))))
        val without = pay(salary, roster(three))
        assertEquals(240_000, withCover.total - without.total)
        listOf(7, 8).forEach { day ->
            val date = LocalDate.of(2026, 11, day)
            assertEquals(without.minutes.single { it.date == date }, withCover.minutes.single { it.date == date })
        }
    }

    @Test
    fun `T5 and T6 a saturday after a day of leave stays within 40 hours`() {
        val sat = LocalDate.of(2026, 11, 7)
        val special = ExtraEntry(ExtraKind.SPECIAL_ID, t(9), t(18), 60, t(12))
        val wed = DayEntry(LocalDate.of(2026, 11, 4), leave = Leave())
        val withLeave = pay(salary, officeWeek(wed, DayEntry(sat, extras = listOf(special)))).total - pay(salary, officeWeek(wed)).total
        assertEquals(96_000, withLeave)
        val full = pay(salary, officeWeek(DayEntry(sat, extras = listOf(special)))).total - pay(salary, officeWeek()).total
        assertEquals(144_000, full)
    }

    @Test
    fun `T7 sunday as the weekly holiday is holiday work under any rest-day rule, 192,000`() {
        val sun = LocalDate.of(2026, 11, 8)
        val work = DayEntry(sun, extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, t(9), t(20), 60, t(12))))
        val p = with(CompanyRules(restDayWork = RestDayWork.OVERTIME, weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY)))
        assertEquals(192_000, pay(p, officeWeek(work)).total - pay(p, officeWeek()).total)
        assertEquals(0, pay(p, officeWeek(work)).sum(LineKind.OVERTIME))
    }

    @Test
    fun `T8 ten hours on a saturday after a full week is all overtime, 180,000`() {
        val sat = LocalDate.of(2026, 11, 7)
        val work = DayEntry(sat, extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, t(9), t(20), 60, t(12))))
        assertEquals(180_000, pay(salary, officeWeek(work)).total - pay(salary, officeWeek()).total)
    }

    // ---- 회사 규칙은 그 건에만 ----

    @Test
    fun `T9 a fixed overtime rule leaves the shift's own overtime alone, 84,000`() {
        val nov2 = LocalDate.of(2026, 11, 2)
        val p = with(CompanyRules(extra = mapOf(ExtraKind.OVERTIME_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 30_000))))
        val result = pay(p, roster(four, DayEntry(nov2, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60)))))
        assertEquals(84_000, result.day(nov2)!!.amount)
        assertTrue(result.warnings.none { "법정 기준" in it })
    }

    @Test
    fun `T10 tiered night cover after a day shift is 282,000 and warns below the law`() {
        val nov2 = LocalDate.of(2026, 11, 2)
        val cover = ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = night.id)
        val p = with(CompanyRules(extra = mapOf(ExtraKind.COVER_ID to ExtraRule(ExtraBasis.TIERED, nightPremium = false))))
        val result = pay(p, roster(four, DayEntry(nov2, extras = listOf(cover))))
        // 주간 연장 3 × 18,000 + 대근 4 × 150% + 4 × 175% + 3 × 200% = 228,000 (법정 198,000 + 42,000 = 240,000).
        assertEquals(282_000, result.day(nov2)!!.amount)
        assertTrue(result.warnings.any { "228,000원" in it && "240,000원" in it })
    }

    @Test
    fun `T11 no night premium on overtime keeps the night shift's own night premium, 78,000`() {
        val nightShift = ShiftType(4, "나이트", "N", 0xFF000000, t(22), t(7), 60, t(2))
        val d = LocalDate.of(2026, 11, 4)
        val r = ScheduleResolver(listOf(nightShift, off), WorkPattern.Rotation(d, listOf(nightShift.id) + List(40) { off.id }),
            listOf(DayEntry(d, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)))))
        val p = with(CompanyRules(extra = mapOf(ExtraKind.OVERTIME_ID to ExtraRule(ExtraBasis.HOURLY_RATE, multiplier = 1.5, nightPremium = false))))
        assertEquals(78_000, pay(p, r).day(d)!!.amount)
    }

    @Test
    fun `T12 early start before a day shift counts its night hour, 96,000`() {
        val nov2 = LocalDate.of(2026, 11, 2)
        val kinds = ExtraKinds(ExtraKinds.DEFAULTS + ExtraKind(101, "조출", "조", 0xFF000000, ExtraTiming.BeforeShift(120)))
        val r = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(start, four),
            listOf(DayEntry(nov2, extras = listOf(ExtraEntry(101, minutes = 120)))), kinds)
        val early = r.resolve(nov2).extras.single()
        assertEquals(nov2.atTime(5, 0), early.from)
        // 연장 3 + 2 = 5시간 × 18,000 + 05~06 야간 1시간 × 6,000.
        assertEquals(96_000, pay(salary, r).day(nov2)!!.amount)
    }

    @Test
    fun `T13 extra hours of a part-timer are not scheduled hours and leave weekly holiday pay alone`() {
        // 시급 11,000원, 월~금 09~13. 수요일 18~22 대근은 100% (단시간 가산 50%는 M11 PAYS-03),
        // 주휴는 주 20시간 그대로 4시간 × 11,000 × 4주 = 176,000.
        val part = ShiftType(20, "오전", "오", 0xFF000000, t(9), t(13))
        val wed = LocalDate.of(2026, 11, 4)
        val r = ScheduleResolver(listOf(part, off), WorkPattern.Rotation(monday, List(5) { part.id } + List(2) { off.id }),
            listOf(DayEntry(wed, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(18), t(22))))))
        val p = PayProfile(Occupation.PART_TIME, WageType.HOURLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 11_000))))
        val result = PayCalculator().calculate(p, nov, r::resolve)
        assertEquals(88_000, result.day(wed)!!.amount)
        assertEquals(176_000, result.lines.single { it.label == "주휴수당" }.amount)
    }

    @Test
    fun `T14 allowances count a cover like a shift, 232,000`() {
        val items = listOf(
            PayItem("n", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 30_000))), Cadence.PER_NIGHT_SHIFT),
            PayItem("m", "식대", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_000))), Cadence.PER_WORK_DAY),
        )
        assertEquals(232_000, added(salary.copy(items = items), four, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `T15 premium-only holiday mode never touches a day off`() {
        val premiumOnly = salary.copy(holidayPayMode = HolidayPayMode.PREMIUM_ONLY)
        assertEquals(192_000, added(premiumOnly, four, DayEntry(nov3, extras = listOf(nightCover))))
        assertEquals(258_000, added(premiumOnly.copy(rules = CompanyRules(restDayWork = RestDayWork.HOLIDAY)), four, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `T16 a day off turned into a day shift keeps no hidden hours, 54,000`() {
        // 휴무일 대근 → 근무를 주간으로 바꾸고 대근을 지우면 주간 07~19의 연장 3시간만 남는다.
        val entry = DayEntry(nov3, override = ShiftOverride.Shift(day.id), extras = listOf(nightCover))
        val r = roster(four, entry)
        assertEquals(1, r.resolve(nov3).extras.size)
        val cleared = pay(salary, roster(four, entry.copy(extras = emptyList())))
        assertEquals(54_000, cleared.day(nov3)!!.amount)
    }

    @Test
    fun `T18 a 55-hour week is 3 hours past 52, 14 with a cover`() {
        val week = WorkHoursCalculator(roster(three)).week(monday)
        assertEquals(55 * 60, week.totalMinutes)
        assertEquals(3 * 60, week.overLegalMinutes)
        val withCover = WorkHoursCalculator(roster(three, DayEntry(LocalDate.of(2026, 11, 5), extras = listOf(nightCover)))).week(monday)
        assertEquals(14 * 60, withCover.overLegalMinutes)
        assertEquals(11 * 60, withCover.restDayExtraMinutes)
    }

    // ---- T19 회사 규칙: 대근 1회 기본급 10% + 연장 가산 + 야간 가산 ----

    private val tenPercent = ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = 0.1, coverMinutes = 8 * 60, legalPremiums = true)

    @Test
    fun `T19 a four-hour cover after an eight-hour day is 10 percent of base plus premiums, 298,800`() {
        // 250,800 + 연장 가산 4 × 6,000 + 야간 가산 4 × 6,000.
        val wed = LocalDate.of(2026, 11, 4)
        val r = officeWeek(DayEntry(wed, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, t(22), t(2)))))
        val result = pay(with(CompanyRules(extra = mapOf(ExtraKind.COVER_ID to tenPercent))), r)
        assertEquals(298_800, result.day(wed)!!.amount)
        assertTrue(result.warnings.none { "법정 기준" in it })
    }

    @Test
    fun `T19 a night cover on a day off pays the hours past eight at 150 percent, 346,800`() {
        // 250,800 (8시간) + 3 × 12,000 + 연장 가산 3 × 6,000 + 야간 7 × 6,000.
        assertEquals(346_800, added(with(CompanyRules(extra = mapOf(ExtraKind.COVER_ID to tenPercent))), four, DayEntry(nov3, extras = listOf(nightCover))))
        // A payment that covers every hour: 250,800 + 18,000 + 42,000.
        val all = tenPercent.copy(coverMinutes = null)
        assertEquals(310_800, added(with(CompanyRules(extra = mapOf(ExtraKind.COVER_ID to all))), four, DayEntry(nov3, extras = listOf(nightCover))))
    }

    @Test
    fun `T19 the rule reads from a sentence`() {
        val c = RuleImport.read("대근 1회 기본급의 10%, 8시간 넘으면 연장 가산 50%").single()
        assertEquals(CandidateKind.EXTRA_RULE, c.kind)
        assertEquals(ExtraKind.COVER_ID, c.extraKind)
        assertEquals(ExtraBasis.PERCENT_OF_BASE, c.extraRule!!.basis)
        assertEquals(0.1, c.extraRule!!.percentOfBase, 1e-9)
        assertTrue(c.extraRule!!.legalPremiums)
    }

    // ---- 모델 ----

    @Test
    fun `kinds survive a round trip and read the first format`() {
        val kinds = ExtraKinds(ExtraKinds.DEFAULTS + ExtraKind(101, "야간 대근", "야대", 0xFF123456,
            ExtraTiming.Band(t(19), t(7), 60, t(2)), offDay = OffDayTreatment.HOLIDAY, hidden = true))
        assertEquals(kinds, ExtraKinds.decode(kinds.encode()))
        val old = ExtraKinds.decode("OVERTIME|조출|0|60\nSPECIAL||1|600\nCOVER|대체근무|0|")
        assertEquals("조출", old.name(1))
        assertEquals(ExtraTiming.AfterShift(60), old.of(1).timing)
        assertTrue(old.of(2).hidden)
        assertEquals(600, (old.of(2).timing as ExtraTiming.Band).minutes)
        assertEquals("대체근무", old.name(3))
        assertEquals(ExtraTiming.AsShift(), old.of(3).timing)
        assertEquals("추가 근무", ExtraKinds().name(999))
    }

    @Test
    fun `old hours with no times on a day off have no place on the clock`() {
        val e = roster(four, DayEntry(nov3, extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, minutes = 480)))).resolve(nov3).extras.single()
        assertNull(e.from)
        assertTrue(e.estimated)
        assertEquals(480, e.minutes)
    }

    @Test
    fun `a cover straight after a shift shows no rest but overtime next to it does not`() {
        val nov2 = LocalDate.of(2026, 11, 2)
        val cover = roster(four, DayEntry(nov2, extras = listOf(nightCover))).resolveRange(nov2.minusDays(1), nov2.plusDays(1))
        assertTrue(RestCheck.check(cover).any { it.date == nov2 && it.kind == RestCheck.Kind.SHORT_REST })
        val ot = roster(four, DayEntry(nov2, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)))).resolveRange(nov2.minusDays(1), nov2.plusDays(1))
        assertFalse(RestCheck.check(ot).any { it.date == nov2 })
    }

    @Test
    fun `changes name the extra work`() {
        val d = DayEntry(nov3)
        assertEquals("대근 19:00–07:00", DayChanges.describe(d, d.copy(extras = listOf(nightCover)), { "" }))
        assertEquals("대근 지움, 알람 끔", DayChanges.describe(d.copy(extras = listOf(nightCover)), d.copy(alarmSkipped = true), { "" }))
    }
}
