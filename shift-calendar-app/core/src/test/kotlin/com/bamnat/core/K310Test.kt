package com.bamnat.core

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.invest.InvestTaxes
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PublicPayTable
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.remote.AlarmGuides
import com.bamnat.core.remote.RemoteFileException
import com.bamnat.core.remote.RemoteFiles
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 1.23 원격 자료 (row 310): 공무원 봉급표·공휴일표·투자 세율·알람 안내 kind, and the rates version on a closed month. */
class K310Test {
    @After fun reset() {
        PublicPayTable.use(null); KoreanHolidays.use(null); InvestTaxes.use(null); AlarmGuides.use(null); RateTable.use(null)
    }

    private fun file(kind: String, data: String, version: Long = 2027010101) = RemoteFiles.parse(
        """{"format":1,"kind":"$kind","version":$version,"issued":"2026-12-20","minApp":34,"data":$data}""".toByteArray(),
    )

    private fun refused(block: () -> Unit) = try { block(); fail("accepted") } catch (e: RemoteFileException) { }

    @Test fun theNewKindsShipAndAreFetched() {
        for (k in listOf(RemoteFiles.PUBLICPAY, RemoteFiles.HOLIDAYS, RemoteFiles.TAXES, RemoteFiles.ALARM_GUIDE)) {
            assertTrue(k in RemoteFiles.KINDS)
            assertEquals(k, RemoteFiles.bundled(k)!!.kind)
        }
    }

    @Test fun builtInPublicPayFileMatchesTheCode() {
        val f = RemoteFiles.bundled(RemoteFiles.PUBLICPAY)!!
        assertEquals(PublicPayTable.BUILT_IN_VERSION, f.version)
        val read = PublicPayTable.read(f)
        assertEquals(PublicPayTable.tables.size, read.size)
        PublicPayTable.tables.zip(read).forEach { (a, b) ->
            assertEquals(a.from, b.from)
            assertEquals(a.source, b.source)
            a.general.zip(b.general).forEach { (x, y) -> assertArrayEquals(x, y) }
            a.policeFire.zip(b.policeFire).forEach { (x, y) -> assertArrayEquals(x, y) }
            assertEquals(a.general.size, b.general.size)
            assertEquals(a.policeFire.size, b.policeFire.size)
        }
    }

    @Test fun a2027PublicPayFileChangesTheSalaryFromItsDay() {
        val nine = PublicPayTable.grade("GENERAL:9급")!!
        assertEquals(2_133_000L, PublicPayTable.salary(nine, 1, LocalDate.of(2027, 3, 1)))
        val cols = { n: Int, v: Long -> (1..n).joinToString(",", "[", "]") { "[${v + it}, ${v + it + 100_000}]" } }
        PublicPayTable.use(file("publicpay", """{"tables":[{"from":"2027-01-01","source":"시험","general":${cols(9, 2_200_000)},"policeFire":${cols(10, 2_300_000)}}]}"""))
        assertEquals(2_200_009L, PublicPayTable.salary(nine, 1, LocalDate.of(2027, 3, 1)))
        assertEquals(2_133_000L, PublicPayTable.salary(nine, 1, LocalDate.of(2026, 12, 1)))
        assertEquals(2, PublicPayTable.maxStep(nine, LocalDate.of(2027, 3, 1)))
        refused { PublicPayTable.read(file("publicpay", """{"tables":[{"from":"2027-01-01","general":${cols(8, 2_200_000)},"policeFire":${cols(10, 2_300_000)}}]}""")) }
        refused { PublicPayTable.read(file("publicpay", """{"tables":[{"from":"2027-01-01","general":[[3000000,2000000]],"policeFire":[]}]}""")) }
        refused { PublicPayTable.read(file("publicpay", """{"tables":[]}""")) }
        // An older file than the app's own is ignored.
        PublicPayTable.use(file("publicpay", """{"tables":[{"from":"2027-01-01","general":${cols(9, 2_200_000)},"policeFire":${cols(10, 2_300_000)}}]}""",
            version = PublicPayTable.BUILT_IN_VERSION))
        assertEquals(2_133_000L, PublicPayTable.salary(nine, 1, LocalDate.of(2027, 3, 1)))
    }

    @Test fun builtInHolidayFileMatchesTheCode() {
        val f = KoreanHolidays.read(RemoteFiles.bundled(RemoteFiles.HOLIDAYS)!!)
        assertEquals(KoreanHolidays.VERSION, f.version)
        assertEquals(KoreanHolidays.years, f.years)
        assertEquals(KoreanHolidays.builtInDays, f.days)
        assertEquals(KoreanHolidays.BUILT_IN_FILE_VERSION, RemoteFiles.bundled(RemoteFiles.HOLIDAYS)!!.version)
    }

    @Test fun aHolidayFileAddsAnAnnouncedDayAndANewYear() {
        assertNull(KoreanHolidays.nameOf(LocalDate.of(2027, 3, 2)))
        val y27 = KoreanHolidays.builtInDays.filterKeys { it.year == 2027 }.entries
            .joinToString(",") { """{"date":"${it.key}","name":"${it.value}"}""" }
        KoreanHolidays.use(file("holidays", """{"version":"2027.01a","fromYear":2027,"toYear":2028,"days":[$y27,
            {"date":"2027-03-02","name":"임시공휴일"},{"date":"2028-01-01","name":"신정"},{"date":"2028-12-25","name":"성탄절"}]}"""))
        assertEquals("임시공휴일", KoreanHolidays.nameOf(LocalDate.of(2027, 3, 2)))
        assertEquals("2027.01a", KoreanHolidays.version)
        assertEquals(2025..2028, KoreanHolidays.years)
        // 2028 is the file's: only what it lists (the rules would have found 설날).
        assertNull(KoreanHolidays.nameOf(LocalDate.of(2028, 1, 26)))
        // 2026 still the built-in table.
        assertEquals("추석", KoreanHolidays.nameOf(LocalDate.of(2026, 9, 25)))
        KoreanHolidays.use(null)
        assertEquals(KoreanHolidays.VERSION, KoreanHolidays.version)
        assertNull(KoreanHolidays.nameOf(LocalDate.of(2027, 3, 2)))
        refused { KoreanHolidays.read(file("holidays", """{"version":"x","fromYear":2027,"toYear":2027,"days":[{"date":"2027-01-01","name":"신정"}]}""")) }
        refused { KoreanHolidays.read(file("holidays", """{"version":"x","fromYear":2027,"toYear":2027,"days":[{"date":"2028-01-01","name":"신정"}]}""")) }
    }

    @Test fun builtInTaxesFileMatchesTheCode() {
        val f = RemoteFiles.bundled(RemoteFiles.TAXES)!!
        assertEquals(InvestTaxes.BUILT_IN_VERSION, f.version)
        assertEquals(InvestTaxes.BUILT_IN, InvestTaxes.read(f))
    }

    @Test fun aTaxesFileMovesTheCryptoYear() {
        fun data(year: Int, rate: String) = """{"foreignDeduction":2500000,"capitalGainsRate":$rate,"cryptoDeduction":2500000,"cryptoFromYear":$year,
            "isaLimit":2000000,"isaLimitLowIncome":4000000,"isaOverRate":0.099,"financialIncomeLimit":20000000,"dividendRate":0.154,"usDividendRate":0.15}"""
        InvestTaxes.use(file("taxes", data(2028, "0.22")))
        assertEquals(2028, InvestTaxes.CRYPTO_FROM_YEAR)
        assertEquals(2_500_000L, InvestTaxes.FOREIGN_DEDUCTION)
        refused { InvestTaxes.read(file("taxes", data(2028, "22"))) }
        InvestTaxes.use(null)
        assertEquals(2027, InvestTaxes.CRYPTO_FROM_YEAR)
    }

    @Test fun alarmGuideFrameReadsGuidesAndRefusesUnsafeOnes() {
        assertTrue(AlarmGuides.read(RemoteFiles.bundled(RemoteFiles.ALARM_GUIDE)!!).isEmpty())
        AlarmGuides.use(file("alarm-guide", """{"guides":[{"maker":"Samsung","title":"절전 예외","steps":["설정 열기","배터리"],"link":"https://example.com/a"}]}"""))
        assertEquals(listOf("설정 열기", "배터리"), AlarmGuides.forMaker("samsung")!!.steps)
        assertNull(AlarmGuides.forMaker("google"))
        refused { AlarmGuides.read(file("alarm-guide", """{"guides":[{"maker":"x","title":"t","steps":["a"],"link":"http://x"}]}""")) }
        refused { AlarmGuides.read(file("alarm-guide", """{"guides":[{"maker":"x","title":"t","steps":[]}]}""")) }
    }

    @Test fun aClosedMonthKeepsTheRemoteRatesVersion() {
        // [REMOTE-06] A newer rates file's version is what the month's 계산 기준 and its 마감 snapshot say.
        RateTable.use(file("rates", """{"rates":[{"from":"2027-01-01","version":"2027.9","source":"시험","minimumHourlyWage":10700,
            "pensionRate":0.095,"healthRate":0.0719,"longTermCareRate":0.009448,"unemploymentRate":0.018}]}"""))
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(LocalDate.of(2027, 1, 4), listOf(1L, 1L, 1L, 1L, 1L, null, null)), emptyList())
        val p = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_000_000))))
        val pay = PayCalculator().calculate(p, YearMonth.of(2027, 2), r::resolve)
        assertEquals("2027.9", pay.ratesVersion)
        assertEquals("2027.9", com.bamnat.core.pay.CalcSnapshot.close(pay, 1L).ratesVersion)
    }
}
