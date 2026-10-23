package com.bamnat.core.report

import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.pay.MonthPay
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [PAYX-45] 노무사·상담센터에 보낼 묶음: the month's pay report, the 연차 원장, the hours week by week against 52시간
 * and, when there is one, the 명세서 대조, as files a person reads (not the app's backup). The app adds the PDF and
 * hands the zip to the share sheet; nothing is sent by itself.
 */
object CounselBundle {
    const val README = "읽어 주세요.txt"

    /** Week (월요일 시작) by week: worked hours, 연장·야간·휴일, and whether it went over 52시간 (근로기준법 제53조). */
    fun weeksCsv(pays: List<MonthPay>): String {
        val days = pays.flatMap { it.minutes }.distinctBy { it.date }.sortedBy { it.date }
        val weeks = days.groupBy { it.date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return Csv.write(buildList {
            add(listOf("주 시작", "주 끝", "일한 시간", "연장", "야간", "휴일·쉬는 날", "52시간 넘음"))
            weeks.toSortedMap().forEach { (from, w) ->
                val worked = w.sumOf { it.worked }
                add(listOf(from.toString(), from.plusDays(6).toString(), HoursFormat.korean(worked), HoursFormat.korean(w.sumOf { it.overtime }),
                    HoursFormat.korean(w.sumOf { it.night }), HoursFormat.korean(w.sumOf { it.holidayWithin8h + it.holidayOver8h + it.restWithin8h + it.restOver8h }),
                    if (worked > 52 * 60) "예" else ""))
            }
        })
    }

    fun readme(made: LocalDate, files: List<String>, ratesVersion: String): String = buildString {
        appendLine("밤낮 앱에서 만든 자료 묶음 ($made)")
        appendLine()
        appendLine("근무표와 입력한 급여 값으로 앱이 계산한 참고 자료예요. 회사 기록·급여명세서와 함께 봐 주세요.")
        appendLine("계산 기준: " + PayReport.basisLine(ratesVersion, made))
        appendLine()
        appendLine("들어 있는 파일")
        files.filter { it != README }.forEach { appendLine("- $it") }
        appendLine()
        appendLine("상담: 고용노동부 고객상담센터 국번 없이 1350, 대한법률구조공단 국번 없이 132")
    }.replace("\n", "\r\n")

    /** [files] (name → bytes) as one zip, in the order given. */
    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { z ->
            files.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name.replace('/', '_')))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
