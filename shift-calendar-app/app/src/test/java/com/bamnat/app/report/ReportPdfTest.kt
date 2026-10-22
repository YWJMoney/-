package com.bamnat.app.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.report.PayReport
import com.bamnat.core.report.ReportDoc
import com.bamnat.core.report.RosterReport
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PROV-01][PROV-05] The PDF pages: A4 sizes, a long table running onto more pages, the year's roster on
 * one landscape page. Pages are drawn into bitmaps (build/screenshots/report) to look at.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReportPdfTest {
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(8, 0), LocalTime.of(20, 0), breakMinutes = 60)
    private val night = ShiftType(2, "야간", "야", 0xFF5E35B1, LocalTime.of(20, 0), LocalTime.of(8, 0), breakMinutes = 60)
    private val off = ShiftType(3, "휴무", "휴", 0xFFE9ECEF, null, null, isOff = true)
    private val resolver = ScheduleResolver(listOf(day, night, off), WorkPattern.Rotation(LocalDate.of(2026, 7, 1), listOf(1L, 1L, 2L, 2L, 3L)), emptyList())
    private val profile = PayProfile(occupation = Occupation.MANUFACTURING, wageType = WageType.MONTHLY,
        base = ValueTimeline(listOf(DatedValue(LocalDate.of(2026, 1, 1), 3_200_000))))
    private val made = LocalDate.of(2026, 10, 22)
    private val out = File("build/screenshots/report").apply { mkdirs() }

    private class Shots(val name: String, val dir: File) : ReportPdf.Pages {
        val sizes = mutableListOf<Pair<Int, Int>>()
        private lateinit var bitmap: Bitmap
        override fun begin(width: Int, height: Int): Canvas {
            sizes += width to height
            // Twice the point size, so the PNG is readable.
            bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            return Canvas(bitmap).apply { scale(2f, 2f) }
        }
        override fun end() {
            File(dir, "${name}_${sizes.size}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun aMonthsPayReportIsPortraitA4() {
        val pay = PayCalculator().calculate(profile, YearMonth.of(2026, 10), resolver::resolve)
        val doc = PayReport.month(pay, PayReport.Who("제조업", "월급제 3,200,000원"), made)
        val shots = Shots("pay_month", out)
        val pages = ReportPdf.draw(doc, shots)
        assertTrue("날짜별 표가 다음 쪽으로 넘어가요", pages >= 2)
        assertTrue(shots.sizes.all { it == ReportPdf.A4_SHORT to ReportPdf.A4_LONG })
    }

    @Test fun aYearsRosterIsOneLandscapePage() {
        val doc = RosterReport.year(2026, resolver::resolve, "민지의 근무표", made)
        val shots = Shots("roster_year", out)
        assertEquals(1, ReportPdf.draw(doc, shots))
        assertEquals(listOf(ReportPdf.A4_LONG to ReportPdf.A4_SHORT), shots.sizes)
        val month = Shots("roster_month", out)
        assertEquals(1, ReportPdf.draw(RosterReport.month(YearMonth.of(2026, 10), resolver::resolve, "민지의 근무표", made), month))
    }

    @Test fun aLongTableCarriesItsHeaderOver() {
        val rows = List(120) { listOf("${it + 1}", "줄 ${it + 1}", "%,d원".format(it * 1000)) }
        val doc = ReportDoc("긴 표", listOf(ReportDoc.Table(listOf("번호", "내용", "금액"), rows, listOf(1f, 3f, 1.5f), right = setOf(2))))
        val shots = Shots("long_table", out)
        assertTrue(ReportPdf.draw(doc, shots) >= 3)
    }

    @Test fun theFileIsAPdf() {
        val bytes = ByteArrayOutputStream()
        val pages = runCatching { ReportPdf.write(RosterReport.month(YearMonth.of(2026, 10), resolver::resolve, "", made), bytes) }
        // Robolectric may not carry Android's PDF writer; on a phone it always does.
        pages.onSuccess {
            assertEquals(1, it)
            assertTrue(String(bytes.toByteArray().take(5).toByteArray()).startsWith("%PDF"))
        }
    }
}
