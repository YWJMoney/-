package com.bamnat.core

import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PublicServantPay
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [JOB-01] 공무원수당 규정's 산식 from a 봉급, and a first public-servant setup with no 근로기준법 premiums. */
class PublicServantPayTest {
    @Test fun formulas() {
        // 9급 10호봉 2,542,700원 × 60% = 1,525,620원 (the worked example in the research note).
        val sb = PublicServantPay.standardBase(2_542_700, lowGrade = true)
        assertEquals(1_525_620L, sb)
        assertEquals(3_650L, PublicServantPay.nightPerHour(sb))
        assertEquals(10_949L, PublicServantPay.overtimePerHour(sb))
        assertEquals(88_017L, PublicServantPay.holidayPerDay(sb))
    }

    @Test fun firstSetupForPolice() {
        val p = PayProfile(Occupation.POLICE, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))))
        val set = PayPresets.firstSetup(p)
        assertEquals(0.0, set.rules.nightPremium!!, 0.0)
        assertEquals(3, set.customRules.size)
        assertEquals(RulePay.PerHour(PublicServantPay.nightPerHour(1_650_000)), set.customRules.first { it.id == "public-night" }.pay)
        assertTrue(PublicServantPay.ESTIMATED in set.estimatedFields)
        // Rules already made are never replaced.
        assertEquals(set, PayPresets.firstSetup(set))
        // Other jobs are left alone.
        val nurse = p.copy(occupation = Occupation.NURSE)
        assertEquals(nurse, PayPresets.firstSetup(nurse))
        assertEquals(CompanyRules(), nurse.rules)
    }
}
