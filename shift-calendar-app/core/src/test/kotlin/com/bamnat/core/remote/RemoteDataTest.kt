package com.bamnat.core.remote

import com.bamnat.core.money.TradeWages
import com.bamnat.core.pay.RateFile
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.WithholdingTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [REMOTE-02][PAYR-04][PAYR-05] The rates, 간이세액표 and 노임단가 files, built in and laid over by a newer one. */
class RemoteDataTest {
    @After fun reset() {
        RateTable.use(null)
        WithholdingTable.use(null)
        TradeWages.use(null)
    }

    private fun file(kind: String, data: String, version: Long = 2027010101) = RemoteFiles.parse(
        """{"format":1,"kind":"$kind","version":$version,"issued":"2026-12-20","minApp":33,"data":$data}""".toByteArray(),
    )

    @Test fun builtInRatesFileMatchesTheCode() {
        val f = RemoteFiles.bundled(RemoteFiles.RATES)!!
        assertEquals(RateTable.BUILT_IN_VERSION, f.version)
        val d = RateFile.read(f)
        assertEquals(RateTable.builtIn, d.rates)
        assertEquals(RateTable.builtInBases, d.bases)
    }

    @Test fun a2027RatesFileChangesTakeHome() {
        val feb27 = LocalDate.of(2027, 2, 1)
        fun care(on: LocalDate) = TakeHome.estimate(3_000_000, TaxMode.INSURANCE_4, on = on).deductions.toMap()["장기요양"]
        assertEquals(14_170L, care(feb27))
        // A made-up 2027 장기요양 rate (1.0% of pay) and new 2027.7 limits, as the 장기요양위원회 file would bring.
        RateTable.use(file("rates", """{"rates":[{"from":"2027-01-01","version":"2027.2","source":"시험","minimumHourlyWage":10700,
            "pensionRate":0.10,"healthRate":0.0719,"longTermCareRate":0.010,"unemploymentRate":0.018}],
            "pensionBases":[{"from":"2027-07-01","min":420000,"max":6800000}]}"""))
        // 건강보험 107,850 × (1.0% / 7.19%) = 15,000원 (10원 미만 버림).
        assertEquals(15_000L, care(feb27))
        assertEquals(14_170L, care(LocalDate.of(2026, 12, 1)))
        assertEquals(340_000L, TakeHome.estimate(10_000_000, TaxMode.INSURANCE_4, on = LocalDate.of(2027, 8, 1)).deductions.toMap()["국민연금"])
        assertEquals(10_700, RateTable.on(feb27).minimumHourlyWage)
        assertEquals(3, RateTable.current.size)
        RateTable.use(null)
        assertEquals(14_170L, care(feb27))
    }

    @Test fun anOldRatesFileIsIgnoredAndABadOneRefused() {
        RateTable.use(file("rates", """{"rates":[{"from":"2027-01-01","version":"x","minimumHourlyWage":99999}]}""", version = RateTable.BUILT_IN_VERSION))
        assertEquals(10_700, RateTable.on(LocalDate.of(2027, 3, 1)).minimumHourlyWage)
        for (bad in listOf("""{"rates":[{"from":"2027-01-01","version":"x","minimumHourlyWage":100}]}""",
            """{"rates":[{"from":"2027-13-01","version":"x","minimumHourlyWage":10700}]}""",
            """{"rates":[{"from":"2027-01-01","version":"x","minimumHourlyWage":10700,"healthRate":7.19}]}"""))
            try { RateTable.use(file("rates", bad)); throw AssertionError(bad) } catch (e: RemoteFileException) { }
    }

    // Golden values read from 소득세법 시행령 별표 2 (2026-02-27 개정) rows.
    @Test fun simplifiedTaxTableGoldenRows() {
        val t = WithholdingTable.on(LocalDate.of(2026, 10, 1))!!
        assertEquals(LocalDate.of(2026, 3, 1), t.from)
        assertEquals(646, t.rows.size)
        assertEquals(0L, t.tax(769_999, 1))
        assertEquals(19_520L, t.tax(2_000_000, 1))
        assertEquals(19_520L, t.tax(2_009_999, 1))
        assertEquals(14_750L, t.tax(2_000_000, 2))
        assertEquals(74_350L, t.tax(3_000_000, 1))
        assertEquals(28_600L, t.tax(2_500_000, 2))
        assertEquals(335_470L, t.tax(5_000_000, 1))
        // 8~20세 자녀 2명: 45,830원 less.
        assertEquals(219_100L - 45_830, t.tax(5_000_000, 4, children = 2))
        assertEquals(219_100L - 45_830 - 33_330, t.tax(5_000_000, 4, children = 3))
        assertEquals(0L, t.tax(2_000_000, 2, children = 1))
        assertEquals(1_503_990L, t.tax(9_999_999, 1))
        assertEquals(1_507_400L, t.tax(10_000_000, 1))
        // 1천만 원 초과 1,400만 원 이하: 10,000천원 세액 + 초과분 × 98% × 35% + 25,000원.
        assertEquals(1_507_400L + 686_000 + 25_000, t.tax(12_000_000, 1))
        assertEquals(1_507_400L + 1_397_000 + 744_800, t.tax(16_000_000, 1))
        assertEquals(1_507_400L + 13_394_600 + 4_200_000, t.tax(55_000_000, 1))
        // 12명: 11명 세액 − (10명 − 11명) × 1.
        assertEquals(87_850L - (106_600 - 87_850), t.tax(5_000_000, 12))
    }

    @Test fun takeHomeUsesTheTableAndLeavesOutNonTaxablePay() {
        val d = TakeHome.estimate(2_200_000, TaxMode.INSURANCE_4, on = LocalDate.of(2026, 10, 1), nonTaxable = 200_000).deductions.toMap()
        assertEquals(19_520L, d[TakeHome.TAX_LABEL])
        assertEquals(1_950L, d[TakeHome.LOCAL_TAX_LABEL])
        val kids = TakeHome.estimate(5_000_000, TaxMode.INSURANCE_4, dependents = 4, children = 2, on = LocalDate.of(2026, 10, 1))
        assertEquals(173_270L, kids.deductions.toMap()[TakeHome.TAX_LABEL])
    }

    @Test fun aNewTableFileReplacesTheYear() {
        val rows = WithholdingTable.on(LocalDate.of(2026, 3, 1))!!.rows.joinToString(",") { r -> r.joinToString(",", "[", "]") }
        WithholdingTable.use(file("taxtable", """{"tables":[{"from":"2027-03-01","top":[1,1,1,1,1,1,1,1,1,1,1],"over":[],
            "children":{"one":1,"two":2,"more":3},"rows":[$rows]}]}"""))
        assertEquals(LocalDate.of(2027, 3, 1), WithholdingTable.on(LocalDate.of(2027, 4, 1))!!.from)
        assertEquals(19_520L - 1, WithholdingTable.on(LocalDate.of(2027, 4, 1))!!.tax(2_000_000, 1, 1))
        assertEquals(LocalDate.of(2026, 3, 1), WithholdingTable.on(LocalDate.of(2026, 4, 1))!!.from)
        assertNull(WithholdingTable.on(LocalDate.of(2026, 2, 1)))
    }

    @Test fun constructionWagesAreTheSecondHalf2026() {
        assertEquals("2026년 하반기", TradeWages.current.label)
        assertEquals(LocalDate.of(2026, 9, 1), TradeWages.current.from)
        assertEquals(29, TradeWages.ALL.size)
        assertEquals(172_698L, TradeWages.of("보통인부")!!.daily)
        assertEquals(282_990L, TradeWages.of("건축 목공")!!.daily)
        assertTrue("하반기" in TradeWages.SOURCE_SHORT)
        TradeWages.use(file("wages", """{"construction":{"from":"2027-01-01","label":"2027년 상반기","source":"시험",
            "trades":[{"name":"보통인부","daily":180000,"group":"공통"}]}}"""))
        assertEquals(180_000L, TradeWages.of("보통인부")!!.daily)
        assertNull(TradeWages.of("타일공"))
    }

    // [LAWX-03] 사장님 인건비의 사업주 몫도 그해 요율: 2027 국민연금 5%.
    @Test fun laborCostFollowsTheYear() {
        assertEquals(199_340L, com.bamnat.core.work.LaborCost.employerInsurance(2_000_000, LocalDate.of(2026, 5, 1)))
        assertEquals(204_340L, com.bamnat.core.work.LaborCost.employerInsurance(2_000_000, LocalDate.of(2027, 5, 1)))
    }
}
