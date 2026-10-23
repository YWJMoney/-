package com.bamnat.core

import com.bamnat.core.hours.HourLimits
import com.bamnat.core.hours.HoursException
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.hours.shortRests
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.BonusBasis
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayExamples
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ProrationBasis
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.pay.WonRounding
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [PAYX-24]~[PAYX-33] 순번 307: docs/pay-golden-cases.md 예시 13-3~13-10 (G3-1~G3-8) and the 52시간 예외. */
class PayGolden3Test {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)
    private fun money(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2020, 1, 1), v)))
    private val nov = YearMonth.of(2026, 11)
    private val day = ShiftType(10, "주간", "주", 0xFF000000, t(9), t(18), 60, t(12))
    private val half = ShiftType(12, "오전", "오", 0xFF000000, t(9), t(13), 0)
    private val night = ShiftType(13, "야간", "야", 0xFF000000, t(22), t(7), 60, t(2), breakPaid = true)
    private val off = ShiftType(11, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private fun weekdays(shift: ShiftType, entries: List<DayEntry> = emptyList()) = ScheduleResolver(listOf(shift, night, off),
        listOf(WorkPattern.Rotation(LocalDate.of(2026, 10, 26), List(5) { shift.id } + List(2) { off.id })), entries)
    private fun monthly(base: Long = 3_000_000, rules: CompanyRules = CompanyRules()) =
        PayProfile(Occupation.CUSTOM, WageType.MONTHLY, money(base), rules = rules)
    private fun hourly(rules: CompanyRules = CompanyRules()) = PayProfile(Occupation.CUSTOM, WageType.HOURLY, money(10_320), rules = rules)
    private fun MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }
    private fun MonthPay.base() = lines.single { it.label == "기본급" }.amount

    @Test
    fun `G3-1 the month of joining or leaving is cut by the company's way`() {
        val r = weekdays(day)
        fun pay(p: PayProfile) = PayCalculator().calculate(p, nov, r::resolve)
        val join = monthly().copy(hireDate = d(16))
        assertEquals(1_500_000, pay(join).base())
        assertEquals(1_571_429, pay(join.copy(rules = CompanyRules(proration = ProrationBasis.WORK_DAYS))).base())
        assertEquals(3_000_000, pay(join.copy(rules = CompanyRules(proration = ProrationBasis.NONE))).base())
        val leave = monthly().copy(hireDate = LocalDate.of(2024, 3, 1), leaveDate = d(13))
        assertEquals(1_300_000, pay(leave).base())
        assertEquals(1_428_571, pay(leave.copy(rules = CompanyRules(proration = ProrationBasis.WORK_DAYS))).base())
        // A whole month employed is not cut; a month after leaving pays nothing.
        assertEquals(3_000_000, pay(monthly().copy(hireDate = LocalDate.of(2024, 3, 1))).base())
        assertEquals(0, PayCalculator().calculate(leave, YearMonth.of(2026, 12), r::resolve).base())
        // A monthly allowance is cut the same way, and the line says how and that it is the company's rule.
        val withItem = join.copy(items = listOf(PayItem("pos", "직책수당", money(300_000), Cadence.MONTHLY)))
        val line = pay(withItem).lines.single { it.label == "직책수당" }
        assertEquals(150_000, line.amount)
        assertTrue("회사 규정" in line.basis)
        // 확인 필요 only while the company's way is not known.
        assertFalse(pay(join).lines.single { it.label == "기본급" }.estimated)
        assertTrue(pay(join.copy(estimatedFields = setOf(Unknowns.PRORATION))).lines.single { it.label == "기본급" }.estimated)
        // An hourly worker is not paid for the roster before joining.
        val h = PayCalculator().calculate(hourly().copy(hireDate = d(16)), nov, r::resolve)
        assertEquals(11 * 8 * 60, h.minutes.sumOf { it.regular })
    }

    @Test
    fun `G3-2 wons are cut on the hourly rate first or at the end of each line`() {
        val ot = (2..6).map { DayEntry(d(it), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))) }
        val r = weekdays(day, ot)
        fun overtime(p: PayProfile) = PayCalculator().calculate(p, nov, r::resolve).sum(LineKind.OVERTIME)
        assertEquals(215_310, overtime(monthly()))
        assertEquals(215_311, overtime(monthly(rules = CompanyRules(wonRounding = WonRounding.AT_END))))
    }

    @Test
    fun `G3-3 a part-timer's hours past the roster earn 50 percent when the company rule is on`() {
        val r = weekdays(half, listOf(DayEntry(d(3), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120)))))
        val plain = PayCalculator().calculate(hourly(), nov, r::resolve)
        assertEquals(20_640, plain.sum(LineKind.EXTRA_HOURS))
        assertTrue(plain.warnings.any { "단시간 근로자일 수 있어요" in it })
        val on = PayCalculator().calculate(hourly(CompanyRules(partTimePremium = true)), nov, r::resolve)
        assertEquals(30_960, on.sum(LineKind.EXTRA_HOURS))
        assertTrue(on.warnings.none { "단시간 근로자일 수 있어요" in it })
        // Under 5 employees the 기간제법 does not apply.
        assertEquals(20_640, PayCalculator().calculate(hourly(CompanyRules(partTimePremium = true)).copy(fiveOrMoreEmployees = false), nov, r::resolve)
            .sum(LineKind.EXTRA_HOURS))
    }

    @Test
    fun `G3-4 holiday work swapped in advance is a working day's`() {
        val sunday = ExtraEntry(ExtraKind.SPECIAL_ID, start = t(9), end = t(18), breakMinutes = 60, breakStart = t(12))
        val rules = CompanyRules(weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY))
        fun pay(e: ExtraEntry) = PayCalculator().calculate(hourly(rules), nov,
            weekdays(day, listOf(DayEntry(d(4), override = ShiftOverride.Off), DayEntry(d(8), extras = listOf(e))))::resolve)
        val plain = pay(sunday)
        assertEquals(123_840, plain.sum(LineKind.HOLIDAY))
        val swapped = pay(sunday.copy(substitute = d(4)))
        assertEquals(0, swapped.sum(LineKind.HOLIDAY))
        assertEquals(82_560, swapped.sum(LineKind.EXTRA_HOURS))
        assertTrue(swapped.warnings.any { "11/4과 휴일을 바꿔" in it })
        // Too far apart: the week may lose its day off.
        val far = pay(sunday.copy(substitute = d(20)))
        assertTrue(far.warnings.any { "12일 떨어져" in it })
    }

    @Test
    fun `G3-4 노동절 is never swapped`() {
        val r = ScheduleResolver(listOf(day, off), listOf(WorkPattern.Rotation(LocalDate.of(2026, 4, 27), List(5) { day.id } + List(2) { off.id })),
            listOf(DayEntry(LocalDate.of(2026, 5, 1), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60, substitute = LocalDate.of(2026, 5, 6))))))
        val pay = PayCalculator().calculate(hourly(), YearMonth.of(2026, 5), r::resolve)
        assertTrue(pay.lines.any { it.kind == LineKind.HOLIDAY && it.amount > 0 })
        assertTrue(pay.warnings.none { "휴일을 바꿔" in it })
    }

    @Test
    fun `G3-5 a bonus is a percent of the base the company names`() {
        val items = listOf(
            PayItem("pos", "직책수당", money(200_000), Cadence.MONTHLY, ordinary = true),
            PayItem("meal", "식대", money(200_000), Cadence.MONTHLY, taxable = false),
        )
        fun bonus(basis: BonusBasis): Long {
            val b = PayItem("b", "연말상여", money(0), Cadence.BONUS, months = setOf(12), percentOfBase = 1.0, bonusBasis = basis)
            val p = monthly(2_000_000).copy(items = items + b)
            return PayCalculator().calculate(p, YearMonth.of(2026, 12), weekdays(day)::resolve).lines.single { it.label == "연말상여" }.amount
        }
        assertEquals(2_000_000, bonus(BonusBasis.BASE))
        assertEquals(2_400_000, bonus(BonusBasis.BASE_FIXED))
        assertEquals(2_200_000, bonus(BonusBasis.ORDINARY))
    }

    @Test
    fun `G3-6 an inclusive salary splits into base and fixed overtime, both estimated`() {
        val split = PayExamples.apply(PayExamples.Kind.INCLUSIVE_TOTAL, monthly(), LocalDate.of(2026, 11, 1), overtimeHours = 20)
        assertEquals(2_623_431, split.base.on(nov).value)
        val item = split.items.single { it.id == PayExamples.INCLUSIVE_ID }
        assertEquals(376_569, item.amount.on(nov).value)
        assertEquals(20 * 60, item.fixedOvertimeMinutes)
        assertTrue(Unknowns.BASE in split.estimatedFields && item.id in split.estimatedFields)
        // Hourly pay has nothing to split.
        assertEquals(hourly(), PayExamples.inclusiveSplit(hourly(), LocalDate.of(2026, 11, 1), 20))
    }

    @Test
    fun `G3-7 a production worker's extra-work pay is tax free within the limits`() {
        fun pay(base: Long) = MonthPay(nov, listOf(PayLine(LineKind.BASE, "기본급", base, ""), PayLine(LineKind.OVERTIME, "연장근로", 300_000, "")),
            0, "", emptyList(), emptyList())
        val worker = monthly(2_000_000).copy(productionWorker = true)
        assertEquals(300_000, TakeHome.productionFree(worker, pay(2_000_000)).amount)
        assertEquals(150_000, TakeHome.productionFree(worker, pay(2_000_000), usedThisYear = 2_250_000).amount)
        assertEquals(0, TakeHome.productionFree(worker, pay(2_700_000)).amount)
        assertEquals(0, TakeHome.productionFree(worker.copy(previousYearPay = 38_000_000), pay(2_000_000)).amount)
        assertEquals(0, TakeHome.productionFree(monthly(2_000_000), pay(2_000_000)).amount)
        // It comes off the taxed pay with the 식대.
        assertEquals(300_000, TakeHome.nonTaxable(worker, nov, pay(2_000_000)))
        assertTrue("시행령 제17조" in TakeHome.productionFree(worker, pay(2_000_000)).note)
    }

    @Test
    fun `G3-8 one kind of day can pay its break while the company's are unpaid`() {
        val r = weekdays(day, listOf(DayEntry(d(3), override = ShiftOverride.Shift(night.id))))
        val pay = PayCalculator().calculate(hourly(CompanyRules(breakPay = BreakPay.UNPAID)), nov, r::resolve)
        assertEquals(10_320, pay.lines.single { it.label == "유급 휴게" }.amount)
        // A kind marked unpaid keeps its break free even where the company pays breaks.
        val unpaid = ScheduleResolver(listOf(day.copy(breakPaid = false), off),
            listOf(WorkPattern.Rotation(LocalDate.of(2026, 10, 26), List(5) { day.id } + List(2) { off.id })), emptyList())
        assertTrue(PayCalculator().calculate(hourly(CompanyRules(breakPay = BreakPay.PAID)), nov, unpaid::resolve).lines.none { it.label == "유급 휴게" })
    }

    @Test
    fun `P12 public servants have Saturday and Sunday as holidays for the holiday allowance`() {
        val sat = DayEntry(d(7), override = ShiftOverride.Shift(day.id))
        val p = com.bamnat.core.pay.PayPresets.firstSetup(PayProfile(Occupation.PUBLIC, WageType.MONTHLY, money(3_000_000)))
        val pay = PayCalculator().calculate(p, nov, weekdays(day, listOf(sat))::resolve)
        assertTrue(pay.lines.any { it.label.contains("휴일근무") && it.amount > 0 })
    }

    @Test
    fun `P5 a fixed overtime allowance is said to cover overtime only when holiday work is paid`() {
        val sunday = DayEntry(d(8), extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, start = t(9), end = t(18), breakMinutes = 60, breakStart = t(12))))
        val p = monthly(rules = CompanyRules(weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY))).copy(items = listOf(
            PayItem("fot", "고정 연장수당", money(200_000), Cadence.MONTHLY, fixedOvertimeMinutes = 10 * 60)))
        val w = PayCalculator().calculate(p, nov, weekdays(day, listOf(sunday))::resolve).warnings
        assertTrue(w.any { "고정 연장수당은 연장근로만" in it })
        val covered = p.copy(items = p.items.map { it.copy(covers = setOf(LineKind.OVERTIME, LineKind.HOLIDAY)) })
        assertTrue(PayCalculator().calculate(covered, nov, weekdays(day, listOf(sunday))::resolve).warnings.none { "고정 연장수당은 연장근로만" in it })
    }

    @Test
    fun `H1 each exception holds a week to its own limit`() {
        // Mon~Sat 10 hours: 60 hours.
        val ten = ShiftType(20, "긴 주간", "긴", 0xFF000000, t(8), t(19), 60, t(12))
        val r = ScheduleResolver(listOf(ten, off), listOf(WorkPattern.Rotation(LocalDate.of(2026, 10, 26), List(6) { ten.id } + listOf(off.id))), emptyList())
        fun over(e: HoursException) = WorkHoursCalculator(r, limits = HourLimits(exception = e)).week(d(2)).overLegalMinutes
        assertEquals(8 * 60, over(HoursException.NONE))
        assertEquals(0, over(HoursException.FLEX_2W))
        assertEquals(0, over(HoursException.FLEX_3M))
        assertEquals(0, over(HoursException.SELECTIVE))
        assertEquals(0, over(HoursException.SPECIAL_INDUSTRY))
        assertEquals(0, over(HoursException.SUPERVISORY))
        assertTrue(HoursException.entries.all { it.note.contains("제") })
        // 특례 업종: 11시간 연속 휴식. 19시에 끝나고 다음 날 8시에 시작하면 13시간이라 괜찮고, 야간 뒤 주간은 모자라요.
        assertTrue(WorkHoursCalculator.shortRests(r.resolveRange(d(2), d(7))).isEmpty())
        val tight = ScheduleResolver(listOf(ten, night, off), listOf(WorkPattern.Rotation(LocalDate.of(2026, 10, 26), List(6) { ten.id } + listOf(off.id))),
            listOf(DayEntry(d(2), override = ShiftOverride.Shift(night.id))))
        // 11/2 22:00 ~ 11/3 07:00, then 11/3 08:00: one hour of rest.
        assertEquals(listOf(d(2) to 60), WorkHoursCalculator.shortRests(tight.resolveRange(d(2), d(7))))
    }
}
