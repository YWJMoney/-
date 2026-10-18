package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
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
 * [PAYS-03] M11 pay rules worked out by hand, after T1–T19 in [ExtraWorkGoldenTest]. T20 is
 * docs/pay-golden-cases.md 예시 11-2; the rest is arithmetic from the law (근로기준법 제50·54·55·56·63조,
 * 시행령 제6조 제2항), not a published example. Not a substitute for a 공인노무사.
 */
class M11PayGoldenTest {
    private val nov = YearMonth.of(2026, 11)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun wage(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), v)))

    private fun type(id: Long, start: LocalTime, end: LocalTime, breakMinutes: Int = 0, breakStart: LocalTime? = null) =
        ShiftType(id, "근무$id", "$id", 0xFF000000, start, end, breakMinutes, breakStart)
    private val off = ShiftType(99, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)

    /** [shift] on [date] only, every other day of the month off. */
    private fun oneDay(shift: ShiftType, date: LocalDate, vararg e: DayEntry) =
        ScheduleResolver(listOf(shift, off), WorkPattern.Rotation(date, listOf(shift.id) + List(60) { off.id }), e.toList())

    // 사무직 월~금 09~18 (휴게 12~13) from Monday 11/2.
    private val office = ShiftType(10, "사무", "사", 0xFF1E88E5, t(9), t(18), 60, t(12))
    private val monday = LocalDate.of(2026, 11, 2)
    private fun officeWeek(vararg e: DayEntry) =
        ScheduleResolver(listOf(office, off), WorkPattern.Rotation(monday, List(5) { office.id } + List(2) { off.id }), e.toList())

    // 4조2교대 주주휴휴야야휴휴, step 0 on Sunday 11/1: 주간 07~19 (휴게 12~13), 야간 19~07 (휴게 02~03).
    private val dayShift = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
    private val nightShift = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
    private val four = listOf(dayShift.id, dayShift.id, off.id, off.id, nightShift.id, nightShift.id, off.id, off.id)
    private fun roster(vararg e: DayEntry) = ScheduleResolver(listOf(dayShift, nightShift, off),
        WorkPattern.Rotation(LocalDate.of(2026, 9, 1), four, anchorDate = LocalDate.of(2026, 11, 1)), e.toList())

    private fun profile(type: WageType, base: Long, fiveOrMore: Boolean = true) = PayProfile(
        occupation = Occupation.CUSTOM, wageType = type, base = wage(base), fiveOrMoreEmployees = fiveOrMore,
    )

    /** 통상시급 12,000원: 2,508,000원 ÷ 209. */
    private val salary = profile(WageType.MONTHLY, 2_508_000)

    private fun pay(p: PayProfile, r: ScheduleResolver, month: YearMonth = nov) = PayCalculator().calculate(p, month, r::resolve)
    private fun MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }

    // ---- 감시·단속적 근로자 (제63조 제3호) ----

    /** 24시간 격일제 09:00~익일 09:00, 휴게 8시간 18:00~02:00 (그중 22~02시 4시간이 야간대). */
    private val guard = type(30, t(9), t(9), 480, t(18))
    private val approved = CompanyRules(supervisoryApproved = true)

    @Test
    fun `T20 a 24-hour guard day is 192,600 with supervisory approval and 235,400 without`() {
        // 예시 11-2: 시급 10,700원, 실근로 16시간, 야간대 실근로 8 − 4 = 4시간. 2027-01-06 (수), 그 하루만 근무.
        val jan = YearMonth.of(2027, 1)
        val d = LocalDate.of(2027, 1, 6)
        val p = profile(WageType.HOURLY, 10_700)
        val r = oneDay(guard, d)
        // 승인 있음: 16h × 10,700 + 야간 4h × 5,350. 8시간 한도도 연장 가산도 없음.
        val yes = pay(p.copy(rules = approved), r, jan)
        assertEquals(960, yes.minutes.single { it.date == d }.regular)
        assertEquals(240, yes.minutes.single { it.date == d }.night)
        assertEquals(0, yes.sum(LineKind.OVERTIME))
        assertEquals(21_400, yes.sum(LineKind.NIGHT))
        assertEquals(192_600, yes.day(d)!!.amount)
        assertEquals(192_600, yes.total)
        // 승인 없음 (5인 이상): 소정 8h × 10,700 + 연장 8h × 16,050 + 야간 4h × 5,350.
        val no = pay(p, r, jan)
        assertEquals(128_400, no.sum(LineKind.OVERTIME))
        assertEquals(235_400, no.day(d)!!.amount)
        assertEquals(235_400, no.total)
        // One more run without the approval gives the figure to check it against.
        assertTrue(yes.warnings.any { "235,400원" in it && "고용노동부" in it })
        assertTrue(no.warnings.none { "감시·단속" in it })
    }

    @Test
    fun `T20 an approved guard keeps the night premium and loses weekly holiday pay and premiums over a week`() {
        // 격일제 한 달 (2027-01-04 월 시작): 승인이 있으면 주휴도 연장 가산도 없고 근무 1시간마다 100%, 야간 가산만 더해요.
        val jan = YearMonth.of(2027, 1)
        val r = ScheduleResolver(listOf(guard, off), WorkPattern.Rotation(LocalDate.of(2027, 1, 4), listOf(guard.id, off.id)), emptyList())
        val p = profile(WageType.HOURLY, 10_700).copy(rules = approved)
        val result = pay(p, r, jan)
        val shifts = (4..31 step 2).count()
        assertEquals(14, shifts)
        assertEquals(shifts * 192_600L, result.total)
        assertTrue(result.lines.none { it.label == "주휴수당" })
        assertEquals(0, result.sum(LineKind.OVERTIME))
    }

    @Test
    fun `T20 Labour Day stays a paid holiday for an approved guard, without the premium`() {
        // 2026-05-01 (금) 근무, 시급 10,320원: 유급휴일 8시간 + 근무 16시간 모두 100% + 야간 가산 4시간 (확인 필요).
        val mayDay = LocalDate.of(2026, 5, 1)
        val result = pay(profile(WageType.HOURLY, 10_320).copy(rules = approved), oneDay(guard, mayDay), YearMonth.of(2026, 5))
        assertEquals(82_560, result.lines.single { it.label == "유급휴일수당 (공휴일)" }.amount)
        assertTrue(result.lines.filter { it.kind == LineKind.HOLIDAY }.all { it.multiplier == 1.0 })
        assertEquals(165_120 + 20_640 + 82_560, result.day(mayDay)!!.amount)
    }

    // ---- 5인 미만 (제11조, 시행령 별표1) ----

    @Test
    fun `T21 under five employees the company's own 50 percent is paid, 72,000 instead of 48,000`() {
        // 14~22시 근무 뒤 잔업 4시간 (22~02시). 법정 가산 없음: 4 × 12,000. 회사 연장·야간 50%: 4 × 18,000 + 4 × 6,000.
        val d = LocalDate.of(2026, 11, 4)
        val small = salary.copy(fiveOrMoreEmployees = false)
        val r = oneDay(type(21, t(14), t(22)), d, DayEntry(d, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 240))))
        val law = pay(small, r)
        assertEquals(48_000, law.sum(LineKind.OVERTIME))
        assertEquals(0, law.sum(LineKind.NIGHT))
        val company = pay(small.copy(rules = CompanyRules(overtimePremium = 0.5, nightPremium = 0.5)), r)
        assertEquals(72_000, company.sum(LineKind.OVERTIME))
        assertEquals(24_000, company.sum(LineKind.NIGHT))
        assertEquals(96_000, company.day(d)!!.amount)
    }

    private val hangul = LocalDate.of(2026, 10, 9) // 한글날, Friday
    private val oct = YearMonth.of(2026, 10)

    @Test
    fun `T22 under five a company that grants public holidays pays them as paid holidays, without the legal premium`() {
        val r = oneDay(type(25, t(9), t(17)), hangul)
        // 시급 12,000원. 법으로는 평일이라 8 × 12,000.
        val hourly = profile(WageType.HOURLY, 12_000, fiveOrMore = false)
        val plain = pay(hourly, r, oct)
        assertEquals(96_000, plain.total)
        assertTrue(plain.lines.none { it.label == "유급휴일수당 (공휴일)" })
        // 회사가 공휴일을 주면 유급휴일 8 × 12,000에 일한 8시간 × 100%.
        val granted = pay(hourly.copy(rules = CompanyRules(holidaysUnderFive = true)), r, oct)
        assertEquals(96_000, granted.lines.single { it.label == "유급휴일수당 (공휴일)" }.amount)
        assertEquals(96_000, granted.sum(LineKind.HOLIDAY))
        assertEquals(192_000, granted.total)
        // 월급제: 일한 8시간 × 100%가 월급 위에 더해지고, 회사 휴일 가산 50%가 있으면 150%.
        val monthly = salary.copy(fiveOrMoreEmployees = false)
        assertEquals(0, pay(monthly, r, oct).sum(LineKind.HOLIDAY))
        assertEquals(96_000, pay(monthly.copy(rules = CompanyRules(holidaysUnderFive = true)), r, oct).sum(LineKind.HOLIDAY))
        val premium = CompanyRules(holidaysUnderFive = true, holidayPremiumWithin8h = 0.5)
        assertEquals(144_000, pay(monthly.copy(rules = premium), r, oct).sum(LineKind.HOLIDAY))
        // At 5인 이상 the setting does nothing: publicHolidaysPaid decides.
        val swapped = CompanyRules(publicHolidaysPaid = false)
        assertEquals(pay(salary.copy(rules = swapped), r, oct).total, pay(salary.copy(rules = swapped.copy(holidaysUnderFive = true)), r, oct).total)
    }

    // ---- 휴게 (제54조, 제50조 제3항) ----

    @Test
    fun `T23 breaks counted as work make 12-hour shifts 12 hours of work, 336,000 more in November`() {
        // 4조2교대, 통상시급 12,000원. 무급 휴게: 주간 연장 3 × 18,000; 야간 연장 3 × 18,000 + 야간 7 × 6,000.
        // 근무시간으로 치면: 주간 연장 4 × 18,000; 야간 연장 4 × 18,000 + 야간 8 × 6,000.
        val unpaid = pay(salary, roster())
        val asWork = pay(salary.copy(rules = CompanyRules(breakPay = BreakPay.AS_WORK)), roster())
        val nov2 = LocalDate.of(2026, 11, 2)
        val nov5 = LocalDate.of(2026, 11, 5)
        assertEquals(54_000, unpaid.day(nov2)!!.amount)
        assertEquals(72_000, asWork.day(nov2)!!.amount)
        assertEquals(96_000, unpaid.day(nov5)!!.amount)
        assertEquals(120_000, asWork.day(nov5)!!.amount)
        // 근무 16번 × 연장 1시간 18,000 + 야간 8번 × 야간 1시간 6,000.
        assertEquals(336_000, asWork.total - unpaid.total)
    }

    @Test
    fun `T24 a paid break is paid at 100 percent but never makes overtime, 108,000 a day`() {
        // 시급 12,000원, 월~금 09~18 (휴게 12~13 유급): 8 × 12,000 + 휴게 1 × 12,000. 주휴는 주 40시간 그대로.
        val hourly = profile(WageType.HOURLY, 12_000)
        val unpaid = pay(hourly, officeWeek())
        val paid = pay(hourly.copy(rules = CompanyRules(breakPay = BreakPay.PAID)), officeWeek())
        assertEquals(108_000, paid.day(monday)!!.amount)
        assertEquals(21 * 12_000L, paid.lines.single { it.label == "유급 휴게" }.amount)
        assertEquals(21 * 12_000L, paid.total - unpaid.total)
        assertEquals(0, paid.sum(LineKind.OVERTIME))
        assertEquals(unpaid.lines.single { it.label == "주휴수당" }.amount, paid.lines.single { it.label == "주휴수당" }.amount)
        // A monthly salary already pays its breaks.
        val monthly = pay(salary.copy(rules = CompanyRules(breakPay = BreakPay.PAID)), officeWeek())
        assertEquals(pay(salary, officeWeek()).total, monthly.total)
        assertTrue(monthly.lines.none { it.label == "유급 휴게" })
    }
}
