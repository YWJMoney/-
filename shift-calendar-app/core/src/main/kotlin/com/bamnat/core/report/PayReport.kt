package com.bamnat.core.report

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.pay.DayPay
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayCalculator
import java.time.LocalDate
import java.time.YearMonth

/**
 * [PROV-01] The month's or the year's pay as a report to keep or hand over (프로): every line with how it
 * was worked out, the days behind it, the rate table and holiday list it used, and that it is an estimate
 * before tax. The same figures go to a PDF ([ReportDoc]) and a CSV for a spreadsheet.
 */
object PayReport {
    /** What the person and the pay are, e.g. "간호사 · 월급 320만 원"; the app's own words. */
    data class Who(val job: String, val wage: String)

    /**
     * [PAYX-40] What the figures rest on, for the page foot and the CSV's last rows: the rate table, the holiday list
     * and the 간이세액표 by version, and the articles the lines use.
     */
    fun basisLine(ratesVersion: String, on: LocalDate): String = listOfNotNull(
        "요율 $ratesVersion",
        "공휴일 ${KoreanHolidays.version}",
        com.bamnat.core.pay.WithholdingTable.on(on)?.from?.let { "간이세액표 $it 시행분" },
        "근로기준법 제55조·제56조·제60조",
    ).joinToString(" · ")

    const val NOTE = "밤낮 앱이 근무표와 입력한 급여 값으로 계산한 참고용 금액이에요. 세금과 4대보험을 빼기 전이고, 회사 급여명세서와 다를 수 있어요."

    fun kindName(kind: LineKind): String = when (kind) {
        LineKind.BASE -> "기본"
        LineKind.OVERTIME -> "연장"
        LineKind.NIGHT -> "야간"
        LineKind.HOLIDAY -> "휴일"
        LineKind.EXTRA -> "추가 근무"
        LineKind.EXTRA_HOURS -> "추가 근무(가산 없음)"
        LineKind.ALLOWANCE -> "수당"
        LineKind.LEAVE -> "휴가"
    }

    fun month(pay: MonthPay, who: Who, made: LocalDate, closed: Boolean = false): ReportDoc {
        val m = pay.month
        val blocks = mutableListOf<ReportDoc.Block>()
        blocks += ReportDoc.Facts(listOfNotNull(
            "직업·급여" to "${who.job} · ${who.wage}",
            "급여 기간" to period(pay),
            pay.payDate?.let { "지급일" to date(it) },
            "통상시급" to won(pay.ordinaryHourly),
            "계산 기준" to "요율 ${pay.ratesVersion}, 공휴일 ${KoreanHolidays.version}",
            "만든 날" to date(made) + if (closed) " (마감한 달)" else "",
        ))
        blocks += ReportDoc.Heading("세전 예상 합계 ${won(pay.total)}" + if (pay.estimated) " (추정 포함)" else "")
        blocks += ReportDoc.Heading("항목별 계산")
        blocks += lineTable(pay)
        val bases = pay.lines.map { it.basis }.filter { it.isNotBlank() }.distinct()
        if (bases.isNotEmpty()) blocks += ReportDoc.Paragraph("근거: " + bases.joinToString(" · "), small = true)
        if (pay.missing.isNotEmpty()) {
            blocks += ReportDoc.Heading("합계에 넣지 않은 수당")
            blocks += ReportDoc.Paragraph(pay.missing.joinToString(", ") { it.name + " · 금액 모름" + (it.times?.let { t -> " · ${t}회" } ?: "") })
        }
        val days = worked(pay)
        if (days.isNotEmpty()) {
            blocks += ReportDoc.Heading("날짜별 근무")
            blocks += ReportDoc.Table(
                headers = listOf("날짜", "근무", "일한 시간", "야간", "더해진 돈", "내역"),
                rows = days.map { (d, minutes) ->
                    listOf(dayLabel(d.date) + (d.holidayName?.let { " $it" } ?: ""), (listOf(d.label) + d.extras).joinToString(", "),
                        HoursFormat.korean(minutes.worked), if (minutes.night > 0) HoursFormat.korean(minutes.night) else "",
                        if (d.amount != 0L) won(d.amount) else "", d.parts.joinToString(", ") { it.label })
                },
                weights = listOf(1.3f, 1.6f, 1.2f, 1.0f, 1.3f, 2.4f),
                right = setOf(2, 3, 4),
            )
        }
        if (pay.warnings.isNotEmpty()) {
            blocks += ReportDoc.Heading("확인해 주세요")
            pay.warnings.forEach { blocks += ReportDoc.Paragraph("• $it") }
        }
        blocks += ReportDoc.Paragraph(NOTE, small = true)
        return ReportDoc("${m.year}년 ${m.monthValue}월 급여 리포트", blocks,
            footer = "밤낮 · ${m.year}년 ${m.monthValue}월 급여 · 참고용 · " + basisLine(pay.ratesVersion, m.atDay(1)))
    }

    /** [PROV-01] The year month by month; [months] are the calculated or closed months of [year]. */
    fun year(year: Int, months: List<MonthPay>, who: Who, made: LocalDate, closed: Set<YearMonth> = emptySet()): ReportDoc {
        val sorted = months.sortedBy { it.month }
        val blocks = mutableListOf<ReportDoc.Block>()
        val total = sorted.sumOf { it.total }
        blocks += ReportDoc.Facts(listOf(
            "직업·급여" to "${who.job} · ${who.wage}",
            "기간" to (if (sorted.isEmpty()) "${year}년" else "${sorted.first().month.monthValue}월~${sorted.last().month.monthValue}월"),
            "계산 기준" to "요율 ${sorted.lastOrNull()?.ratesVersion ?: "-"}, 공휴일 ${KoreanHolidays.version}",
            "만든 날" to date(made),
        ))
        blocks += ReportDoc.Heading("${year}년 세전 예상 합계 ${won(total)}")
        blocks += ReportDoc.Heading("달마다")
        blocks += ReportDoc.Table(
            headers = listOf("달", "일한 시간", "야간", "연장", "휴일", "합계", "비고"),
            rows = sorted.map { p ->
                val f = figures(p)
                listOf("${p.month.monthValue}월", HoursFormat.korean(f.worked), HoursFormat.korean(f.night), HoursFormat.korean(f.overtime),
                    HoursFormat.korean(f.holiday), won(p.total), listOfNotNull(if (p.month in closed) "마감" else null, if (p.estimated) "추정 포함" else null).joinToString(", "))
            },
            weights = listOf(0.8f, 1.3f, 1.1f, 1.1f, 1.1f, 1.5f, 1.2f),
            right = setOf(1, 2, 3, 4, 5),
            total = sorted.map(::figures).fold(Figures()) { a, b -> a + b }.let { f ->
                listOf("합계", HoursFormat.korean(f.worked), HoursFormat.korean(f.night), HoursFormat.korean(f.overtime), HoursFormat.korean(f.holiday), won(total), "")
            },
        )
        val byKind = LineKind.entries.map { k -> k to sorted.sumOf { p -> p.lines.filter { it.kind == k }.sumOf { it.amount } } }.filter { it.second != 0L }
        if (byKind.isNotEmpty()) {
            blocks += ReportDoc.Heading("종류별 한 해 합계")
            blocks += ReportDoc.Table(listOf("종류", "금액"), byKind.map { (k, v) -> listOf(kindName(k), won(v)) }, listOf(2f, 1.5f), right = setOf(1))
        }
        blocks += ReportDoc.Paragraph(NOTE, small = true)
        return ReportDoc("${year}년 급여·근무 리포트", blocks,
            footer = "밤낮 · ${year}년 급여·근무 · 참고용 · " + basisLine(sorted.lastOrNull()?.ratesVersion ?: "-", LocalDate.of(year, 12, 1)))
    }

    // ---- CSV ----

    val MONTH_HEADER = listOf("구분", "날짜", "종류", "항목", "시간(분)", "시급", "배율", "금액", "계산", "근거")
    val YEAR_HEADER = listOf("달", "일한 시간(분)", "야간(분)", "연장(분)", "휴일(분)", "합계", "추정 포함", "마감")

    /** One table: the month's lines (구분 항목), then each worked day (구분 날짜), so a spreadsheet can filter either. */
    fun monthCsv(pay: MonthPay): String = Csv.write(buildList {
        add(MONTH_HEADER)
        pay.lines.forEach { l ->
            add(listOf("항목", "", kindName(l.kind), Csv.text(l.label), l.minutes.takeIf { it > 0 }?.toString().orEmpty(),
                l.hourly.takeIf { it > 0 }?.toString().orEmpty(), l.multiplier.takeIf { it > 0 }?.let(PayCalculator::pct).orEmpty(),
                l.amount.toString(), Csv.text(l.formula), Csv.text(com.bamnat.core.pay.PayExplain.basisOf(l))))
        }
        add(listOf("합계", "", "", "", "", "", "", pay.total.toString(), "", ""))
        worked(pay).forEach { (d, minutes) ->
            add(listOf("날짜", d.date.toString(), "", Csv.text((listOf(d.label) + d.extras).joinToString(", ")), minutes.worked.toString(), "", "",
                d.amount.toString(), Csv.text(d.parts.joinToString(", ") { "${it.label} ${it.formula}" }), d.holidayName.orEmpty()))
        }
        add(listOf("계산 기준", "", "", Csv.text(basisLine(pay.ratesVersion, pay.month.atDay(1))), "", "", "", "", "", ""))
    })

    fun yearCsv(months: List<MonthPay>, closed: Set<YearMonth> = emptySet()): String = Csv.write(buildList {
        add(YEAR_HEADER)
        months.sortedBy { it.month }.forEach { p ->
            val f = figures(p)
            add(listOf(p.month.toString(), f.worked.toString(), f.night.toString(), f.overtime.toString(), f.holiday.toString(),
                p.total.toString(), if (p.estimated) "예" else "아니요", if (p.month in closed) "예" else "아니요"))
        }
        months.maxByOrNull { it.month }?.let { last ->
            add(listOf("계산 기준", Csv.text(basisLine(last.ratesVersion, last.month.atDay(1))), "", "", "", "", "", ""))
        }
    })

    fun fileName(month: YearMonth, ext: String) = "bamnat-pay-$month.$ext"
    fun fileName(year: Int, ext: String) = "bamnat-pay-$year.$ext"

    // ---- figures ----

    /** [PROV-01][PROV-02] Minutes of a month by what they were, from the pay calculation. */
    data class Figures(val worked: Int = 0, val night: Int = 0, val overtime: Int = 0, val holiday: Int = 0, val extra: Int = 0) {
        operator fun plus(o: Figures) = Figures(worked + o.worked, night + o.night, overtime + o.overtime, holiday + o.holiday, extra + o.extra)
    }

    fun figures(pay: MonthPay): Figures = pay.minutes.fold(Figures()) { a, d ->
        a + Figures(
            worked = d.worked, night = d.night, overtime = d.overtime,
            // 휴일 and 쉬는 날 work alike: work on a day with no shift.
            holiday = d.holidayWithin8h + d.holidayOver8h + d.restWithin8h + d.restOver8h,
            extra = d.extras.sumOf { it.minutes.worked },
        )
    }

    private fun lineTable(pay: MonthPay) = ReportDoc.Table(
        headers = listOf("종류", "항목", "시간", "계산", "금액"),
        rows = pay.lines.map { l ->
            listOf(kindName(l.kind), l.label + if (l.estimated) " (추정)" else "", if (l.minutes > 0) HoursFormat.korean(l.minutes) else "", l.formula, won(l.amount))
        },
        weights = listOf(0.9f, 1.8f, 1.1f, 3.0f, 1.3f),
        right = setOf(2, 4),
        total = listOf("", "합계", "", "", won(pay.total)),
    )

    private fun worked(pay: MonthPay): List<Pair<DayPay, com.bamnat.core.pay.DayMinutes>> {
        val minutes = pay.minutes.associateBy { it.date }
        return pay.days.mapNotNull { d -> minutes[d.date]?.let { d to it } }
            .filter { (d, m) -> m.worked > 0 || d.amount != 0L }
            .sortedBy { it.first.date }
    }

    private fun period(pay: MonthPay): String {
        val start = pay.periodStart ?: pay.month.atDay(1)
        val end = pay.periodEnd ?: pay.month.atEndOfMonth()
        return "${date(start)} ~ ${date(end)}"
    }

    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")
    private fun dayLabel(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${WEEKDAYS[d.dayOfWeek.value - 1]})"
    private fun date(d: LocalDate) = "${d.year}.${d.monthValue}.${d.dayOfMonth}"
    internal fun won(v: Long): String = "%,d원".format(v)
}

/** CSV the way the app's other exports write it: UTF-8 with a BOM so Excel reads Korean, CRLF lines. */
internal object Csv {
    fun write(rows: List<List<String>>): String {
        val sb = StringBuilder("﻿")
        for (cells in rows) {
            cells.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) sb.append('"').append(c.replace("\"", "\"\"")).append('"') else sb.append(c)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /** Text a spreadsheet must not treat as a formula. */
    fun text(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
}
