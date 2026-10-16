package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.OffDayWork
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PremiumTarget
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.RuleImport
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Company rules and the calendar's 잔업·특근·대근 marks turned into pay [PAYX-07, PAYX-08]. */
class CompanyRulesTest {
    private val nov = YearMonth.of(2026, 11)
    private val monday = LocalDate.of(2026, 11, 2)
    private val saturday = LocalDate.of(2026, 11, 7)
    private val tuesday = LocalDate.of(2026, 11, 10)
    private val week = List(5) { Fixtures.day.id } + List(2) { Fixtures.off.id }

    private fun resolver(vararg entries: DayEntry) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, week), entries.toList())

    private val cover8h = DayEntry(saturday, holidayWorkMinutes = 480, extraWork = ExtraWork.COVER)
    private val overtime2h = DayEntry(tuesday, overtimeMinutes = 120, extraWork = ExtraWork.OVERTIME)

    private val nurse = PayProfile(
        occupation = Occupation.NURSE,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
    )
    private fun calc(p: PayProfile, r: ScheduleResolver, month: YearMonth = nov) = PayCalculator().calculate(p, month, r::resolve)

    @Test
    fun `calendar extra work is paid by law when the company has no rule`() {
        val pay = calc(nurse, resolver(cover8h, overtime2h))
        assertEquals(14_354, pay.ordinaryHourly)
        assertEquals(172_248, pay.lines.single { it.kind == LineKind.HOLIDAY }.amount)
        assertEquals(43_062, pay.lines.single { it.kind == LineKind.OVERTIME }.amount)
        // Each calendar day carries what it added.
        assertEquals(172_248, pay.day(saturday)!!.amount)
        assertEquals(ExtraWork.COVER, pay.day(saturday)!!.extraWork)
        assertEquals(43_062, pay.day(tuesday)!!.amount)
        assertEquals(pay.total - 3_000_000, pay.days.sumOf { it.amount })
    }

    @Test
    fun `cover at 200 percent of the ordinary hourly with 8 hours credited`() {
        val rules = CompanyRules(extra = mapOf(ExtraWork.COVER to ExtraRule(ExtraBasis.HOURLY_RATE, multiplier = 2.0, minMinutes = 480)))
        val pay = calc(nurse.copy(rules = rules), resolver(cover8h.copy(holidayWorkMinutes = 240)))
        val cover = pay.lines.single { it.kind == LineKind.EXTRA }
        assertEquals("대근 (회사 규칙)", cover.label)
        assertEquals(480, cover.minutes)
        assertEquals(229_664, cover.amount)
        assertTrue(pay.lines.none { it.kind == LineKind.HOLIDAY })
        assertEquals(229_664, pay.day(saturday)!!.amount)
        assertTrue(pay.warnings.isEmpty())
    }

    @Test
    fun `a fixed cover amount below the legal pay is flagged`() {
        val rules = CompanyRules(extra = mapOf(ExtraWork.COVER to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 50_000)))
        val pay = calc(nurse.copy(rules = rules), resolver(cover8h))
        assertEquals(50_000, pay.lines.single { it.kind == LineKind.EXTRA }.amount)
        assertTrue(pay.warnings.any { "172,248원" in it })
    }

    @Test
    fun `per hour amount for special work`() {
        val special = DayEntry(saturday, holidayWorkMinutes = 300, extraWork = ExtraWork.SPECIAL)
        val rules = CompanyRules(extra = mapOf(ExtraWork.SPECIAL to ExtraRule(ExtraBasis.FIXED_PER_HOUR, amount = 30_000)))
        val pay = calc(nurse.copy(rules = rules), resolver(special))
        assertEquals(150_000, pay.lines.single { it.kind == LineKind.EXTRA }.amount)
    }

    @Test
    fun `day off work can count as overtime instead of holiday work`() {
        val rules = CompanyRules(offDayWork = OffDayWork.OVERTIME)
        val pay = calc(nurse.copy(rules = rules), resolver(cover8h, overtime2h))
        assertTrue(pay.lines.none { it.kind == LineKind.HOLIDAY })
        assertEquals(600, pay.lines.single { it.kind == LineKind.OVERTIME }.minutes)
    }

    @Test
    fun `company premiums replace the legal ones and lower ones are flagged`() {
        val higher = calc(nurse.copy(rules = CompanyRules(overtimePremium = 1.0)), resolver(overtime2h))
        assertEquals(2.0, higher.lines.single { it.kind == LineKind.OVERTIME }.multiplier, 1e-9)
        val lower = calc(nurse.copy(rules = CompanyRules(overtimePremium = 0.3)), resolver(overtime2h))
        assertTrue(lower.warnings.any { "연장 가산" in it })
    }

    @Test
    fun `overtime minutes are rounded per day the company's way`() {
        val r = resolver(overtime2h.copy(overtimeMinutes = 50))
        val down = calc(nurse.copy(rules = CompanyRules(overtimeRounding = MinuteRounding(30, Rounding.DOWN))), r)
        assertEquals(30, down.lines.single { it.kind == LineKind.OVERTIME }.minutes)
        assertTrue(down.warnings.any { "30분 단위" in it })
        val up = calc(nurse.copy(rules = CompanyRules(overtimeRounding = MinuteRounding(30, Rounding.UP))), r)
        assertEquals(60, up.lines.single { it.kind == LineKind.OVERTIME }.minutes)
    }

    @Test
    fun `hourly workers get weekly holiday pay for each full week`() {
        val partTime = PayProfile(
            occupation = Occupation.PART_TIME,
            wageType = WageType.HOURLY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_320))),
        )
        val pay = calc(partTime, resolver())
        val weekly = pay.lines.single { it.label == "주휴수당" }
        // Weeks ending Nov 8, 15, 22 and 29; the week ending Dec 6 belongs to December.
        assertEquals(4 * 480, weekly.minutes)
        assertEquals(330_240, weekly.amount)
        val off = calc(partTime.copy(rules = CompanyRules(weeklyHolidayPay = false)), resolver())
        assertTrue(off.lines.none { it.label == "주휴수당" })
    }

    @Test
    fun `public holidays on the calendar are holiday work unless the company swaps them`() {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(LocalDate.of(2026, 10, 1), listOf(Fixtures.day.id)), emptyList())
        val oct = YearMonth.of(2026, 10)
        val pay = calc(nurse, r, oct)
        // 10/3 개천절, 10/5 대체공휴일, 10/9 한글날.
        assertEquals(3 * 480, pay.lines.filter { it.kind == LineKind.HOLIDAY }.sumOf { it.minutes })
        assertEquals("한글날", pay.day(LocalDate.of(2026, 10, 9))!!.holidayName)
        val swapped = calc(nurse.copy(rules = CompanyRules(publicHolidaysPaid = false)), r, oct)
        assertTrue(swapped.lines.none { it.kind == LineKind.HOLIDAY })
    }

    @Test
    fun `holiday table knows substitutes and falls back to solar dates`() {
        assertEquals("대체공휴일", KoreanHolidays.nameOf(LocalDate.of(2027, 12, 27)))
        assertEquals("광복절", KoreanHolidays.nameOf(LocalDate.of(2030, 8, 15)))
        assertNull(KoreanHolidays.nameOf(LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun `rule text sets extra work pay and company premiums`() {
        val found = RuleImport.read(
            """
            대근은 통상시급의 200%, 최소 8시간 인정
            특근 1회 80,000원
            잔업 시간당 2만원
            연장근로 200%
            야간 가산 100%
            """.trimIndent(),
        )
        val cover = found.single { it.extraWork == ExtraWork.COVER }.extraRule!!
        assertEquals(ExtraBasis.HOURLY_RATE, cover.basis)
        assertEquals(2.0, cover.multiplier, 1e-9)
        assertEquals(480, cover.minMinutes)
        assertEquals(ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 80_000), found.single { it.extraWork == ExtraWork.SPECIAL }.extraRule)
        assertEquals(ExtraRule(ExtraBasis.FIXED_PER_HOUR, amount = 20_000), found.single { it.extraWork == ExtraWork.OVERTIME }.extraRule)
        val ot = found.single { it.premiumTarget == PremiumTarget.OVERTIME }
        assertEquals(1.0, ot.premium!!, 1e-9)
        assertEquals(1.0, found.single { it.premiumTarget == PremiumTarget.NIGHT }.premium!!, 1e-9)
    }
}
