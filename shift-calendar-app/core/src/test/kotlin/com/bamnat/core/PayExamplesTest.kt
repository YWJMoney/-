package com.bamnat.core

import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayExamples
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [PAYX-16] 포괄임금·탄력제 examples: what each puts in, and the words that say the app does not judge. */
class PayExamplesTest {
    private val salary = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 12_000L * 209))))
    private val from = LocalDate.of(2026, 11, 1)

    @Test
    fun `a fixed overtime allowance of twenty hours at 150 percent, put in once`() {
        val p = PayExamples.apply(PayExamples.Kind.FIXED_OVERTIME, salary, from)
        val item = p.items.single()
        assertEquals(360_000, item.amount.on(from).value)
        assertEquals(20 * 60, item.fixedOvertimeMinutes)
        assertEquals(1, PayExamples.apply(PayExamples.Kind.FIXED_OVERTIME, p, from, amount = 400_000).items.size)
    }

    @Test
    fun `flexible hours examples set the period and the scheduled hours`() {
        val two = PayExamples.apply(PayExamples.Kind.FLEX_2W, salary, from, flexStart = LocalDate.of(2026, 11, 2))
        assertEquals(FlexPeriod.TWO_WEEKS, two.flexPeriod)
        assertEquals(LocalDate.of(2026, 11, 2), two.flexStart)
        assertTrue(two.scheduleIsRegular)
        assertEquals(FlexPeriod.QUARTER, PayExamples.apply(PayExamples.Kind.FLEX_3M, salary, from).flexPeriod)
    }

    @Test
    fun `every example says where the law is and never decides`() {
        assertEquals(5, PayExamples.all.size)
        assertTrue(PayExamples.all.all { "근로기준법" in it.guide })
        assertTrue("판단하지 않아요" in PayExamples.of(PayExamples.Kind.FIXED_OVERTIME).guide)
    }
}
