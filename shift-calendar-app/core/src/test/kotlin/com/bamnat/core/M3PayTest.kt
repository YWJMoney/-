package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.Festival
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
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

/** M3 pay engine: 명절상여, the company's night window, cover pay per shift and own names [PAYX-09..12]. */
class M3PayTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val nov = YearMonth.of(2026, 11)
    private val profile = PayProfile(
        occupation = Occupation.MANUFACTURING,
        wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))),
    )

    private fun calc(p: PayProfile, r: ScheduleResolver, month: YearMonth = nov) = PayCalculator().calculate(p, month, r::resolve)

    @Test
    fun `holiday bonus follows the lunar calendar every year`() {
        assertEquals(LocalDate.of(2026, 2, 17), Festival.SEOLLAL.date(2026))
        assertEquals(LocalDate.of(2026, 9, 25), Festival.CHUSEOK.date(2026))
        assertEquals(LocalDate.of(2027, 2, 7), Festival.SEOLLAL.date(2027))
        assertEquals(LocalDate.of(2027, 9, 15), Festival.CHUSEOK.date(2027))
        val bonus = PayItem("b", "명절상여", ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 0))), Cadence.BONUS,
            percentOfBase = 0.5, festivals = setOf(Festival.SEOLLAL, Festival.CHUSEOK))
        assertEquals(1, bonus.timesIn(YearMonth.of(2026, 2)))
        assertEquals(1, bonus.timesIn(YearMonth.of(2026, 9)))
        assertEquals(0, bonus.timesIn(YearMonth.of(2026, 10)))
        assertEquals(2, bonus.timesInYear(2026))
        val before = bonus.copy(festivalMonthBefore = true)
        assertEquals(1, before.timesIn(YearMonth.of(2026, 1)))
        assertEquals(1, before.timesIn(YearMonth.of(2026, 8)))
        // 설날 2027-02-07 → paid in January 2027, not December 2026.
        assertEquals(0, before.timesIn(YearMonth.of(2026, 12)))

        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(LocalDate.of(2026, 9, 7), List(5) { Fixtures.day.id } + List(2) { Fixtures.off.id }), emptyList())
        val sep = calc(profile.copy(items = listOf(bonus)), r, YearMonth.of(2026, 9))
        val line = sep.lines.single { it.label == "명절상여" }
        assertEquals(1_500_000, line.amount)
        assertTrue(line.basis, "추석 9/25" in line.basis)
        assertTrue(calc(profile.copy(items = listOf(bonus)), r, YearMonth.of(2026, 10)).lines.none { it.label == "명절상여" })
    }

    private val nights = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, List(5) { Fixtures.night.id } + List(2) { Fixtures.off.id }), emptyList())

    @Test
    fun `night premium applies to base shifts and follows the company window`() {
        // Shifts counted as base work still earn the night premium for their night hours.
        val base = calc(profile.copy(scheduleIsRegular = true), nights)
        val legalNight = base.lines.single { it.kind == LineKind.NIGHT }
        assertTrue(legalNight.amount > 0)
        assertTrue(legalNight.formula, "22~06시" in legalNight.formula)

        val company = calc(profile.copy(scheduleIsRegular = true, rules = CompanyRules(nightStart = LocalTime.of(23, 0), nightEnd = LocalTime.of(7, 0))), nights)
        val night = company.lines.single { it.kind == LineKind.NIGHT }
        assertTrue(night.formula, "23~07시" in night.formula)
        assertTrue(company.warnings.none { "야간 시간" in it })

        // A window shorter than the law's is flagged.
        val short = calc(profile.copy(scheduleIsRegular = true, rules = CompanyRules(nightStart = LocalTime.of(0, 0), nightEnd = LocalTime.of(6, 0))), nights)
        assertTrue(short.lines.single { it.kind == LineKind.NIGHT }.amount < legalNight.amount)
        assertTrue(short.warnings.joinToString(), short.warnings.any { "야간 시간 00~06시" in it })
    }

    @Test
    fun `cover pay can differ by the shift that was covered`() {
        val week = List(5) { Fixtures.day.id } + List(2) { Fixtures.off.id }
        val tue = LocalDate.of(2026, 11, 10)
        val wed = LocalDate.of(2026, 11, 11)
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, week), listOf(
            // Only the covered shift: the 대근 takes that shift's hours.
            DayEntry(tue, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, coveredShiftId = Fixtures.night.id))),
            DayEntry(wed, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, coveredShiftId = Fixtures.evening.id))),
        ))
        val rules = CompanyRules(
            extra = mapOf(ExtraKind.COVER_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 50_000, nightPremium = false)),
            extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(Fixtures.night.id to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 100_000, nightPremium = false))),
            extraNames = mapOf(ExtraKind.COVER_ID to "대체근무"),
        )
        val pay = calc(profile.copy(rules = rules, fiveOrMoreEmployees = false), r)
        val extras = pay.lines.filter { it.kind == LineKind.EXTRA }.associate { it.label to it.amount }
        assertEquals(mapOf("대체근무 · 야간 (회사 규칙)" to 100_000L, "대체근무 (회사 규칙)" to 50_000L), extras)
        assertEquals("대체근무 · 야간", pay.day(tue)!!.parts.single { it.kind == LineKind.EXTRA }.label)
    }

    @Test
    fun `extra kinds keep names, hidden kinds and their place on the clock`() {
        val k = ExtraKinds()
            .put(ExtraKinds().of(ExtraKind.OVERTIME_ID).copy(name = "조출", short = "조", timing = ExtraTiming.BeforeShift(60)))
            .put(ExtraKinds().of(ExtraKind.SPECIAL_ID).copy(hidden = true))
        val back = ExtraKinds.decode(k.encode())
        assertEquals(k, back)
        assertEquals("조출", back.name(ExtraKind.OVERTIME_ID))
        assertEquals("조", back.of(ExtraKind.OVERTIME_ID).mark)
        assertEquals(ExtraTiming.BeforeShift(60), back.of(ExtraKind.OVERTIME_ID).timing)
        assertEquals(listOf(ExtraKind.OVERTIME_ID, ExtraKind.COVER_ID), back.visible().map { it.id })
        // A hidden kind already on the day still shows so it can be cleared.
        assertEquals(listOf(ExtraKind.OVERTIME_ID, ExtraKind.SPECIAL_ID, ExtraKind.COVER_ID), back.visible(listOf(ExtraKind.SPECIAL_ID)).map { it.id })
        assertEquals(ExtraKinds(), ExtraKinds.decode(""))
        assertFalse(back.of(ExtraKind.OVERTIME_ID).countsAsShift)
    }
}
