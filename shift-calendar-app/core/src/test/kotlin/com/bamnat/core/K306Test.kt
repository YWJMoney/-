package com.bamnat.core

import com.bamnat.core.imports.AttendanceImport
import com.bamnat.core.imports.DeviceEvent
import com.bamnat.core.imports.Sheets
import com.bamnat.core.money.CardCycle
import com.bamnat.core.money.EntryPart
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.AnnualPay
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Festival
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayPeriod
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.WeeklyHoliday
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [PAYX-23][IMPORT-04] 순번 306 S 묶음 (research/1.23-리뷰/K-급여-연차-돈-가져오기.md P1·P3·P4·P11·P13·P14·X1·X2·X4). */
class K306Test {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private fun money(v: Long) = ValueTimeline(listOf(DatedValue(LocalDate.of(2020, 1, 1), v)))
    private val day = ShiftType(10, "주간", "주", 0xFF000000, t(9), t(18), 60, t(12))
    private val off = ShiftType(11, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val weekdays = ScheduleResolver(listOf(day, off),
        WorkPattern.Rotation(LocalDate.of(2026, 11, 2), List(5) { day.id } + List(2) { off.id }), emptyList())

    @Test
    fun `P1 the minimum wage warning names the year of the rates used and a new year inside the period`() {
        // 통상시급 10,500원: above 2026's 10,320원, below 2027's 10,700원. January's pay covers 12/21~1/20.
        val p = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, money(10_500L * 209), period = PayPeriod(endDay = 20))
        val w = PayCalculator().calculate(p, YearMonth.of(2027, 1), weekdays::resolve).warnings
        assertTrue(w.none { "2027년 최저임금" in it && "보다 낮아요. 입력값" in it })
        assertTrue(w.any { "급여기간 중 1/1부터 2027년 최저임금 10,700원" in it })
        // A calendar-month January is all 2027: the plain warning names 2027.
        val cal = PayCalculator().calculate(p.copy(period = PayPeriod()), YearMonth.of(2027, 1), weekdays::resolve).warnings
        assertTrue(cal.any { "2027년 최저임금 10,700원보다 낮아요" in it })
    }

    @Test
    fun `P3 a 설 bonus paid the month before counts once a year for the ordinary wage`() {
        val seol = PayItem("seol", "설 상여", money(600_000), Cadence.BONUS, ordinary = true,
            festivals = setOf(Festival.SEOLLAL), festivalMonthBefore = true)
        val s27 = Festival.SEOLLAL.date(2027)!!
        val s28 = Festival.SEOLLAL.date(2028)!!
        // 설 2027 falls in February and 설 2028 in January, so 2027 pays it in January and December.
        assertEquals(2, s27.monthValue)
        assertEquals(1, s28.monthValue)
        assertEquals(2, seol.timesInYear(2027))
        assertEquals(1, seol.yearlyTimes)
        assertEquals(50_000, seol.bonusMonthlyShare(0, YearMonth.of(2027, 6)))
    }

    @Test
    fun `P4 with no weekly holiday the range also runs Sunday as the weekly holiday`() {
        val p = Unknowns.markUnknown(PayProfile(Occupation.CUSTOM, WageType.HOURLY, money(12_000)), Unknowns.REST_DAY_WORK)
        assertEquals(WeeklyHoliday.Unknown, p.rules.weeklyHoliday)
        val u = Unknowns.of(p).single { it.key == Unknowns.WEEKLY_HOLIDAY }
        assertTrue("휴무일로" in u.assumed)
        val sunday = u.answers.single { it.label == "일요일" }.apply(p)
        assertEquals(WeeklyHoliday.Fixed(java.time.DayOfWeek.SUNDAY), sunday.rules.weeklyHoliday)
        // Set in 회사 규칙: no longer asked; nor when 쉬는 날 근무 is answered.
        assertTrue(Unknowns.of(sunday).none { it.key == Unknowns.WEEKLY_HOLIDAY })
        assertTrue(Unknowns.of(Unknowns.answered(p, Unknowns.REST_DAY_WORK)).none { it.key == Unknowns.WEEKLY_HOLIDAY })
    }

    @Test
    fun `P4 an hourly worker's Sunday shift on a public holiday is paid when no weekly holiday is set`() {
        // Every day a 09~18 shift; 2026-05-24 (일) is not a holiday, so use 2026-03-01 (일, 삼일절).
        val all = ScheduleResolver(listOf(day), WorkPattern.Rotation(LocalDate.of(2026, 1, 5), List(7) { day.id }), emptyList())
        val p = PayProfile(Occupation.CUSTOM, WageType.HOURLY, money(12_000))
        val r = PayCalculator().calculate(p, YearMonth.of(2026, 3), all::resolve)
        assertTrue(r.lines.any { "유급휴일" in it.label || "공휴일" in it.label })
    }

    @Test
    fun `P11 a night shift with a break and no start time is flagged, one with a start time is not`() {
        val night = ShiftType(12, "야간", "야", 0xFF000000, t(22), t(7), 60)
        val nightAt = night.copy(id = 13, breakStart = t(2))
        fun month(s: ShiftType) = PayCalculator().calculate(
            PayProfile(Occupation.CUSTOM, WageType.HOURLY, money(12_000)), YearMonth.of(2026, 11),
            ScheduleResolver(listOf(s, off), WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(s.id, off.id)), emptyList())::resolve,
        )
        assertTrue(month(night).breakGuessedDays.isNotEmpty())
        assertTrue(month(nightAt).breakGuessedDays.isEmpty())
        // A day shift's break moves nothing between night and day.
        val dayOnly = PayCalculator().calculate(PayProfile(Occupation.CUSTOM, WageType.HOURLY, money(12_000)), YearMonth.of(2026, 11),
            ScheduleResolver(listOf(day.copy(breakStart = null), off), WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(day.id, off.id)), emptyList())::resolve)
        assertTrue(dayOnly.breakGuessedDays.isEmpty())
    }

    @Test
    fun `P13 a 13 way split asks whether the 13th share is a regular bonus`() {
        val p = PayProfile(Occupation.CUSTOM, WageType.MONTHLY, money(3_000_000), annual = AnnualPay(39_000_000, 13),
            estimatedFields = setOf(Unknowns.ANNUAL_13))
        val u = Unknowns.of(p).single { it.key == Unknowns.ANNUAL_13 }
        assertTrue("퇴직금" in u.assumed)
        val bonus = u.answers.single().apply(p)
        val item = bonus.items.single { it.id == Unknowns.ANNUAL_13_ITEM }
        assertTrue(item.ordinary)
        assertEquals(3_000_000, item.amount.on(YearMonth.of(2026, 12)).value)
        // Not a 13 way split: no question.
        assertNull(Unknowns.describe(Unknowns.ANNUAL_13, p.copy(annual = AnnualPay(36_000_000))))
    }

    @Test
    fun `P14 the pay before tax is found back from the take-home`() {
        val on = LocalDate.of(2026, 10, 1)
        listOf(2_000_000L, 3_100_000L, 5_500_000L).forEach { gross ->
            val net = TakeHome.estimate(gross, TaxMode.INSURANCE_4, 2, on).net
            val back = TakeHome.grossFromNet(net, TaxMode.INSURANCE_4, 2, on)
            // The 간이세액표 steps make the take-home move in jumps, so a nearby pay can leave the same amount.
            assertTrue("$gross → $net → $back", kotlin.math.abs(back - gross) <= gross / 200)
            assertTrue(TakeHome.estimate(back, TaxMode.INSURANCE_4, 2, on).net - net in 0..1_000)
        }
        assertEquals(1_500_000, TakeHome.grossFromNet(1_500_000, TaxMode.NONE))
    }

    @Test
    fun `X1 24 00 is midnight and a day ending then ends the next day`() {
        assertEquals(LocalTime.MIDNIGHT, AttendanceImport.parseTime("24:00"))
        val rows = listOf(listOf("날짜", "출근", "퇴근"), listOf("2026-10-22", "16:00", "24:00"))
        val read = AttendanceImport.read(rows, AttendanceImport.guess(rows)!!, 2026).single()
        assertEquals(t(16), read.start)
        assertEquals(LocalTime.MIDNIGHT, read.end)
        // AttendanceImport.apply puts an end not after the start on the next day: 16:00 → next day 00:00, 8 hours.
        assertFalse(read.end!!.isAfter(read.start))
    }

    private fun workbook(date1904: Boolean, serial: Double, fmt: Int): ByteArray {
        val wb = """<workbook><workbookPr${if (date1904) " date1904=\"1\"" else ""}/><sheets><sheet name="a" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
        val styles = """<styleSheet><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="$fmt"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>$serial</v></c><c r="B1"><v>45000</v></c></row></sheetData></worksheet>"""
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf("xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet)
                    .forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            }
        }.toByteArray()
    }

    @Test
    fun `X2 a date cell reads the same in the 1900 and the 1904 date systems`() {
        val day = LocalDate.of(2026, 10, 1)
        val s1900 = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), day).toDouble()
        val s1904 = ChronoUnit.DAYS.between(LocalDate.of(1904, 1, 1), day).toDouble()
        assertEquals("2026-10-01", Sheets.read(workbook(false, s1900, 14))[0][0])
        assertEquals("2026-10-01", Sheets.read(workbook(true, s1904, 14))[0][0])
        // A date with its time of day.
        assertEquals("2026-10-01 9:30", Sheets.serialDate((s1904 + 9.5 / 24).toString(), true))
        // A number in an unstyled cell stays a number.
        assertEquals("45000", Sheets.read(workbook(true, s1904, 14))[0][1])
        assertEquals(day, AttendanceImport.parseDate("2026-10-01", 2026))
    }

    @Test
    fun `X4 an all-day event ending at midnight the day after covers only its own days`() {
        val one = DeviceEvent(1, 1, "휴가", LocalDateTime.of(2026, 10, 22, 0, 0), LocalDateTime.of(2026, 10, 23, 0, 0), allDay = true)
        assertEquals(LocalDate.of(2026, 10, 22), one.lastDate)
        val three = one.copy(end = LocalDateTime.of(2026, 10, 25, 0, 0))
        assertEquals(LocalDate.of(2026, 10, 24), three.lastDate)
        assertNotNull(three.key)
    }

    @Test
    fun `M2 a split payment and its installments add up to the amount`() {
        val e = MoneyEntry(date = LocalDate.of(2026, 10, 1), kind = MoneyKind.EXPENSE, amount = 100_001, category = "식비",
            parts = listOf(EntryPart("식비", 33_333), EntryPart("생활", 33_333)), installments = 3)
        assertEquals(100_001, e.shares().sumOf { it.second })
        val first = YearMonth.of(2026, 11)
        assertEquals(100_001, (0L..4L).sumOf { CardCycle.portion(e, first, first.plusMonths(it)) })
        assertEquals(33_335, CardCycle.portion(e, first, first))
        val refund = e.copy(amount = -100_001, parts = emptyList())
        assertEquals(-100_001, refund.shares().single().second)
    }

    @Test
    fun `M3 CU is a convenience store only as a word of its own`() {
        assertEquals("편의점", PaymentParser.guessCategory("CU 강남역점"))
        assertEquals("편의점", PaymentParser.guessCategory("씨유(CU)역삼"))
        assertTrue(PaymentParser.guessCategory("SECURITY GUARD") != "편의점")
        assertTrue(PaymentParser.guessCategory("CULTURE LAND") != "편의점")
    }
}
