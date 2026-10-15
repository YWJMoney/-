package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.StandardHours
import com.bamnat.core.pay.LeaveBook
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
 * Golden cases from published worked examples [PAY-13]. Each test names its source; the full
 * list with quotes, URLs and how each was checked is docs/pay-golden-cases.md. Cases marked
 * "산술" are arithmetic from the official rates, not a published example. These are not a
 * substitute for review by a 공인노무사.
 */
class PayGoldenTest {
    private val nov = YearMonth.of(2026, 11)
    private val monday = LocalDate.of(2026, 11, 2)
    private val saturday = LocalDate.of(2026, 11, 7)

    private fun type(id: Long, start: Int, startMin: Int, end: Int, endMin: Int, breakMinutes: Int = 0, breakStart: LocalTime? = null) =
        ShiftType(id, "근무$id", "$id", 0xFF000000, LocalTime.of(start, startMin), LocalTime.of(end, endMin), breakMinutes, breakStart)

    private val day = type(10, 9, 0, 18, 0, 60)
    private val off = ShiftType(11, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)

    /** Monday-to-Friday [shift] from [monday], weekends off. */
    private fun weekly(shift: ShiftType, vararg entries: DayEntry, daysPerWeek: Int = 5) =
        ScheduleResolver(listOf(shift, off), WorkPattern.Rotation(monday, List(daysPerWeek) { shift.id } + List(7 - daysPerWeek) { off.id }), entries.toList())

    /** [shift] on [date] only, every other day of the month off. */
    private fun oneDay(shift: ShiftType, date: LocalDate, vararg entries: DayEntry) =
        ScheduleResolver(listOf(shift, off), WorkPattern.Rotation(date, listOf(shift.id) + List(60) { off.id }), entries.toList())

    private fun monthly(base: Long, fiveOrMore: Boolean = true) = PayProfile(
        occupation = Occupation.CUSTOM, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), base))), fiveOrMoreEmployees = fiveOrMore,
    )
    private fun hourly(wage: Long) = PayProfile(
        occupation = Occupation.PART_TIME, wageType = WageType.HOURLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), wage))),
    )

    /** 통상시급 12,000원 in the 고용노동부 examples: 12,000 × 209. */
    private val moel = monthly(12_000L * 209)

    private fun MonthPay.sum(kind: LineKind) = lines.filter { it.kind == kind }.sumOf { it.amount }

    // ---- 통상시급 ----

    @Test
    fun `minimum wage monthly amounts divide back to the hourly wage`() {
        // 2027 최저임금 고시 (2026-08-05): 10,700원, 월 2,236,300원 (209시간). 2026: 10,320원, 2,156,880원.
        val r = weekly(day)
        assertEquals(10_700, PayCalculator().calculate(monthly(2_236_300), YearMonth.of(2027, 1), r::resolve).ordinaryHourly)
        assertEquals(10_320, PayCalculator().calculate(monthly(2_156_880), nov, r::resolve).ordinaryHourly)
    }

    // ---- 고용노동부 임금명세서 계산방법 예시 (moel.go.kr/wageCal.do), 통상시급 12,000원 ----

    @Test
    fun `moel overtime 16 hours is 288,000`() {
        val ot = listOf(3, 4, 5, 6, 10, 11, 12, 13).map { DayEntry(LocalDate.of(2026, 11, it), overtimeMinutes = 120) }
        val pay = PayCalculator().calculate(moel, nov, weekly(day, *ot.toTypedArray())::resolve)
        assertEquals(12_000, pay.ordinaryHourly)
        assertEquals(288_000, pay.sum(LineKind.OVERTIME))
        assertEquals(0, pay.sum(LineKind.NIGHT))
    }

    @Test
    fun `moel overtime 4 hours is 72,000`() {
        val pay = PayCalculator().calculate(moel, nov, weekly(day, DayEntry(LocalDate.of(2026, 11, 3), overtimeMinutes = 240))::resolve)
        assertEquals(72_000, pay.sum(LineKind.OVERTIME))
    }

    @Test
    fun `moel night premium alone is 24,000 for 4 hours`() {
        val d = LocalDate.of(2026, 11, 4)
        val pay = PayCalculator().calculate(moel, nov, oneDay(type(20, 22, 0, 2, 0), d)::resolve)
        assertEquals(24_000, pay.sum(LineKind.NIGHT))
        assertEquals(0, pay.sum(LineKind.OVERTIME))
    }

    @Test
    fun `moel overtime at night is 96,000 for 4 hours`() {
        val d = LocalDate.of(2026, 11, 4)
        val pay = PayCalculator().calculate(moel, nov, oneDay(type(21, 14, 0, 22, 0), d, DayEntry(d, overtimeMinutes = 240))::resolve)
        assertEquals(72_000, pay.sum(LineKind.OVERTIME))
        assertEquals(24_000, pay.sum(LineKind.NIGHT))
        assertEquals(96_000, pay.day(d)!!.amount)
    }

    @Test
    fun `moel holiday work 8 hours is 144,000 and 10 hours is 192,000`() {
        val eight = PayCalculator().calculate(moel, nov, weekly(day, DayEntry(saturday, holidayWorkMinutes = 480))::resolve)
        assertEquals(144_000, eight.sum(LineKind.HOLIDAY))
        val ten = PayCalculator().calculate(moel, nov, weekly(day, DayEntry(saturday, holidayWorkMinutes = 600))::resolve)
        assertEquals(192_000, ten.sum(LineKind.HOLIDAY))
        assertEquals(0, ten.sum(LineKind.OVERTIME))
    }

    // ---- 야간과 휴게 ----

    @Test
    fun `evening overtime with one night hour is 65,000 at 10,000 an hour`() {
        // 2차 출처 계산 블로그 (19~23시 연장, 22~23시 야간 겹침), 금액은 산술.
        val d = LocalDate.of(2026, 11, 4)
        val pay = PayCalculator().calculate(monthly(10_000L * 209), nov, oneDay(type(22, 11, 0, 19, 0), d, DayEntry(d, overtimeMinutes = 240))::resolve)
        assertEquals(60_000, pay.sum(LineKind.OVERTIME))
        assertEquals(5_000, pay.sum(LineKind.NIGHT))
    }

    @Test
    fun `a night break with a known start comes off the night minutes exactly`() {
        // 제56조 제3항 + 제54조 (산술): 22~07시, 02~03시 휴게 → 야간 실근로 7시간 × 5,000 = 35,000.
        val d = LocalDate.of(2026, 11, 4)
        val known = type(23, 22, 0, 7, 0, 60, LocalTime.of(2, 0))
        val pay = PayCalculator().calculate(monthly(10_000L * 209), nov, oneDay(known, d)::resolve)
        assertEquals(420, pay.minutes.single { it.date == d }.night)
        assertEquals(35_000, pay.sum(LineKind.NIGHT))
        assertEquals(0, pay.sum(LineKind.OVERTIME))
        // A break after 06:00 leaves the whole 22~06 window worked.
        val late = PayCalculator().calculate(monthly(10_000L * 209), nov, oneDay(known.copy(breakStart = LocalTime.of(6, 0)), d)::resolve)
        assertEquals(40_000, late.sum(LineKind.NIGHT))
    }

    @Test
    fun `holiday work into the night adds the night premium`() {
        // 고용노동부 예시의 가산율 조합 (산술): 휴일 14~01시, 18~19시 휴게 → 144,000 + 48,000 + 18,000.
        val d = LocalDate.of(2026, 11, 7)
        val shift = type(24, 14, 0, 1, 0, 60, LocalTime.of(18, 0))
        val pay = PayCalculator().calculate(moel, nov, oneDay(shift, d)::resolve) { it == d }
        assertEquals(192_000, pay.sum(LineKind.HOLIDAY))
        assertEquals(18_000, pay.sum(LineKind.NIGHT))
        assertEquals(210_000, pay.day(d)!!.amount)
    }

    // ---- 공휴일 (임금근로시간과-653, 2021.3.22.) ----

    private val hangul = LocalDate.of(2026, 10, 9) // 한글날, Friday
    private val oct = YearMonth.of(2026, 10)
    private val dayNoBreak = type(25, 9, 0, 17, 0)

    @Test
    fun `monthly worker on a public holiday gets 150 percent on top of the salary`() {
        val pay = PayCalculator().calculate(moel, oct, oneDay(dayNoBreak, hangul)::resolve)
        assertEquals(144_000, pay.sum(LineKind.HOLIDAY))
        assertEquals(144_000, pay.day(hangul)!!.amount)
    }

    @Test
    fun `hourly worker gets a day's wage for a public holiday on a working day and holiday pay on top`() {
        val pay = PayCalculator().calculate(hourly(12_000), oct, oneDay(dayNoBreak, hangul)::resolve)
        assertEquals(96_000, pay.lines.single { it.label == "유급휴일수당 (공휴일)" }.amount)
        assertEquals(144_000, pay.sum(LineKind.HOLIDAY))
        assertEquals(240_000, pay.day(hangul)!!.amount)
    }

    @Test
    fun `a day off that falls on a public holiday earns nothing`() {
        val pay = PayCalculator().calculate(hourly(12_000), oct, oneDay(dayNoBreak, hangul.plusDays(1))::resolve)
        assertTrue(pay.lines.none { it.label == "유급휴일수당 (공휴일)" })
        assertEquals(0, pay.day(hangul)!!.amount)
    }

    // ---- 5인 미만 (제11조, 시행령 별표1) ----

    @Test
    fun `under five employees pays only the hours, no premiums`() {
        val small = monthly(12_000L * 209, fiveOrMore = false)
        val d = LocalDate.of(2026, 11, 4)
        val night = PayCalculator().calculate(small, nov, oneDay(type(21, 14, 0, 22, 0), d, DayEntry(d, overtimeMinutes = 240))::resolve)
        assertEquals(48_000, night.sum(LineKind.OVERTIME))
        assertEquals(0, night.sum(LineKind.NIGHT))
        val sat = PayCalculator().calculate(small, nov, weekly(day, DayEntry(saturday, holidayWorkMinutes = 600))::resolve)
        assertEquals(120_000, sat.sum(LineKind.HOLIDAY))
    }

    @Test
    fun `under five employees a public holiday is an ordinary day`() {
        val small = monthly(12_000L * 209, fiveOrMore = false)
        val pay = PayCalculator().calculate(small, oct, oneDay(dayNoBreak, hangul)::resolve)
        assertEquals(0, pay.sum(LineKind.HOLIDAY))
        assertEquals(480, pay.minutes.single { it.date == hangul }.regular)
    }

    // ---- 주휴수당 (제55조, 시행령 제30조, 제18조 제3항) ----

    private fun weeklyHoliday(pay: MonthPay) = pay.lines.singleOrNull { it.label == "주휴수당" }?.amount ?: 0

    @Test
    fun `weekly holiday pay is weekly hours over 40 times 8 hours`() {
        // 데일리팜 (주 18시간, 시급 1만 원 → 36,000원). 11월에 끝나는 주: 11/8, 15, 22, 29.
        val six = type(26, 9, 0, 15, 0)
        assertEquals(4 * 36_000, weeklyHoliday(PayCalculator().calculate(hourly(10_000), nov, weekly(six, daysPerWeek = 3)::resolve)))
        // 산술: 주 15시간 → 32,100원, 주 40시간 → 85,600원 (시급 10,700).
        val five = type(27, 9, 0, 14, 0)
        assertEquals(4 * 32_100, weeklyHoliday(PayCalculator().calculate(hourly(10_700), nov, weekly(five, daysPerWeek = 3)::resolve)))
        assertEquals(4 * 85_600, weeklyHoliday(PayCalculator().calculate(hourly(10_700), nov, weekly(dayNoBreak)::resolve)))
    }

    @Test
    fun `under 15 hours a week there is no weekly holiday pay`() {
        val short = type(28, 9, 0, 16, 15) // 7h15m × 2 = 14.5h
        assertEquals(0, weeklyHoliday(PayCalculator().calculate(hourly(10_700), nov, weekly(short, daysPerWeek = 2)::resolve)))
    }

    @Test
    fun `a public holiday in the week still counts toward weekly holiday pay`() {
        // 10/5 대체공휴일 and 10/9 한글날 are paid holidays, so those weeks are still 40 hours.
        val r = ScheduleResolver(listOf(dayNoBreak, off), WorkPattern.Rotation(LocalDate.of(2026, 9, 28), List(5) { dayNoBreak.id } + List(2) { off.id }), emptyList())
        val pay = PayCalculator().calculate(hourly(10_700), oct, r::resolve)
        assertEquals(4 * 85_600, weeklyHoliday(pay))
    }

    // ---- 연차 (제60조) ----

    @Test
    fun `legal annual leave by years of service`() {
        val hire = LocalDate.of(2010, 3, 2)
        fun on(years: Long, months: Long = 0) = LeaveBook.legalDays(hire, hire.plusYears(years).plusMonths(months))
        assertEquals(11, on(0, 11))
        assertEquals(15, on(1))
        assertEquals(15, on(2))
        assertEquals(16, on(3))
        assertEquals(17, on(5))
        assertEquals(25, on(21))
        assertEquals(25, on(25))
    }

    // ---- 상여금과 통상임금 (대법원 2024.12.19. 2020다247190 전원합의체, 시행령 제6조 제2항 제5호) ----

    private fun bonus(percent: Double, months: Set<Int>, ordinary: Boolean = true) = PayItem(
        "bonus", "상여금", ValueTimeline(listOf(DatedValue(LocalDate.of(2020, 1, 1), 0))), Cadence.BONUS,
        ordinary = ordinary, months = months, percentOfBase = percent,
    )

    @Test
    fun `a 600 percent bonus raises the ordinary hourly by a year's payments over 12`() {
        // 고용노동부 통상임금 지침(2025.2.6) 예시 (검색 요약): 기본급 200만, 상여 600% → 월 100만, 300만 ÷ 209 = 14,354 (산술).
        val p = monthly(2_000_000).copy(items = listOf(bonus(1.0, setOf(2, 4, 6, 8, 10, 12))))
        val r = weekly(day)
        assertEquals(14_354, PayCalculator().calculate(p, nov, r::resolve).ordinaryHourly)
        // Paid in even months only, 100% of 기본급 each time.
        val dec = PayCalculator().calculate(p, YearMonth.of(2026, 12), r::resolve)
        assertEquals(2_000_000, dec.lines.single { it.label == "상여금" }.amount)
        assertTrue(PayCalculator().calculate(p, YearMonth.of(2026, 11), r::resolve).lines.none { it.label == "상여금" })
        // A performance bonus left out of 통상임금 does not change the hourly.
        val perf = p.copy(items = listOf(bonus(1.0, setOf(2, 4, 6, 8, 10, 12), ordinary = false)))
        assertEquals(9_569, PayCalculator().calculate(perf, nov, r::resolve).ordinaryHourly)
    }

    @Test
    fun `bonuses count toward ordinary wage only from the 2024-12-19 ruling`() {
        val p = monthly(2_000_000).copy(base = ValueTimeline(listOf(DatedValue(LocalDate.of(2020, 1, 1), 2_000_000))),items = listOf(bonus(1.0, setOf(2, 4, 6, 8, 10, 12))))
        val r = weekly(day)
        assertEquals(9_569, PayCalculator().calculate(p, YearMonth.of(2024, 11), r::resolve).ordinaryHourly)
        assertEquals(14_354, PayCalculator().calculate(p, YearMonth.of(2024, 12), r::resolve).ordinaryHourly)
    }

    // ---- 월 소정근로시간 (시행령 제6조 제2항) ----

    @Test
    fun `monthly standard hours from weekly hours`() {
        assertEquals(209, StandardHours.monthly(40 * 60))            // 208.57
        assertEquals(183, StandardHours.monthly(35 * 60))            // (35 + 7) × 365/7/12 = 182.5 (산술)
        assertEquals(226, StandardHours.monthly(40 * 60, 4 * 60))    // 토요일 유급 4시간: 225.95
        assertEquals(243, StandardHours.monthly(40 * 60, 8 * 60))    // 토요일 유급 8시간: 243.33
        assertEquals(0, StandardHours.weeklyHolidayMinutes(14 * 60))
    }

    @Test
    fun `the roster gives the weekly average`() {
        assertEquals(40 * 60, StandardHours.averageWeekly(monday, resolve = weekly(dayNoBreak)::resolve))
        // 12-hour shifts count 8 hours of 소정근로 a day (제50조); the rest is 연장.
        val twelve = type(29, 7, 0, 19, 0)
        assertEquals(24 * 60, StandardHours.averageWeekly(monday, resolve = weekly(twelve, daysPerWeek = 3)::resolve))
    }

    // ---- 고정 연장수당 ----

    @Test
    fun `fixed overtime allowance covers overtime up to its amount and flags the rest`() {
        val fixed = PayItem("ot", "고정 연장수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 360_000))),
            Cadence.MONTHLY, fixedOvertimeMinutes = 20 * 60)
        val p = moel.copy(items = listOf(fixed.copy(ordinary = true)))
        fun ot(hours: Int) = (3..20).filter { LocalDate.of(2026, 11, it).dayOfWeek.value <= 5 }.take(hours / 2)
            .map { DayEntry(LocalDate.of(2026, 11, it), overtimeMinutes = 120) }.toTypedArray()
        // 16시간 연장 = 288,000원 (고용노동부 예시) ≤ 360,000원: nothing more, the hourly stays 12,000.
        val within = PayCalculator().calculate(p, nov, weekly(day, *ot(16))::resolve)
        assertEquals(12_000, within.ordinaryHourly)
        assertEquals(12_000L * 209 + 360_000, within.total)
        assertTrue(within.warnings.none { "차액" in it })
        // 24시간 = 432,000원: 72,000원 more is owed.
        val over = PayCalculator().calculate(p, nov, weekly(day, *ot(24))::resolve)
        assertEquals(12_000L * 209 + 432_000, over.total)
        assertTrue(over.warnings.any { "72,000원" in it })
    }
}
