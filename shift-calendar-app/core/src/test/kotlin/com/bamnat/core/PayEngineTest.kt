package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.BulkEditPlanner
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CandidateKind
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MinuteClassifier
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.RuleImport
import com.bamnat.core.pay.ValueSource
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PayEngineTest {
    private val classifier = MinuteClassifier({ RateTable.on(it) })

    private fun resolver(start: LocalDate, cycle: List<Long>) =
        ScheduleResolver(Fixtures.all, WorkPattern.Rotation(start, cycle), emptyList())

    // ---- minutes ----

    @Test
    fun `night shift minutes shrink with the break and stay regular within 8 hours`() {
        val day = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.night.id)).resolve(LocalDate.of(2026, 10, 1))
        val m = classifier.classifyDay(day, holiday = false)
        assertEquals(480, m.regular)
        assertEquals(0, m.overtime)
        // 22:00~06:00 is 480 of the 540 minutes; the 60-minute break is taken out evenly.
        assertEquals(427, m.night)
    }

    @Test
    fun `a 12 hour shift has 4 hours of daily overtime`() {
        val day = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.long.id)).resolve(LocalDate.of(2026, 10, 1))
        val m = classifier.classifyDay(day, holiday = false)
        assertEquals(480, m.regular)
        assertEquals(240, m.overtime)
    }

    @Test
    fun `a sixth 8 hour day in one week is weekly overtime`() {
        // 2026-10-05 is a Monday.
        val r = resolver(LocalDate.of(2026, 10, 5), listOf(Fixtures.day.id))
        val week = (0L until 6L).map { r.resolve(LocalDate.of(2026, 10, 5).plusDays(it)) }
        val m = classifier.classify(week) { false }
        assertEquals(listOf(480, 480, 480, 480, 480, 0), m.map { it.regular })
        assertEquals(480, m.last().overtime)
    }

    @Test
    fun `holiday work splits at 8 hours and is not also overtime`() {
        val day = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.long.id)).resolve(LocalDate.of(2026, 10, 1))
        val m = classifier.classifyDay(day, holiday = true)
        assertEquals(480, m.holidayWithin8h)
        assertEquals(240, m.holidayOver8h)
        assertEquals(0, m.overtime + m.regular)
    }

    @Test
    fun `night window is counted across midnight`() {
        val rates = RateTable.on(LocalDate.of(2026, 1, 1))
        val from = LocalDate.of(2026, 1, 1).atTime(21, 0)
        assertEquals(480, classifier.nightMinutes(from, from.plusHours(10), rates))
        assertEquals(0, classifier.nightMinutes(from.withHour(9), from.withHour(17), rates))
    }

    // ---- month ----

    private val nurse = PayProfile(
        occupation = Occupation.NURSE,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
        items = listOf(
            PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 30_000))), Cadence.PER_NIGHT_SHIFT),
        ),
    )

    @Test
    fun `nurse month with 16 nights adds night premium and night allowance`() {
        val r = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.night.id, Fixtures.off.id))
        val pay = PayCalculator().calculate(nurse, YearMonth.of(2026, 10), r::resolve)
        assertEquals(14_354, pay.ordinaryHourly)
        assertEquals(3_000_000, pay.lines.single { it.kind == LineKind.BASE }.amount)
        val night = pay.lines.single { it.kind == LineKind.NIGHT }
        assertEquals(16 * 427, night.minutes)
        assertEquals(817_221, night.amount)
        assertEquals(480_000, pay.lines.single { it.label == "나이트 수당" }.amount)
        assertEquals(3_000_000 + 817_221 + 480_000, pay.total)
        assertEquals("2026.1", pay.ratesVersion)
        assertTrue(night.formula.contains("14,354원"))
        assertTrue(pay.warnings.isEmpty())
    }

    @Test
    fun `monthly holiday pay mode changes the holiday multiplier`() {
        val r = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.day.id))
        val holiday = LocalDate.of(2026, 10, 9)
        val full = PayCalculator().calculate(nurse, YearMonth.of(2026, 10), r::resolve) { it == holiday }
        val premiumOnly = PayCalculator().calculate(nurse.copy(holidayPayMode = HolidayPayMode.PREMIUM_ONLY), YearMonth.of(2026, 10), r::resolve) { it == holiday }
        assertEquals(1.5, full.lines.single { it.kind == LineKind.HOLIDAY }.multiplier, 1e-9)
        assertEquals(0.5, premiumOnly.lines.single { it.kind == LineKind.HOLIDAY }.multiplier, 1e-9)
    }

    @Test
    fun `small workplace pays no premiums and warns under the minimum wage`() {
        val partTime = PayProfile(
            occupation = Occupation.PART_TIME,
            wageType = WageType.HOURLY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 10_000))),
            fiveOrMoreEmployees = false,
        )
        val r = resolver(LocalDate.of(2026, 10, 1), listOf(Fixtures.long.id, Fixtures.off.id))
        val pay = PayCalculator().calculate(partTime, YearMonth.of(2026, 10), r::resolve)
        assertEquals(1.0, pay.lines.single { it.kind == LineKind.OVERTIME }.multiplier, 1e-9)
        assertTrue(pay.lines.none { it.kind == LineKind.NIGHT })
        assertEquals(2, pay.warnings.size)
    }

    // ---- values over time ----

    @Test
    fun `yearly raise fills years without an entered value`() {
        val t = ValueTimeline(
            listOf(DatedValue(LocalDate.of(2025, 1, 1), 3_000_000)),
            RaiseRule(month = 3, percent = 3.0, unit = 1_000, rounding = Rounding.UP),
        )
        assertEquals(3_000_000, t.on(LocalDate.of(2025, 2, 28)).value)
        assertEquals(3_090_000, t.on(LocalDate.of(2026, 2, 1)).value)
        // 3,090,000 × 1.03 = 3,182,700, rounded up to 3,183,000.
        val march = t.on(LocalDate.of(2026, 3, 1))
        assertEquals(3_183_000, march.value)
        assertEquals(ValueSource.RAISED, march.source)
        assertEquals(2, march.raisesApplied)
        // A value the user entered wins over the rule from then on.
        val entered = t.with(DatedValue(LocalDate.of(2026, 3, 1), 3_224_000))
        assertEquals(3_224_000, entered.on(LocalDate.of(2026, 6, 1)).value)
        assertEquals(ValueSource.ENTERED, entered.on(LocalDate.of(2026, 6, 1)).source)
    }

    @Test
    fun `only this month goes back to the old value afterwards`() {
        val t = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 100)))
        val after = BulkEditPlanner.apply(t, 150, EditScope.OnlyMonth(YearMonth.of(2026, 5)))
        assertEquals(100, after.on(YearMonth.of(2026, 4)).value)
        assertEquals(150, after.on(YearMonth.of(2026, 5)).value)
        assertEquals(100, after.on(YearMonth.of(2026, 6)).value)
        val from = BulkEditPlanner.apply(t, 150, EditScope.FromMonth(YearMonth.of(2026, 5)))
        assertEquals(150, from.on(YearMonth.of(2027, 1)).value)
    }

    @Test
    fun `plan previews each month and skips locked months in the total`() {
        val t = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 100)))
        val months = (1..6).map { YearMonth.of(2026, it) }
        val plan = BulkEditPlanner.plan(
            t, 120, EditScope.FromMonth(YearMonth.of(2026, 3)), months,
            totalFor = { tl, m -> tl.on(m).value * 10 },
            isLocked = { it == YearMonth.of(2026, 3) },
        )
        assertEquals(4, plan.changedMonths.size)
        assertEquals(3 * 200, plan.totalDelta)
        assertEquals(t, plan.before)
    }

    // ---- reading rules ----

    @Test
    fun `reads allowances, base pay, hourly wage and raises from text`() {
        val found = RuleImport.read(
            """
            • 야간근무수당 1회 30,000원
            기본급: 3,224,000원
            시급 10,500원
            매년 3월 임금 3.5% 인상
            장기근속수당 월 5만원
            연장근로 150%
            안녕하세요
            """.trimIndent(),
        )
        assertEquals(6, found.size)
        val night = found[0]
        assertEquals(CandidateKind.ALLOWANCE, night.kind)
        assertEquals("야간근무수당", night.name)
        assertEquals(30_000L, night.amount)
        assertEquals(Cadence.PER_NIGHT_SHIFT, night.cadence)
        assertEquals(CandidateKind.BASE_SALARY, found[1].kind)
        assertEquals(3_224_000L, found[1].amount)
        assertEquals(10_500L, found[2].amount)
        assertEquals(3.5, found[3].percent!!, 1e-9)
        assertEquals(50_000L, found[4].amount)
        assertEquals(Cadence.MONTHLY, found[4].cadence)
        assertEquals(CandidateKind.MULTIPLIER, found[5].kind)
    }

    @Test
    fun `korean money units are understood`() {
        assertEquals(35_000L, RuleImport.parseAmount("3만 5천원"))
        assertEquals(15_000L, RuleImport.parseAmount("1.5만원"))
        assertEquals(1_000L, RuleImport.parseAmount("1천원"))
    }
}
