package com.bamnat.core

import com.bamnat.core.model.ExtraKind
import com.bamnat.core.pay.PayScale
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.PremiumTarget
import com.bamnat.core.pay.RuleChange
import com.bamnat.core.pay.RuleDraft
import com.bamnat.core.pay.RuleDraft.Target
import com.bamnat.core.pay.RuleDraft.Way
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.RuleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * [INPUT-01][INPUT-03] The rule builder's sentence is read back by the one reader pasted rules use, into the rule
 * the taps meant (계획/1.24-Q-개발계획.md 347·350), and [PAYX-47] the 호봉표 editor's next row.
 */
class RuleDraftTest {
    private fun one(d: RuleDraft): RuleChange = RuleText.read(d.sentence()).single().change

    @Test
    fun `the user's own 대근 way, built by taps, reads as the sentence they used to type`() {
        val d = RuleDraft(Target.COVER, Way.BASE_SHARE, percent = 10.0, unitHours = 8, overPremium = 50, nightPremium = 50)
        assertEquals("대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산", d.sentence())
        val rule = (one(d) as RuleChange.Extra).also { assertEquals(ExtraKind.COVER_ID, it.kind) }.rule
        assertEquals(PayUnit.BASE_SHARE, rule.unit)
        assertEquals(0.1, rule.percentOfBase, 1e-9)
        assertEquals(480, rule.unitMinutes)
        assertEquals(PremiumScope.Over(480), rule.premium(Premium.OVERTIME)!!.scope)
        assertEquals(0.5, rule.premium(Premium.OVERTIME)!!.rate, 1e-9)
        assertTrue(rule.premium(Premium.NIGHT)!!.on)
    }

    @Test
    fun `each way of paying extra work reads back as that way`() {
        val hourly = (one(RuleDraft(Target.SPECIAL, Way.PER_HOUR, amount = 20_000)) as RuleChange.Extra)
        assertEquals(ExtraKind.SPECIAL_ID, hourly.kind)
        assertEquals(PayUnit.PER_HOUR, hourly.rule.unit)
        assertEquals(20_000L, hourly.rule.amount)

        val once = (one(RuleDraft(Target.COVER, Way.PER_TIME, amount = 80_000, unitHours = 8)) as RuleChange.Extra).rule
        assertEquals(PayUnit.PER_TIME, once.unit)
        assertEquals(80_000L, once.amount)
        assertEquals(480, once.coverMinutes)

        val ordinary = (one(RuleDraft(Target.OVERTIME_WORK, Way.ORDINARY, percent = 150.0)) as RuleChange.Extra)
        assertEquals(ExtraKind.OVERTIME_ID, ordinary.kind)
        assertEquals(PayUnit.ORDINARY, ordinary.rule.unit)
        assertEquals(0.5, ordinary.rule.premiums.first { it.name.endsWith("가산") && it.key.startsWith("c") }.rate, 1e-9)
    }

    @Test
    fun `no night premium is said, not left out`() {
        val d = RuleDraft(Target.COVER, Way.BASE_SHARE, percent = 10.0, nightPremium = 0)
        assertTrue(d.sentence().endsWith("야간 가산 없음"))
        assertFalse((one(d) as RuleChange.Extra).rule.premium(Premium.NIGHT)!!.on)
    }

    @Test
    fun `the month's premiums and a day of the week`() {
        val ot = one(RuleDraft(Target.OVERTIME, Way.PREMIUM, percent = 50.0)) as RuleChange.Premium
        assertEquals(PremiumTarget.OVERTIME, ot.target)
        assertEquals(0.5, ot.premium, 1e-9)
        val night = one(RuleDraft(Target.NIGHT, Way.PREMIUM, percent = 100.0)) as RuleChange.Premium
        assertEquals(PremiumTarget.NIGHT, night.target)
        assertEquals(1.0, night.premium, 1e-9)
        assertEquals(PremiumTarget.HOLIDAY, (one(RuleDraft(Target.HOLIDAY, Way.PREMIUM, percent = 50.0)) as RuleChange.Premium).target)

        val sat = (one(RuleDraft(Target.SATURDAY, Way.PREMIUM, percent = 50.0)) as RuleChange.Custom).rule
        assertEquals(setOf(DayOfWeek.SATURDAY), sat.whenever.daysOfWeek)
        assertEquals(RulePay.OfHourly(0.5), sat.pay)
        val sun = (one(RuleDraft(Target.SUNDAY, Way.PER_TIME, amount = 20_000)) as RuleChange.Custom).rule
        assertEquals(setOf(DayOfWeek.SUNDAY), sun.whenever.daysOfWeek)
        assertEquals(RulePay.PerTime(20_000), sun.pay)
        val satHour = (one(RuleDraft(Target.SATURDAY, Way.PER_HOUR, amount = 3_000)) as RuleChange.Custom).rule
        assertEquals(RulePay.PerHour(3_000), satHour.pay)
    }

    @Test
    fun `every target and way the builder offers says something the reader understands`() {
        for (t in Target.entries) for (w in t.ways) {
            val d = RuleDraft().withTarget(t).withWay(w).let { if (it.asksPremiums) it.copy(overPremium = 50, nightPremium = 50, holidayPremium = 50) else it }
            assertFalse("$t $w empty", d.empty)
            assertEquals("$t $w: ${d.sentence()}", 1, RuleText.read(d.sentence()).size)
        }
    }

    @Test
    fun `changing what the rule is about keeps a way that fits and resets one that does not`() {
        val d = RuleDraft(Target.COVER, Way.PER_HOUR, amount = 12_000)
        assertEquals(12_000L, d.withTarget(Target.SPECIAL).amount)
        val night = d.withTarget(Target.NIGHT)
        assertEquals(Way.PREMIUM, night.way)
        assertEquals(50.0, night.percent, 0.0)
        assertFalse(night.asksPremiums)
        // A way the target does not have is not taken.
        assertEquals(night, night.withWay(Way.BASE_SHARE))
    }

    @Test
    fun `the next 호봉 row follows the last step's raise`() {
        assertEquals(2_400_000L, PayScale.nextAmount(emptyList()))
        assertEquals(2_500_000L, PayScale.nextAmount(listOf(2_500_000)))
        assertEquals(2_560_000L, PayScale.nextAmount(listOf(2_400_000, 2_480_000)))
        // A table that goes down is not carried on downwards.
        assertEquals(2_400_000L, PayScale.nextAmount(listOf(2_500_000, 2_400_000)))
    }
}
