package com.bamnat.core

import com.bamnat.core.leave.LeavePayout
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** [LEAVE-05] 미사용 연차수당 약: days × 1일 통상임금 (통상시급 × 하루 소정근로시간). */
class LeavePayoutTest {
    private val month = YearMonth.of(2026, 12)

    private fun profile(type: WageType, base: Long, dailyHours: Int = 480) = PayProfile(
        occupation = Occupation.CUSTOM, wageType = type,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), base))), dailyHours = dailyHours,
    )

    @Test
    fun `monthly pay is divided by its hours`() {
        val e = LeavePayout.estimate(2.5, profile(WageType.MONTHLY, 2_090_000), month)
        assertEquals(80_000, e.daily)
        assertEquals(200_000, e.amount)
        assertEquals("2.5일 × 1일 통상임금 80,000원", e.formula)
    }

    @Test
    fun `hourly and daily pay`() {
        assertEquals(80_240, LeavePayout.dailyWage(profile(WageType.HOURLY, 10_030), month))
        assertEquals(50_150, LeavePayout.dailyWage(profile(WageType.HOURLY, 10_030, dailyHours = 300), month))
        assertEquals(120_000, LeavePayout.dailyWage(profile(WageType.DAILY, 120_000), month))
        assertEquals(0, LeavePayout.estimate(-1.0, profile(WageType.DAILY, 120_000), month).amount)
    }
}
