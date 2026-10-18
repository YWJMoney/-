package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.MatchChange
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Payslip
import com.bamnat.core.pay.PayslipCheck
import com.bamnat.core.pay.PayslipMatcher
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PAYS-02] Payslip matching: each payslip is worked out from the settings the company really uses, and the
 * matcher starts from what the user set up. It may change calculation choices only; legal facts come back
 * as hints. 통상시급 12,000원 (2,508,000원 ÷ 209) throughout.
 */
class PayslipMatchTest {
    private val nov = YearMonth.of(2026, 11)
    private val oct = YearMonth.of(2026, 10)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun wage(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), v)))
    private val off = ShiftType(99, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)

    /** 월~금 [shift] from Monday 9/28, so October and November are whole. */
    private fun weekdays(shift: ShiftType, vararg e: DayEntry) =
        ScheduleResolver(listOf(shift, off), WorkPattern.Rotation(LocalDate.of(2026, 9, 28), List(5) { shift.id } + List(2) { off.id }), e.toList())

    private val office = ShiftType(10, "사무", "사", 0xFF1E88E5, t(9), t(18), 60, t(12))

    /** 연장 16시간 in November, 2시간씩 8일: 288,000원 (고용노동부 임금명세서 예시). */
    private val overtime = listOf(3, 4, 5, 6, 10, 11, 12, 13)
        .map { DayEntry(LocalDate.of(2026, 11, it), extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))) }
    private val withOvertime = weekdays(office, *overtime.toTypedArray())

    private val salary = PayProfile(occupation = Occupation.CUSTOM, wageType = WageType.MONTHLY, base = wage(2_508_000))

    private fun total(p: PayProfile, r: ScheduleResolver, month: YearMonth = nov) = PayCalculator().calculate(p, month, r::resolve).total

    @Test
    fun `the overtime pay and its hours give 209 hours`() {
        val gross = total(salary, withOvertime)
        assertEquals(2_508_000 + 288_000L, gross)
        val asked = salary.copy(monthlyHours = 226, estimatedFields = setOf(Unknowns.MONTHLY_HOURS))
        val m = PayslipMatcher.match(asked, nov, withOvertime::resolve, Payslip(gross, overtimePay = 288_000, overtimeMinutes = 16 * 60))
        // 288,000 ÷ (16시간 × 150%) = 12,000; 2,508,000 ÷ 12,000 = 209.
        assertEquals(12_000L, m.payslipHourly)
        assertEquals(209, m.profile.monthlyHours)
        assertTrue(m.matched)
        assertEquals(0L, m.gap)
        assertEquals(listOf(MatchChange(Unknowns.MONTHLY_HOURS, "월 소정근로시간", "226시간", "209시간")), m.changes)
        // The payslip settles it: it leaves 확인 필요.
        assertTrue(m.profile.estimatedFields.isEmpty())
        assertTrue(m.hints.isEmpty())
        assertNull(m.belowLaw)
        assertTrue("${m.runs} runs", m.runs <= 40)
    }

    @Test
    fun `the gross alone finds 209 among the usual hours`() {
        val m = PayslipMatcher.match(salary.copy(monthlyHours = 243), nov, withOvertime::resolve, Payslip(2_796_000))
        assertNull(m.payslipHourly)
        assertEquals(209, m.profile.monthlyHours)
        assertTrue(m.matched)
    }

    @Test
    fun `the payslip settles the holiday pay mode`() {
        // 10/5 대체공휴일과 10/9 한글날 근무. 회사는 공휴일 근무에 가산 50%만 줘요: 2 × 8시간 × 6,000.
        val r = weekdays(office)
        val gross = total(salary.copy(holidayPayMode = HolidayPayMode.PREMIUM_ONLY), r, oct)
        assertEquals(2_508_000 + 96_000L, gross)
        val asked = salary.copy(estimatedFields = setOf(Unknowns.HOLIDAY_PAY_MODE))
        assertEquals(2_508_000 + 288_000L, total(asked, r, oct))
        val m = PayslipMatcher.match(asked, oct, r::resolve, Payslip(gross))
        assertTrue(m.matched)
        assertEquals(HolidayPayMode.PREMIUM_ONLY, m.profile.holidayPayMode)
        assertEquals(listOf(MatchChange(Unknowns.HOLIDAY_PAY_MODE, "휴일 근무 급여 방식", "100% + 가산", "가산만")), m.changes)
        assertTrue(m.profile.estimatedFields.isEmpty())
        // 가산만 pays less than 100% + 가산, and the result says by how much.
        assertEquals(192_000L, m.belowLaw)
    }

    @Test
    fun `legal facts are never changed, only suggested`() {
        // 실제로는 5인 미만: 연장 16시간이 100%라 2,508,000 + 192,000.
        val gross = total(salary.copy(fiveOrMoreEmployees = false), withOvertime)
        assertEquals(2_700_000L, gross)
        val m = PayslipMatcher.match(salary, nov, withOvertime::resolve, Payslip(gross, overtimePay = 192_000, overtimeMinutes = 16 * 60))
        assertFalse(m.matched)
        assertTrue(m.profile.fiveOrMoreEmployees)
        assertFalse(m.profile.rules.supervisoryApproved)
        assertTrue(m.profile.rules.publicHolidaysPaid)
        assertNull(m.profile.flexPeriod)
        // The flip is tried on the settings before the search too, so it explains the payslip exactly.
        val hint = m.hints.first()
        assertEquals(Unknowns.FIVE_OR_MORE, hint.key)
        assertEquals("직원이 5명 미만이면 명세서와 맞아요. 회사에 확인해 보세요", hint.text)
        assertEquals(gross, hint.total)
        // An office day is no 감시·단속 work, so that is never suggested.
        assertTrue(m.hints.none { it.key == Unknowns.SUPERVISORY })
    }

    @Test
    fun `a legal fact that only comes closer does not say it matches`() {
        // 5인 미만 comes to 2,700,000; the payslip says 20,000원 more, which nothing in the app explains.
        val fewer = total(salary.copy(fiveOrMoreEmployees = false), withOvertime)
        val m = PayslipMatcher.match(salary, nov, withOvertime::resolve, Payslip(fewer + 20_000, overtimePay = 192_000, overtimeMinutes = 16 * 60))
        assertFalse(m.matched)
        val hint = m.hints.first()
        assertEquals(Unknowns.FIVE_OR_MORE, hint.key)
        assertEquals("직원이 5명 미만이면 명세서에 더 가까워요. 회사에 확인해 보세요", hint.text)
        assertEquals(fewer, hint.total)
    }

    @Test
    fun `a guard roster can be told it looks like approved supervisory work`() {
        // 24시간 격일제, 시급 10,700원, 승인 있는 회사의 명세서 (2027년 1월, 14번 근무 × 192,600).
        val guard = ShiftType(30, "당직", "당", 0xFF000000, t(9), t(9), 480, t(18))
        val r = ScheduleResolver(listOf(guard, off), WorkPattern.Rotation(LocalDate.of(2027, 1, 4), listOf(guard.id, off.id)), emptyList())
        val hourly = PayProfile(occupation = Occupation.CUSTOM, wageType = WageType.HOURLY, base = wage(10_700))
        val jan = YearMonth.of(2027, 1)
        val gross = total(hourly.copy(rules = CompanyRules(supervisoryApproved = true)), r, jan)
        assertEquals(14 * 192_600L, gross)
        val m = PayslipMatcher.match(hourly, jan, r::resolve, Payslip(gross))
        assertFalse(m.profile.rules.supervisoryApproved)
        val hint = m.hints.single { it.key == Unknowns.SUPERVISORY }
        assertEquals("감시·단속 승인을 받은 회사라면 명세서와 맞아요. 회사에 확인해 보세요", hint.text)
        assertEquals(gross, hint.total)
    }

    @Test
    fun `a payslip within half a percent matches, one with an allowance the app lacks does not`() {
        val app = total(salary, withOvertime)
        val near = PayslipMatcher.match(salary, nov, withOvertime::resolve, Payslip(app + 700))
        assertTrue(near.matched)
        assertEquals(700L, near.gap)
        assertTrue(near.changes.isEmpty())
        // 자격수당 300,000원 is on the payslip only: the rest matches, the month does not.
        val m = PayslipMatcher.match(salary, nov, withOvertime::resolve, Payslip(app + 300_000, allowances = mapOf("자격수당" to 300_000L)))
        assertFalse(m.matched)
        assertEquals(300_000L, m.gap)
        assertTrue(m.changes.isEmpty())
        assertEquals(mapOf("자격수당" to 300_000L), m.unmatched)
        assertEquals("명세서의 ‘자격수당’ 300,000원은 앱에 없는 수당이에요. 수당으로 넣어 주세요", m.hints.single().text)
        // Nothing explains 300,000원 more and no allowance is named: no match and no hint.
        val far = PayslipMatcher.match(salary, nov, withOvertime::resolve, Payslip(app + 300_000))
        assertFalse(far.matched)
        assertTrue(far.hints.isEmpty())
    }

    @Test
    fun `allowance amounts come from the payslip by name, per shift for shift allowances`() {
        // 4조2교대 주주휴휴야야휴휴 from Sunday 11/1: 11월 야간 8번.
        val day = ShiftType(1, "주간", "주", 0xFF1E88E5, t(7), t(19), 60, t(12))
        val night = ShiftType(2, "야간", "야", 0xFF5E35B1, t(19), t(7), 60, t(2))
        val r = ScheduleResolver(listOf(day, night, off),
            WorkPattern.Rotation(LocalDate.of(2026, 11, 1), listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)), emptyList())
        val items = listOf(PayItem("m", "식대", wage(100_000), Cadence.MONTHLY), PayItem("n", "야간근무수당", wage(30_000), Cadence.PER_NIGHT_SHIFT))
        val truth = salary.copy(items = items.map { if (it.id == "m") it.copy(amount = wage(200_000)) else it })
        val gross = total(truth, r)
        // 식대 100,000원은 추정, 야간근무수당은 금액 모름으로 둔 설정.
        val asked = Unknowns.markUnknown(salary.copy(items = items, estimatedFields = setOf("m")), Unknowns.item("n"))
        val payslip = Payslip(gross, allowances = mapOf("식 대" to 200_000L, "야간근무수당" to 240_000L))
        val m = PayslipMatcher.match(asked, nov, r::resolve, payslip)
        assertTrue(m.matched)
        assertEquals(gross, m.total)
        assertEquals(
            listOf(
                MatchChange(Unknowns.item("m"), "식대", "100,000원", "200,000원"),
                MatchChange(Unknowns.item("n"), "야간근무수당", "금액 모름", "1회 30,000원"),
            ),
            m.changes,
        )
        assertTrue(m.profile.estimatedFields.isEmpty())
        // Saved with the check once the user applies it; the figures stay on the phone.
        assertEquals(PayslipCheck(nov, gross, gross, 1_234L), m.verifiedProfile(1_234L).verified)
    }

    @Test
    fun `a company night window is found and shown as below the law`() {
        // 월~금 18~02시: 법정 야간 22~02시 4시간, 회사 23~07시면 3시간. 21번 × 1시간 × 6,000 덜 받아요.
        val evening = ShiftType(20, "저녁", "저", 0xFF000000, t(18), t(2))
        val r = weekdays(evening)
        val late = salary.copy(rules = CompanyRules(nightStart = t(23), nightEnd = t(7)))
        val gross = total(late, r)
        assertEquals(2_508_000 + 21 * 3 * 6_000L, gross)
        val m = PayslipMatcher.match(salary, nov, r::resolve, Payslip(gross))
        assertTrue(m.matched)
        assertEquals(209, m.profile.monthlyHours)
        assertEquals(listOf(MatchChange("nightWindow", "야간 시간", "22~06시", "23~07시")), m.changes)
        assertEquals(21 * 6_000L, m.belowLaw)
        assertTrue("${m.runs} runs", m.runs <= 80)
    }
}
