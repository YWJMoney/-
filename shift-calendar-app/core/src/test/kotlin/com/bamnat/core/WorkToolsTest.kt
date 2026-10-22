package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.DailyWorkerTax
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.Quote
import com.bamnat.core.money.QuoteItem
import com.bamnat.core.money.QuoteVat
import com.bamnat.core.money.TradeWages
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.work.HourlyWeek
import com.bamnat.core.work.JobOffer
import com.bamnat.core.work.LaborCost
import com.bamnat.core.work.OfferCompare
import com.bamnat.core.work.QuitCheck
import com.bamnat.core.work.SimpleTaxLimit
import com.bamnat.core.work.Staff
import com.bamnat.core.work.StaffRoster
import com.bamnat.core.work.StaffShift
import com.bamnat.core.work.Weather
import com.bamnat.core.work.WeatherNote
import com.bamnat.core.work.WeatherSales
import com.bamnat.core.work.WorkContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs

/** [HRLY-09][HRLY-10][HRLY-11][FLD-11][FLD-12][FLD-13][SHOP-09][SHOP-10][SHOP-11] The P3 tools of part-time, field and shop calendars. */
class WorkToolsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun offerCountsHolidayPayAndAMonthOfWeeks() {
        val a = OfferCompare.of(JobOffer(hourlyWage = 10_320, hoursPerWeek = 20.0, daysPerWeek = 5))
        assertEquals(206_400, a.weeklyBase)
        // 20시간 ÷ 40 × 8 = 4시간.
        assertEquals(41_280, a.weeklyHolidayPay)
        assertEquals(1_076_229, a.monthlyGross)
        assertEquals(a.monthlyGross, a.monthlyNet)
        assertTrue(a.holidayEligible)
        assertFalse(a.belowMinimum)
        assertEquals(12_384, a.perWorkHour)
    }

    @Test
    fun aHigherWageFarAwayCanKeepLessPerHour() {
        val near = OfferCompare.of(JobOffer(hourlyWage = 10_320, hoursPerWeek = 20.0, daysPerWeek = 5))
        val far = OfferCompare.of(JobOffer(hourlyWage = 12_000, hoursPerWeek = 20.0, daysPerWeek = 5, holidayPay = false,
            commuteMinutes = 60, commuteCost = 3_000))
        assertEquals(0, far.weeklyHolidayPay)
        assertTrue(abs(far.realPerHour - 9_000) <= 1)
        assertEquals(0, OfferCompare.best(listOf(near, far)))
        assertNull(OfferCompare.best(listOf(near)))
    }

    @Test
    fun offerPremiumsNeedFivePeopleAndTaxComesOff() {
        val five = OfferCompare.of(JobOffer(hourlyWage = 10_000, hoursPerWeek = 10.0, nightHoursPerWeek = 4.0, taxMode = TaxMode.WITHHOLDING_3_3))
        assertEquals(20_000, five.weeklyPremium)
        assertEquals(0, five.weeklyHolidayPay)
        assertEquals(five.monthlyGross - five.deductions.sumOf { it.second }, five.monthlyNet)
        val small = OfferCompare.of(JobOffer(hourlyWage = 10_000, hoursPerWeek = 10.0, nightHoursPerWeek = 4.0, fiveOrMore = false))
        assertEquals(0, small.weeklyPremium)
        assertTrue(OfferCompare.of(JobOffer(hourlyWage = 9_000, hoursPerWeek = 10.0)).belowMinimum)
    }

    @Test
    fun contractEndingSoon() {
        val c = WorkContract(end = d(2026, 11, 10))
        assertEquals(21L, c.daysLeft(d(2026, 10, 20)))
        assertTrue(c.endingSoon(d(2026, 10, 20)))
        assertFalse(c.endingSoon(d(2026, 9, 1)))
        assertFalse(WorkContract().endingSoon(d(2026, 10, 20)))
        assertTrue(WorkContract(hourlyWage = 11_000).wageDiffers(10_320))
    }

    @Test
    fun lastWeekHolidayPayNeedsTheWholeWeekEmployed() {
        val week = HourlyWeek(d(2026, 10, 12), 20 * 60, 0)
        assertFalse(QuitCheck.lastWeek(week, d(2026, 10, 16), 10_320).owed)
        val sunday = QuitCheck.lastWeek(week, d(2026, 10, 18), 10_320)
        assertTrue(sunday.owed)
        assertEquals(41_280, sunday.pay)
        assertFalse(QuitCheck.lastWeek(week.copy(absent = 1), d(2026, 10, 18), 10_320).owed)
    }

    @Test
    fun severanceNeedsAYearAndFifteenHours() {
        val s = QuitCheck.severance(d(2025, 3, 1), d(2026, 3, 31), 20.0, 10_320)
        assertTrue(s.eligible)
        assertEquals(35_383, s.averageDaily)
        assertEquals(Math.round(35_383 * 30.0 * 396 / 365), s.amount)
        assertFalse(QuitCheck.severance(d(2025, 6, 1), d(2026, 3, 31), 20.0, 10_320).eligible)
        assertFalse(QuitCheck.severance(d(2024, 6, 1), d(2026, 3, 31), 14.0, 10_320).eligible)
        assertFalse(QuitCheck.severance(null, d(2026, 3, 31), 20.0, 10_320).eligible)
        // 365 days across 29 February are a day short of a year; the full year is.
        assertFalse(QuitCheck.severance(d(2023, 3, 2), d(2024, 2, 29), 20.0, 10_320).eligible)
        assertTrue(QuitCheck.severance(d(2023, 3, 2), d(2024, 3, 1), 20.0, 10_320).eligible)
        assertTrue(QuitCheck.severance(d(2025, 3, 1), d(2026, 2, 28), 20.0, 10_320).eligible)
    }

    @Test
    fun leaveDaysAndPayBy() {
        assertEquals(7, QuitCheck.leaveDays(d(2026, 1, 1), d(2026, 7, 31), 20.0))
        assertEquals(11, QuitCheck.leaveDays(d(2025, 1, 1), d(2025, 12, 31), 20.0))
        assertEquals(15, QuitCheck.leaveDays(d(2025, 1, 1), d(2026, 1, 1), 20.0))
        assertEquals(0, QuitCheck.leaveDays(d(2025, 1, 1), d(2026, 1, 1), 10.0))
        assertEquals(41_280, QuitCheck.leaveDayPay(20.0, 10_320))
        assertEquals(d(2026, 11, 3), QuitCheck.payBy(d(2026, 10, 20)))
    }

    @Test
    fun patternsStopAfterTheLastDay() {
        val week = WorkPattern.Weekly(d(2026, 1, 1), mapOf(DayOfWeek.MONDAY to 1L))
        val later = WorkPattern.Weekly(d(2026, 11, 5), mapOf(DayOfWeek.TUESDAY to 1L))
        val ended = QuitCheck.endPatterns(listOf(week, later), d(2026, 10, 31))
        assertEquals(1, ended.size)
        assertEquals(d(2026, 10, 31), ended[0].endDate)
        val early = WorkPattern.Weekly(d(2026, 1, 1), emptyMap(), d(2026, 3, 1))
        assertEquals(d(2026, 3, 1), QuitCheck.endPatterns(listOf(early), d(2026, 10, 31))[0].endDate)
    }

    @Test
    fun quoteAddsOrTakesOutVat() {
        val items = listOf(QuoteItem("철거", 2.0, "식", 50_000), QuoteItem("타일", 10.0, "m²", 15_000))
        val q = Quote(date = d(2026, 10, 20), client = "민지", items = items, vat = QuoteVat.ADDED)
        assertEquals(250_000, q.supply)
        assertEquals(25_000, q.vatAmount)
        assertEquals(275_000, q.total)
        val inc = q.copy(vat = QuoteVat.INCLUDED)
        assertEquals(227_273, inc.supply)
        assertEquals(22_727, inc.vatAmount)
        assertEquals(250_000, inc.total)
        assertEquals(250_000, q.copy(vat = QuoteVat.NONE).total)
        val job = q.toJob()
        assertEquals(JobStatus.TENTATIVE, job.status)
        assertEquals(275_000, job.amount)
        assertEquals("철거", job.title)
        assertEquals(d(2026, 11, 19), q.validUntil)
    }

    @Test
    fun tradeWagesAreThere() {
        assertEquals(172_068, TradeWages.find("보통인부").single().daily)
        assertTrue(TradeWages.find("").size >= 20)
        assertTrue(TradeWages.ALL.map { it.name }.toSet().size == TradeWages.ALL.size)
    }

    @Test
    fun dailyWorkerTaxIsTwoPointSevenOverTheDeduction() {
        assertEquals(1_350, DailyWorkerTax.incomeTaxOfDay(200_000))
        assertEquals(DailyWorkerTax.Tax(1_350, 135), DailyWorkerTax.paidTogether(listOf(200_000)))
        assertEquals(0, DailyWorkerTax.incomeTaxOfDay(150_000))
        // 18만 원 a day is 810원, not taken day by day, but taken when five days are paid together.
        val five = List(5) { 180_000L }
        assertEquals(0, DailyWorkerTax.paidDaily(five).total)
        assertEquals(DailyWorkerTax.Tax(4_050, 405), DailyWorkerTax.paidTogether(five))
        assertEquals(187_037, DailyWorkerTax.TAX_FREE_UP_TO)
        assertEquals(999, DailyWorkerTax.incomeTaxOfDay(DailyWorkerTax.TAX_FREE_UP_TO))
    }

    @Test
    fun dailyWorkerMonthGroupsDayRateJobsByDay() {
        val jobs = listOf(
            Job(date = d(2026, 10, 1), title = "1공", amount = 200_000, units = 1.0),
            Job(date = d(2026, 10, 2), title = "1공", amount = 180_000, units = 1.0),
            Job(date = d(2026, 10, 3), title = "촬영", amount = 500_000),
            Job(date = d(2026, 10, 4), title = "1공", amount = 200_000, units = 1.0, status = JobStatus.CANCELLED),
        )
        val m = DailyWorkerTax.month(YearMonth.of(2026, 10), jobs)
        assertEquals(2, m.days)
        assertEquals(380_000, m.wages)
        assertEquals(2_160, m.together.income)
        assertEquals(1_350, m.daily.income)
    }

    @Test
    fun simpleTaxLimitWarnsWhereTheYearIsHeading() {
        val sales = listOf(DailySales(d(2026, 6, 30), 60_000_000), DailySales(d(2025, 12, 1), 90_000_000))
        val y = SimpleTaxLimit.of(d(2026, 7, 1), sales)
        assertEquals(60_000_000, y.sales)
        assertEquals(SimpleTaxLimit.Level.HEADING_OVER, y.level)
        assertEquals(57, y.percent)
        assertEquals(SimpleTaxLimit.Level.UNDER, SimpleTaxLimit.of(d(2026, 12, 31), listOf(DailySales(d(2026, 6, 1), 10_000_000))).level)
        assertEquals(SimpleTaxLimit.Level.NEAR, SimpleTaxLimit.of(d(2026, 12, 31), listOf(DailySales(d(2026, 6, 1), 90_000_000))).level)
        assertEquals(SimpleTaxLimit.Level.OVER, SimpleTaxLimit.of(d(2026, 12, 31), listOf(DailySales(d(2026, 6, 1), 104_000_000))).level)
        assertNull(SimpleTaxLimit.of(d(2026, 1, 10), sales).projected)
    }

    @Test
    fun rainyDaysAgainstSunnyDays() {
        val notes = listOf(
            WeatherNote(d(2026, 10, 1), Weather.SUNNY), WeatherNote(d(2026, 10, 2), Weather.SUNNY, 18),
            WeatherNote(d(2026, 10, 3), Weather.RAIN), WeatherNote(d(2026, 10, 4), Weather.SNOW),
        )
        val sales = listOf(DailySales(d(2026, 10, 1), 1_000_000), DailySales(d(2026, 10, 2), 800_000), DailySales(d(2026, 10, 3), 630_000))
        val rows = WeatherSales.compare(notes, sales, d(2026, 10, 1), d(2026, 10, 31))
        assertEquals(listOf(Weather.SUNNY, Weather.RAIN), rows.map { it.weather })
        assertEquals(900_000, rows[0].average)
        assertEquals(-30, WeatherSales.rainAgainstSun(rows))
        assertNull(WeatherSales.rainAgainstSun(rows.take(1)))
    }

    @Test
    fun laborCostCountsHolidayPayAndTheEmployerShare() {
        val shift = StaffShift(LocalTime.of(9, 0), LocalTime.of(13, 0))
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val s = Staff("a", "준호", 10_000, holidayPay = true, insured = true, shifts = weekdays.associateWith { shift })
        val m = LaborCost.month(StaffRoster(listOf(s), legalBreaks = false), YearMonth.of(2026, 11))
        val line = m.lines.single()
        assertEquals(21 * 4 * 60, line.workMinutes)
        assertEquals(840_000, line.base)
        assertEquals(200_000, line.holidayPay)
        assertEquals(103_650, line.insurance)
        assertEquals(1_143_650, m.total)
        assertEquals(38, m.ratio(3_000_000))
        assertNull(m.ratio(0))
        // With the legal break a 4-hour shift is paid 3.5 hours.
        assertEquals(210, shift.paidMinutes(true))
        // 8 hours on the clock: 30 minutes off leaves 7½ hours, which needs no more.
        assertEquals(450, StaffShift(LocalTime.of(22, 0), LocalTime.of(6, 0)).paidMinutes(true))
        assertEquals(60 * 8, StaffShift(LocalTime.of(22, 0), LocalTime.of(7, 0)).paidMinutes(true))
    }
}
