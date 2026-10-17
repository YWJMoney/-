package com.bamnat.app.pay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.MinuteRounding
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
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
                PayItem("holiday", "명절상여", ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 3, 1), 0))), Cadence.BONUS,
                    percentOfBase = 0.5, festivals = setOf(com.bamnat.core.pay.Festival.CHUSEOK), festivalMonthBefore = true),
            ),
            fiveOrMoreEmployees = false,
            holidayPayMode = HolidayPayMode.PREMIUM_ONLY,
            dailyLimitMinutes = null,
            estimatedFields = setOf("night"),
            rules = CompanyRules(
                overtimePremium = 1.0, nightPremium = null, holidayPremiumWithin8h = 0.7,
                restDayWork = RestDayWork.OVERTIME, weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY), publicHolidaysPaid = false,
                extra = mapOf(
                    ExtraKind.COVER_ID to ExtraRule(ExtraBasis.HOURLY_RATE, multiplier = 2.0, minMinutes = 480, nightPremium = false),
                    ExtraKind.SPECIAL_ID to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 80_000),
                    // [EXTRA-03] 1회 기본급의 10%, 8시간까지, 연장 가산과 1회 30,000원.
                    101L to ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = 0.1, coverMinutes = 480, legalPremiums = true, bonus = 30_000),
                ),
                publicHolidayAs = 101L,
                overtimeRounding = MinuteRounding(30, Rounding.NEAREST),
                weeklyHolidayPay = false,
                extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(3L to ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = 100_000))),
                nightStart = java.time.LocalTime.of(23, 0), nightEnd = java.time.LocalTime.of(7, 0),
            ),
        )
        assertEquals(p, PayCodec.decode(PayCodec.encode(p)))
        assertEquals(WeeklyHoliday.FirstRestDay, PayCodec.rulesFrom(PayCodec.rulesJson(CompanyRules(weeklyHoliday = WeeklyHoliday.FirstRestDay))).weeklyHoliday)
    }

    @Test fun rulesFrom18KeepTheirRestDayRuleAndKinds() {
        // As 1.8 wrote it: kinds by name, 휴일근로 for days off unless set to 연장.
        val old = JSONObject(
            "{\"offDay\":\"HOLIDAY\",\"publicHolidayAs\":\"COVER\"," +
                "\"extra\":{\"COVER\":{\"basis\":\"FIXED_PER_TIME\",\"amount\":80000,\"night\":false}}," +
                "\"extraByShift\":{\"COVER\":{\"3\":{\"basis\":\"FIXED_PER_TIME\",\"amount\":100000}}}}",
        )
        val r = PayCodec.rulesFrom(old)
        assertEquals(RestDayWork.HOLIDAY, r.restDayWork)
        assertEquals(WeeklyHoliday.Unknown, r.weeklyHoliday)
        assertEquals(ExtraKind.COVER_ID, r.publicHolidayAs)
        assertEquals(80_000L, r.extra.getValue(ExtraKind.COVER_ID).amount)
        assertEquals(100_000L, r.extraByShift.getValue(ExtraKind.COVER_ID).getValue(3L).amount)
        assertEquals(RestDayWork.OVERTIME, PayCodec.rulesFrom(JSONObject("{\"offDay\":\"OVERTIME\"}")).restDayWork)
        // A new setup is by law.
        assertEquals(RestDayWork.LAW, PayCodec.rulesFrom(PayCodec.rulesJson(CompanyRules())).restDayWork)
    }
}
