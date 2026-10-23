package com.bamnat.core.pay

import java.time.YearMonth
import kotlin.math.abs

/**
 * [PAYX-36][PAYX-41] "내 월급 맞나요?": a payslip line by line against what the calendar and the settings make of the
 * month. The 공제 lines go next to [TakeHome]'s estimate, and the pay lines that differ fall in two baskets — lines
 * the company seems to have paid short, and lines the app seems not to know about — so the user knows whether to
 * ask the company or fix the calendar (research/1.23-리뷰 K I1).
 */
object SlipAudit {
    /** One 공제 line: what the payslip took, and what the app expects for the same 지급 총액. */
    data class Deduction(val name: String, val slip: Long?, val estimate: Long?) {
        val diff: Long? get() = if (slip != null && estimate != null) slip - estimate else null
    }

    /** The order TakeHome writes its 공제 in, and the words a payslip uses for each. */
    private val DEDUCTIONS: List<Pair<String, List<String>>> = listOf(
        "국민연금" to listOf("국민연금", "연금"),
        "건강보험" to listOf("건강보험", "건강"),
        "장기요양" to listOf("장기요양", "요양"),
        "고용보험" to listOf("고용보험", "고용"),
        TakeHome.TAX_LABEL to listOf("근로소득세", "소득세", "갑근세"),
        TakeHome.LOCAL_TAX_LABEL to listOf("지방소득세", "지방세", "주민세"),
    )

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    /** Which of the five (six with 장기요양) a payslip's 공제 line is, or null for another (조합비, 식대 공제 …). */
    fun deductionOf(name: String): String? {
        val n = norm(name)
        // 지방소득세 before 소득세, 장기요양 before 건강, since one name holds the other.
        val order = listOf(5, 2, 0, 1, 3, 4)
        return order.map { DEDUCTIONS[it] }.firstOrNull { (_, words) -> words.any { n.contains(it) } }?.first
    }

    /**
     * The payslip's 4대보험·소득세 lines against [TakeHome.estimate] on the payslip's own 지급 총액 (4대보험 setting,
     * the profile's 가족 수 and 비과세). Lines the payslip does not have are shown with the estimate only.
     */
    fun deductions(read: PayslipRead, profile: PayProfile, month: YearMonth): List<Deduction> {
        val gross = read.gross ?: return emptyList()
        val est = TakeHome.estimate(gross, TaxMode.INSURANCE_4, profile.taxFamily, month.atDay(1), profile.taxChildren,
            TakeHome.nonTaxable(profile, month)).deductions.toMap()
        val slip = read.lines.filter { it.role == SlipRole.DEDUCTION && it.amount != null }
            .groupBy { deductionOf(it.name) }.filterKeys { it != null }.mapValues { (_, l) -> l.sumOf { it.amount!! } }
        return DEDUCTIONS.map { (name, _) -> Deduction(name, slip[name], est[name]) }.filter { it.slip != null || (it.estimate ?: 0) > 0 }
    }

    enum class Basket(val label: String, val hint: String) {
        COMPANY_LESS("회사가 적게 준 것 같은 줄", "근무표대로면 더 받아야 해요. 회사에 계산 방법을 물어보세요."),
        NOT_ENTERED("앱에 안 넣은 것 같은 줄", "명세서가 더 많아요. 달력에 빠진 근무나 앱에 없는 수당이 있는지 봐 주세요."),
    }

    /** One pay line that differs by more than [tolerance]: the payslip's amount, the app's, and its basket. */
    data class Gap(val label: String, val slip: Long, val app: Long, val basket: Basket) {
        val diff: Long get() = slip - app
    }

    private val PAIRS: List<Triple<String, (Payslip) -> Long?, Set<LineKind>>> = listOf(
        Triple("기본급", { s -> s.base }, setOf(LineKind.BASE)),
        Triple("연장근로수당", { s -> s.overtimePay }, setOf(LineKind.OVERTIME, LineKind.EXTRA_HOURS)),
        Triple("야간근로수당", { s -> s.nightPay }, setOf(LineKind.NIGHT)),
        Triple("휴일근로수당", { s -> s.holidayPay }, setOf(LineKind.HOLIDAY)),
    )

    /**
     * The pay lines of [slip] against [pay] (the month with the settings the user keeps or the match found): each
     * premium and the 기본급 the payslip gives, then each allowance by name; one the app does not have at all is
     * [Basket.NOT_ENTERED].
     */
    fun gaps(slip: Payslip, pay: MonthPay, tolerance: Long = 1_000): List<Gap> {
        val out = mutableListOf<Gap>()
        for ((label, get, kinds) in PAIRS) {
            val s = get(slip) ?: continue
            val a = pay.lines.filter { it.kind in kinds }.sumOf { it.amount }
            if (abs(s - a) > tolerance) out += Gap(label, s, a, if (s < a) Basket.COMPANY_LESS else Basket.NOT_ENTERED)
        }
        for ((name, s) in slip.allowances) {
            val a = pay.lines.filter { it.kind == LineKind.ALLOWANCE && norm(it.label) == norm(name) }.sumOf { it.amount }
            if (abs(s - a) > tolerance) out += Gap(name, s, a, if (s < a) Basket.COMPANY_LESS else Basket.NOT_ENTERED)
        }
        return out
    }

    /** [PAYX-41] The comparison as a page to keep or show: both baskets, the 공제, and where to ask. */
    fun doc(month: YearMonth, gross: Long, appTotal: Long, gaps: List<Gap>, deductions: List<Deduction>, belowLaw: Long?): com.bamnat.core.report.ReportDoc {
        fun w(v: Long) = "%,d원".format(v)
        val blocks = mutableListOf<com.bamnat.core.report.ReportDoc.Block>(
            com.bamnat.core.report.ReportDoc.Facts(listOf("달" to "${month.year}년 ${month.monthValue}월", "명세서 지급 총액" to w(gross), "앱 계산 (세전)" to w(appTotal), "차이" to w(gross - appTotal))),
        )
        Basket.entries.forEach { b ->
            val mine = gaps.filter { it.basket == b }
            if (mine.isEmpty()) return@forEach
            blocks += com.bamnat.core.report.ReportDoc.Heading(b.label)
            blocks += com.bamnat.core.report.ReportDoc.Table(listOf("항목", "명세서", "앱", "차이"), mine.map { listOf(it.label, w(it.slip), w(it.app), w(it.diff)) }, listOf(2f, 1.3f, 1.3f, 1.2f), right = setOf(1, 2, 3))
            blocks += com.bamnat.core.report.ReportDoc.Paragraph(b.hint, small = true)
        }
        belowLaw?.let { blocks += com.bamnat.core.report.ReportDoc.Paragraph("법 기준으로 계산하면 이 달 ${w(it)} 더 많아요.") }
        if (deductions.isNotEmpty()) {
            blocks += com.bamnat.core.report.ReportDoc.Heading("공제")
            blocks += com.bamnat.core.report.ReportDoc.Table(listOf("항목", "명세서", "앱 예상"), deductions.map { listOf(it.name, it.slip?.let(::w) ?: "없음", it.estimate?.let(::w) ?: "-") },
                listOf(2f, 1.3f, 1.3f), right = setOf(1, 2))
        }
        blocks += com.bamnat.core.report.ReportDoc.Paragraph(ASK_NOTE, small = true)
        return com.bamnat.core.report.ReportDoc("${month.year}년 ${month.monthValue}월 명세서 대조", blocks, footer = "밤낮 · 명세서 대조 · 참고용")
    }

    /** "회사가 적게 준 것 같아요" goes with the 1350 상담 (근로기준법 제43조 임금 전액 지급, 제56조 가산임금). */
    const val ASK_NOTE = "적게 받은 것 같으면 회사에 먼저 물어보고, 그래도 다르면 고용노동부 고객상담센터(국번 없이 1350)에 상담할 수 있어요 " +
        "(근로기준법 제43조·제56조)."
}
