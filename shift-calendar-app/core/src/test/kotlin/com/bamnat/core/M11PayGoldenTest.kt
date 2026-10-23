package com.bamnat.core

import com.bamnat.core.pay.WeeklyHoliday
import java.time.DayOfWeek
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.AnnualPay
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MissingItem
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayRange
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.Unknowns
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
 * [PAYS-01][PAYS-03] M11 pay rules worked out by hand, after T1–T19 in [ExtraWorkGoldenTest]. T20 is
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

    // ---- 일당 (시행령 제6조 제2항 제2호) ----

    @Test
    fun `T25 a day rate of 150,000 is 18,750 an hour, and weekly holiday pay is one day's rate`() {
        val daily = profile(WageType.DAILY, 150_000)
        val tue = LocalDate.of(2026, 11, 3)
        val result = pay(daily, officeWeek(DayEntry(tue, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)))))
        assertEquals(18_750, result.ordinaryHourly)
        // 11/2~11/30 평일 21일.
        val base = result.lines.single { it.kind == LineKind.BASE }
        assertEquals(21 * 150_000L, base.amount)
        assertTrue(base.formula, base.formula.startsWith("일당 150,000원 × 21일"))
        assertTrue(base.basis, "일당 150,000원 ÷ 8시간 = 시급 18,750원" in base.basis)
        assertEquals(150_000, result.day(monday)!!.amount)
        assertEquals("일당 150,000원", result.day(monday)!!.parts.single().formula)
        // 주 40시간 → 주휴 8시간 = 일당 하루, 11월에 끝나는 4주.
        assertEquals(4 * 150_000L, result.lines.single { it.label == "주휴수당" }.amount)
        // 10시간 일한 날: 연장 2 × 18,750 × 150% = 56,250.
        assertEquals(56_250, result.sum(LineKind.OVERTIME))
        assertEquals(206_250, result.day(tue)!!.amount)
    }

    @Test
    fun `T25 a day rate for 6 hours pays its share for a shorter day`() {
        // 일당 90,000원, 1일 소정 6시간 → 시급 15,000원. 4시간 근무한 날은 90,000 × 4 ÷ 6.
        val six = profile(WageType.DAILY, 90_000).copy(dailyHours = 6 * 60)
        val d = LocalDate.of(2026, 11, 4)
        val result = pay(six, oneDay(type(26, t(9), t(13)), d))
        assertEquals(15_000, result.ordinaryHourly)
        assertEquals(60_000, result.day(d)!!.amount)
        assertEquals("일당 90,000원 × 4시간 ÷ 6시간", result.day(d)!!.parts.single().formula)
        assertTrue(result.lines.single { it.kind == LineKind.BASE }.formula.startsWith("일당 90,000원 × 4시간 ÷ 6시간"))
    }

    // ---- 연봉 ----

    @Test
    fun `T26 an annual salary divides into the monthly base`() {
        val plain = AnnualPay(36_000_000).breakdown()
        assertEquals(3_000_000, plain.monthlyBase)
        assertEquals(listOf("연봉 36,000,000원 ÷ 12 = 기본급 3,000,000원"), plain.lines)
        assertTrue(plain.warnings.isEmpty())
        // (연봉 − 정기 상여) ÷ 12 − 고정 연장수당 = (연봉 − 고정 연장수당 × 12 − 정기 상여) ÷ 12.
        val parts = AnnualPay(54_000_000, fixedOvertimeMonthly = 500_000, regularBonusYearly = 12_000_000).breakdown()
        assertEquals(3_500_000, parts.monthlyShare)
        assertEquals(3_000_000, parts.monthlyBase)
        assertEquals(
            listOf(
                "연봉 54,000,000원 − 정기 상여 12,000,000원 = 42,000,000원",
                "42,000,000원 ÷ 12 = 월 3,500,000원",
                "월 3,500,000원 − 고정 연장수당 500,000원 = 기본급 3,000,000원",
            ),
            parts.lines,
        )
        // 13분할: the 13th share may be 퇴직금 folded into pay.
        val thirteen = AnnualPay(39_000_000, split = 13).breakdown()
        assertEquals(3_000_000, thirteen.monthlyBase)
        assertTrue(thirteen.warnings.single().let { "퇴직금" in it && "노무사" in it && "1350" in it })
        // Allowances bigger than the month's share leave no 기본급 and say so.
        val over = AnnualPay(12_000_000, otherMonthly = 1_500_000).breakdown()
        assertEquals(0, over.monthlyBase)
        assertEquals(1, over.warnings.size)
        // The 연봉 rides along to show how the 기본급 was reached; the month is still worked out from the 기본급.
        assertEquals(pay(salary, officeWeek()).total, pay(salary.copy(annual = AnnualPay(30_096_000)), officeWeek()).total)
    }

    // ---- 가산을 대신하는 수당 ----

    private fun nightAllowance(each: Long, covers: Set<LineKind>?) =
        salary.copy(items = listOf(PayItem("n", "야간근무수당", wage(each), Cadence.PER_NIGHT_SHIFT, covers = covers)))

    @Test
    fun `T27 a night allowance paid instead of the night premium gives the larger of the two`() {
        // 4조2교대 11월 야간 8번: 법정 야간 가산 8 × 7시간 × 6,000 = 336,000.
        val plain = pay(salary, roster())
        assertEquals(336_000, plain.sum(LineKind.NIGHT))
        // 따로 받는 수당 (기본값): 1회 50,000 × 8 more.
        assertEquals(plain.total + 400_000, pay(nightAllowance(50_000, null), roster()).total)
        // 야간 가산 대신: 400,000 > 336,000이라 수당만.
        val instead = pay(nightAllowance(50_000, setOf(LineKind.NIGHT)), roster())
        assertEquals(plain.total + 400_000 - 336_000, instead.total)
        val offset = instead.lines.single { it.amount < 0 }
        assertEquals(LineKind.NIGHT, offset.kind)
        assertEquals("야간근무수당으로 이미 받은 몫", offset.label)
        assertEquals("야간수당 336,000원 중 야간근무수당 400,000원만큼", offset.formula)
        assertTrue(instead.warnings.none { "차액" in it })
        // 240,000 < 336,000: the law's 336,000, and the 96,000 short is flagged.
        val short = pay(nightAllowance(30_000, setOf(LineKind.NIGHT)), roster())
        assertEquals(plain.total, short.total)
        assertTrue(short.warnings.contains("이번 달 야간수당이 336,000원으로 야간근무수당 240,000원보다 96,000원 많아요. 차액은 따로 받아야 해요"))
    }

    @Test
    fun `T28 a fixed overtime allowance that also covers night work, 84,000 short`() {
        // 사무직, 잔업 18~23시 4번 (한 주): 연장 20 × 18,000 = 360,000, 야간 4 × 6,000 = 24,000.
        val late = (2..5).map { DayEntry(LocalDate.of(2026, 11, it), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, t(18), t(23)))) }
        val r = officeWeek(*late.toTypedArray())
        fun fixed(amount: Long, covers: Set<LineKind>?) = salary.copy(items = listOf(
            PayItem("f", "고정 연장수당", wage(amount), Cadence.MONTHLY, fixedOvertimeMinutes = 20 * 60, covers = covers),
        ))
        val both = setOf(LineKind.OVERTIME, LineKind.NIGHT)
        val plain = pay(salary, r)
        assertEquals(360_000, plain.sum(LineKind.OVERTIME))
        assertEquals(24_000, plain.sum(LineKind.NIGHT))
        // 300,000원이 연장·야간을 대신하면 법정 384,000원이 더 많아서 그대로, 84,000원 차액.
        val short = pay(fixed(300_000, both), r)
        assertEquals(plain.total, short.total)
        assertTrue(short.warnings.contains("이번 달 연장·야간수당이 384,000원으로 고정 연장수당 300,000원보다 84,000원 많아요. 차액은 따로 받아야 해요"))
        assertEquals("연장·야간수당 384,000원 중 고정 연장수당 300,000원만큼", short.lines.single { it.amount < 0 }.formula)
        // 연장만 대신하는 기본값은 지금까지와 같아요: 차액은 연장 60,000원, 야간 가산은 따로.
        val otOnly = pay(fixed(300_000, null), r)
        assertEquals(plain.total, otOnly.total)
        assertTrue(otOnly.warnings.contains("이번 달 연장수당이 360,000원으로 고정 연장수당 300,000원보다 60,000원 많아요. 차액은 따로 받아야 해요"))
        assertEquals("고정 연장수당으로 이미 받은 몫", otOnly.lines.single { it.amount < 0 }.label)
        // 400,000원: 연장·야간을 모두 대신하면 야간 가산 24,000원도 그 안에 들어가요.
        assertEquals(plain.total + 16_000, pay(fixed(400_000, both), r).total)
        assertEquals(plain.total + 40_000, pay(fixed(400_000, null), r).total)
    }

    // ---- 금액 모름 ----

    @Test
    fun `T29 an allowance with an unknown amount is listed, not added`() {
        val items = listOf(
            PayItem("n", "야간근무수당", wage(30_000), Cadence.PER_NIGHT_SHIFT),
            PayItem("m", "직책수당", wage(100_000), Cadence.MONTHLY),
            PayItem("b", "명절상여", wage(0), Cadence.BONUS, months = setOf(11), percentOfBase = 0.5),
        )
        val known = salary.copy(items = items)
        assertEquals(pay(salary, roster()).total + 240_000 + 100_000 + 1_254_000, pay(known, roster()).total)
        val unknown = listOf("n", "m", "b").fold(known) { p, id -> Unknowns.markUnknown(p, Unknowns.item(id)) }
        val result = pay(unknown, roster())
        assertEquals(pay(salary, roster()).total, result.total)
        assertEquals(
            listOf(MissingItem("n", "야간근무수당", 8), MissingItem("m", "직책수당", null), MissingItem("b", "명절상여", 1)),
            result.missing,
        )
        // Nothing is missing in a month the allowance is not paid.
        assertTrue(pay(unknown, roster(), YearMonth.of(2026, 12)).missing.none { it.id == "b" })
        // Entering the amount answers the key and the allowance is paid again.
        val entered = Unknowns.answered(unknown.copy(items = items), Unknowns.item("m"))
        assertTrue(pay(entered, roster()).missing.none { it.id == "m" })
        assertEquals(100_000, pay(entered, roster()).lines.single { it.label == "직책수당" }.amount)
    }

    // ---- 모르겠어요의 범위 ----

    @Test
    fun `T30 the range holds the month for every combination of answers`() {
        // 4조2교대 월급제 10월: 10/5 대체공휴일·10/9 한글날 근무, 10/14 휴무일 야간 대근.
        val cover = ExtraEntry(ExtraKind.COVER_ID, t(19), t(7), 60, t(2), coveredShiftId = nightShift.id)
        val r = roster(DayEntry(LocalDate.of(2026, 10, 14), extras = listOf(cover)))
        val keys = listOf(Unknowns.FIVE_OR_MORE, Unknowns.SUPERVISORY, Unknowns.SCHEDULE_IS_REGULAR, Unknowns.PUBLIC_HOLIDAYS,
            Unknowns.REST_DAY_WORK, Unknowns.BREAK_PAY, Unknowns.MONTHLY_HOURS)
        val asked = keys.fold(salary) { p, key -> Unknowns.markUnknown(p, key) }
        val range = PayRange.of(asked, oct, r::resolve)
        val calc = PayCalculator()
        assertEquals(calc.calculate(asked, oct, r::resolve, checks = false).total, range.total)
        // [PAYX-23] The 주휴일 is not set either, so it comes with 쉬는 날 근무.
        assertEquals(keys.toMutableList().apply { add(indexOf(Unknowns.REST_DAY_WORK) + 1, Unknowns.WEEKLY_HOLIDAY) }, range.effects.map { it.key })
        assertTrue(range.low < range.total && range.total < range.high)
        // 월 소정근로시간: the usual values and the roster's (주 28시간 → 146시간).
        val hours = range.effects.single { it.key == Unknowns.MONTHLY_HOURS }.unknown.answers.map { it.label }
        assertEquals(listOf("226시간", "243시간", "146시간"), hours)

        val totals = mutableListOf<Long>()
        for (five in listOf(true, false)) for (holidays in listOf(true, false)) for (supervisory in listOf(false, true))
            for (regular in listOf(false, true)) for (restDay in RestDayWork.entries) for (breaks in BreakPay.entries)
                for (monthlyHours in listOf(209, 226, 243, 146)) for (weekly in listOf<WeeklyHoliday>(WeeklyHoliday.Unknown, WeeklyHoliday.Fixed(DayOfWeek.SUNDAY))) {
                    // [PAYX-23] 주휴일 not set: the 쉬는 날 근무 question also runs 일요일.
                    val rules = asked.rules.copy(weeklyHoliday = weekly,
                        supervisoryApproved = supervisory, restDayWork = restDay, breakPay = breaks,
                        // 공휴일: 휴일 대체 or not at 5인 이상, given by the company or not below that.
                        publicHolidaysPaid = !five || holidays, holidaysUnderFive = !five && !holidays,
                    )
                    val truth = asked.copy(fiveOrMoreEmployees = five, scheduleIsRegular = regular, monthlyHours = monthlyHours, rules = rules)
                    val total = calc.calculate(truth, oct, r::resolve, checks = false).total
                    assertTrue("$five $holidays $supervisory $regular $restDay $breaks $monthlyHours $weekly: $total not in ${range.low}..${range.high}",
                        total in range.low..range.high)
                    totals += total
                }
        assertEquals(2 * 2 * 2 * 2 * 3 * 3 * 4 * 2, totals.size)
        // And no wider than it has to be: the lowest and the highest answers are the bounds.
        assertEquals(totals.min(), range.low)
        assertEquals(totals.max(), range.high)
        // Each other answer once, then the lows together and the highs together.
        assertEquals(1 + range.effects.sumOf { it.unknown.answers.size } + 2, range.runs)
        // Nothing unknown: one exact figure.
        assertTrue(PayRange.of(salary, oct, r::resolve).exact)
    }
}
