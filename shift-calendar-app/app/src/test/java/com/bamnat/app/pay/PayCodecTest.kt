package com.bamnat.app.pay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The saved pay setup survives a round trip unchanged [PAY-10, PAYX-02]. */
@RunWith(AndroidJUnit4::class)
class PayCodecTest {
    @Test fun roundTrip() {
        val p = PayProfile(
            occupation = Occupation.NURSE,
            wageType = WageType.MONTHLY,
            base = ValueTimeline(
                listOf(DatedValue(LocalDate.of(2025, 3, 1), 3_000_000), DatedValue(LocalDate.of(2026, 3, 1), 3_150_000)),
                RaiseRule(3, percent = 3.5, unit = 1_000, rounding = Rounding.NEAREST),
            ),
            items = listOf(
                PayItem("night", "나이트 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 3, 1), 30_000)), RaiseRule(1, amount = 1_000)),
                    Cadence.PER_NIGHT_SHIFT, ordinary = false),
                PayItem("shift", "교대근무 수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 3, 1), 100_000))), Cadence.MONTHLY, ordinary = true),
            ),
            fiveOrMoreEmployees = false,
            holidayPayMode = HolidayPayMode.PREMIUM_ONLY,
            dailyLimitMinutes = null,
            estimatedFields = setOf("night"),
        )
        assertEquals(p, PayCodec.decode(PayCodec.encode(p)))
    }
}
