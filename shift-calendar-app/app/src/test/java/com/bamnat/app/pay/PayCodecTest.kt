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
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

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

    /** [EXTRA-08] An hour's pay and its ticked premiums, the company's own among them, come back as saved. */
    @Test fun premiumsRoundTrip() {
        val law = ExtraRule.lawUnit(overtime = 0.5, night = 0.5, holiday = 0.5, restAsHoliday = false)
        val company = law.copy(
            unit = PayUnit.BASE_SHARE, percentOfBase = 0.1, unitMinutes = 480, minMinutes = 240, bonus = 30_000,
            premiums = law.premiums.map { if (it.key == Premium.OVERTIME) it.copy(scope = PremiumScope.Over(480)) else it } + listOf(
                Premium("c1", "심야 가산", 0.25, true, PremiumScope.Window(LocalTime.of(19, 0), LocalTime.of(23, 30)), offDaysOnly = true),
                Premium("c2", "대근 가산", 0.1, false, PremiumScope.All),
            ),
        )
        val perTime = law.copy(unit = PayUnit.PER_TIME, amount = 80_000, coverMinutes = 480)
        val rules = CompanyRules(extra = mapOf(ExtraKind.COVER_ID to company, ExtraKind.SPECIAL_ID to perTime),
            extraByShift = mapOf(ExtraKind.COVER_ID to mapOf(3L to company.copy(unit = PayUnit.PER_HOUR, amount = 20_000))))
        assertEquals(rules, PayCodec.rulesFrom(PayCodec.rulesJson(rules)))
    }

    /** [EXTRA-08] A shared file cannot widen a premium: rates, names, the count and the four boxes stay in bounds. */
    @Test fun premiumsFromAFileStayInBounds() {
        fun add(key: String, scope: String, rate: Double = 0.5, name: String = "가산") =
            "{\"key\":\"$key\",\"name\":\"$name\",\"rate\":$rate,\"on\":true,\"scope\":\"$scope\"}"
        val adds = listOf(
            add("night", "all"), add("overtime", "over:600"), add("holiday", "later"),
            add("c1", "window:19:00-23:00", rate = 99.0, name = "아주아주아주아주긴이름가산입니다"),
            add("c1", "all"), add("c2", "someday"), add("c3", "window:19:00-19:00"),
        ) + (4..12).map { add("c$it", "all") }
        val o = JSONObject("{\"extra\":{\"3\":{\"basis\":\"UNIT\",\"unit\":\"LATER\",\"unitMin\":5,\"adds\":[${adds.joinToString(",")}]}}}")
        val r = PayCodec.rulesFrom(o).extra.getValue(3L)
        assertEquals(PayUnit.ORDINARY, r.unit)
        assertEquals(30, r.unitMinutes)
        // The four keep what they are for; only 연장 can be counted another way.
        assertEquals(PremiumScope.Night, r.premium(Premium.NIGHT)!!.scope)
        assertEquals(PremiumScope.Over(600), r.premium(Premium.OVERTIME)!!.scope)
        assertEquals(PremiumScope.Holiday, r.premium(Premium.HOLIDAY)!!.scope)
        val c1 = r.premium("c1")!!
        assertEquals(Premium.MAX_RATE, c1.rate, 1e-9)
        assertEquals(Premium.MAX_NAME, c1.name.length)
        assertEquals(PremiumScope.Window(LocalTime.of(19, 0), LocalTime.of(23, 0)), c1.scope)
        // Hours it cannot read, or none at all, are left out rather than guessed.
        assertNull(r.premium("c2"))
        assertNull(r.premium("c3"))
        assertEquals(Premium.MAX_CUSTOM, r.premiums.count { it.custom })
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

    /**
     * [PAYS-01][PAYS-02][PAYS-03] 일당 and its hours, the 연봉 behind the 기본급, the payslip check, the new
     * company rules, an allowance in place of a premium and the 확인 필요 keys all come back as saved.
     */
    @Test fun newPaySetupFieldsRoundTrip() {
        val p = PayProfile(
            occupation = Occupation.SECURITY,
            wageType = WageType.DAILY,
            base = ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 11, 1), 120_000))),
            items = listOf(
                PayItem("night", "야간수당", ValueTimeline(), Cadence.PER_NIGHT_SHIFT,
                    covers = setOf(com.bamnat.core.pay.LineKind.NIGHT)),
                PayItem("fixed", "고정 연장수당", ValueTimeline(listOf(DatedValue(LocalDate.of(2025, 11, 1), 200_000))), Cadence.MONTHLY,
                    fixedOvertimeMinutes = 20 * 60, covers = emptySet()),
            ),
            dailyHours = 6 * 60,
            annual = com.bamnat.core.pay.AnnualPay(39_000_000, split = 13, fixedOvertimeMonthly = 200_000, regularBonusYearly = 0, otherMonthly = 100_000),
            verified = com.bamnat.core.pay.PayslipCheck(java.time.YearMonth.of(2026, 9), 3_120_000, 3_118_500, 1_760_000_000_000),
            estimatedFields = setOf(com.bamnat.core.pay.Unknowns.FIVE_OR_MORE, com.bamnat.core.pay.Unknowns.item("night")),
            rules = CompanyRules(supervisoryApproved = true, holidaysUnderFive = true, breakPay = com.bamnat.core.pay.BreakPay.AS_WORK),
        )
        val back = PayCodec.decode(PayCodec.encode(p))
        assertEquals(p, back)
        // An allowance with no choice keeps none, so it takes its default again.
        assertNull(PayCodec.decode(PayCodec.encode(p.copy(items = listOf(p.items[1].copy(covers = null))))).items[0].covers)
    }

    /** [PAYS-03] A file from 1.11 reads with the new fields at their defaults; values from a newer version do not fail it. */
    @Test fun olderAndNewerFilesStillRead() {
        val json = PayCodec.encode(PayProfile(Occupation.NURSE, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 2_500_000)))))
        listOf("dailyHours", "annual", "verified").forEach(json::remove)
        json.getJSONObject("rules").apply { remove("supervisory"); remove("holidaysU5"); remove("breakPay") }
        val old = PayCodec.decode(json)
        assertEquals(8 * 60, old.dailyHours)
        assertNull(old.annual)
        assertNull(old.verified)
        assertEquals(CompanyRules(), old.rules.copy(extraNames = emptyMap()))
        json.put("occupation", "PILOT").put("wageType", "WEEKLY").put("dailyHours", 99_999)
        json.getJSONObject("rules").put("breakPay", "SOMETIMES")
        val newer = PayCodec.decode(json)
        assertEquals(Occupation.CUSTOM, newer.occupation)
        assertEquals(WageType.MONTHLY, newer.wageType)
        assertEquals(8 * 60, newer.dailyHours)
        assertEquals(com.bamnat.core.pay.BreakPay.UNPAID, newer.rules.breakPay)
    }
}
