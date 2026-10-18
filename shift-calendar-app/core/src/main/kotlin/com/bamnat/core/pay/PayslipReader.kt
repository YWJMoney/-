package com.bamnat.core.pay

import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/** [PAYS-05] What a line of a payslip is. */
enum class SlipRole(val label: String) {
    GROSS("지급 총액"),
    BASE("기본급"),
    /** 시급 written on the payslip (시급제), not money paid. */
    HOURLY("시급"),
    OVERTIME("연장근로수당"),
    NIGHT("야간근로수당"),
    HOLIDAY("휴일근로수당"),
    /** 주휴수당: worked out from the calendar for hourly pay, so never an allowance of its own. */
    WEEKLY_HOLIDAY("주휴수당"),
    /** Pay for 잔업·특근·대근 marked on the calendar: the app works it out, so never an allowance of its own. */
    EXTRA_WORK("추가 근무 수당"),
    BONUS("상여"),
    ALLOWANCE("수당"),
    DEDUCTION("공제"),
    DEDUCTION_TOTAL("공제 합계"),
    NET("실수령액"),
    /** "합계", "총계": the 지급 총액 unless the payslip states it on its own. */
    TOTAL("합계"),
}

/** [PAYS-05] One line read from a payslip: its name as printed, the amount, and the hours behind it when given. */
data class SlipLine(
    val name: String,
    val role: SlipRole,
    val amount: Long?,
    val minutes: Int? = null,
    val source: String = "",
) {
    /** Money paid to the worker, part of 지급 총액. */
    val isEarning: Boolean get() = role in EARNINGS && amount != null

    companion object {
        val EARNINGS = setOf(
            SlipRole.BASE, SlipRole.OVERTIME, SlipRole.NIGHT, SlipRole.HOLIDAY, SlipRole.WEEKLY_HOLIDAY,
            SlipRole.EXTRA_WORK, SlipRole.BONUS, SlipRole.ALLOWANCE,
        )
    }
}

/**
 * [PAYS-05] A payslip as read from a photo's text or pasted text. Nothing here leaves the phone; [payslip] turns
 * it into what [PayslipMatcher] takes, and the user sees and can correct every line before that.
 */
data class PayslipRead(
    val lines: List<SlipLine>,
    /** The month the payslip says it is for, when it says. */
    val month: YearMonth? = null,
) {
    val earnings: List<SlipLine> get() = lines.filter { it.isEarning }
    val earningsSum: Long get() = earnings.sumOf { it.amount ?: 0 }

    /** 지급 총액: as stated, else a 합계 that holds the earnings, else the earnings added up. */
    val gross: Long?
        get() = lines.firstOrNull { it.role == SlipRole.GROSS && it.amount != null }?.amount
            ?: lines.filter { it.role == SlipRole.TOTAL && it.amount != null }.map { it.amount!! }
                .filter { earningsSum == 0L || it >= earningsSum - PayslipMatch.tolerance(earningsSum) }.minOrNull()
            ?: earningsSum.takeIf { it > 0 }

    /** The stated total and the lines read agree, so no line was missed or misread. */
    val consistent: Boolean
        get() {
            val g = gross ?: return false
            return earnings.isNotEmpty() && abs(g - earningsSum) <= PayslipMatch.tolerance(g)
        }

    fun amount(role: SlipRole): Long? = lines.filter { it.role == role && it.amount != null }.takeIf { it.isNotEmpty() }?.sumOf { it.amount!! }

    fun minutes(role: SlipRole): Int? = lines.filter { it.role == role }.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()

    /** Allowances and bonuses by the name on the payslip; two lines of one name add up. */
    val allowances: Map<String, Long>
        get() = linkedMapOf<String, Long>().apply {
            lines.filter { (it.role == SlipRole.ALLOWANCE || it.role == SlipRole.BONUS) && it.amount != null }
                .forEach { merge(it.name, it.amount!!, Long::plus) }
        }

    /** What [PayslipMatcher] takes; null without a 지급 총액 to aim at. */
    fun payslip(): Payslip? {
        val g = gross ?: return null
        return Payslip(
            gross = g,
            base = amount(SlipRole.BASE),
            overtimePay = amount(SlipRole.OVERTIME), overtimeMinutes = minutes(SlipRole.OVERTIME),
            nightPay = amount(SlipRole.NIGHT), nightMinutes = minutes(SlipRole.NIGHT),
            holidayPay = amount(SlipRole.HOLIDAY), holidayMinutes = minutes(SlipRole.HOLIDAY),
            allowances = allowances,
        )
    }

    /** The same payslip with [line] at [index] replaced, for the user's corrections. */
    fun withLine(index: Int, line: SlipLine?): PayslipRead =
        copy(lines = lines.toMutableList().also { if (line == null) it.removeAt(index) else it[index] = line })
}

/**
 * [PAYS-05] Reads a payslip from text: what on-device text recognition got from a photo, or what the user pasted.
 * Payslips come in many layouts, so it reads a stream of names and numbers rather than fixed rows: a name takes
 * the next amount after it, which handles "기본급 2,508,000" on one line, a table read row by row, and a photo
 * read column by column (all the names, then all the amounts). A line with "=" is a 계산 방법 line: its result
 * is the amount and its hours are the hours behind it. Plain pattern matching, no network.
 */
object PayslipReader {
    private val numberRe = Regex("""(?<![\d.])(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*(원|시간|hr|h|H|분|%|배|일|회|월|년)?""")
    private val monthRe = Regex("""(20\d{2})\s*(?:년|[./-])\s*(\d{1,2})\s*(?:월|\b)""")
    private val wordRe = Regex("""[가-힣A-Za-z][가-힣A-Za-z()·\s]*""")

    /** Words that head a section or a column, never a line of pay. */
    private val headers = listOf(
        "지급항목", "공제항목", "지급내역", "공제내역", "항목", "금액", "계산방법", "산출식", "산출근거", "계산식", "근로일수",
        "성명", "이름", "사번", "부서", "직급", "직위", "입사일", "지급일", "생년월일", "급여명세서", "임금명세서", "명세서",
        "귀속", "근무일수", "총근로시간", "단위", "비고", "회사명", "사업장",
    )

    private val deductions = listOf(
        "국민연금", "건강보험", "장기요양", "고용보험", "산재", "소득세", "지방소득세", "주민세", "노조", "조합비", "상조", "가불",
        "공제", "대출", "연말정산", "기숙사", "경조", "원천", "징수", "환급", "보험료", "사우회",
    )

    /** The role of a pay line by its name, or null when the name says nothing about pay. */
    fun roleOf(rawName: String): SlipRole? {
        val n = rawName.filterNot { it.isWhitespace() }.lowercase()
        if (n.isEmpty()) return null
        return when {
            listOf("실수령", "차인지급", "실지급", "차감지급", "실수령액").any { it in n } -> SlipRole.NET
            "공제" in n && listOf("합계", "계", "총").any { it in n } -> SlipRole.DEDUCTION_TOTAL
            deductions.any { it in n } -> SlipRole.DEDUCTION
            "지급" in n && listOf("총액", "합계", "계", "총").any { it in n } || n in setOf("총지급액", "급여총액", "임금총액", "총급여", "급여계", "세전급여") ->
                SlipRole.GROSS
            n in setOf("합계", "총계", "계", "총액", "소계", "합") -> SlipRole.TOTAL
            "기본급" in n || "기본월급" in n || "기본임금" in n || n == "기본" || n == "기본급여" -> SlipRole.BASE
            n == "시급" || n == "기본시급" || n == "통상시급" -> SlipRole.HOURLY
            "고정" in n && ("연장" in n || "시간외" in n || "ot" in n) -> SlipRole.ALLOWANCE
            "주휴" in n -> SlipRole.WEEKLY_HOLIDAY
            "잔업" in n || "특근" in n || "대근" in n || "조출" in n -> SlipRole.EXTRA_WORK
            "휴일" in n && ("근로" in n || "수당" in n || "가산" in n || "연장" in n) -> SlipRole.HOLIDAY
            ("야간" in n || "심야" in n) && ("근로" in n || "가산" in n || n == "야간수당" || n == "야간" || n == "심야수당") -> SlipRole.NIGHT
            "연장" in n || "시간외" in n || n == "ot" || "초과근무" in n -> SlipRole.OVERTIME
            listOf("상여", "성과급", "인센티브", "명절", "보너스", "격려금").any { it in n } -> SlipRole.BONUS
            listOf("수당", "식대", "식비", "보조", "지원", "교통", "차량", "급", "비", "금", "료").any { n.endsWith(it) || it in n && it.length > 1 } ->
                SlipRole.ALLOWANCE
            else -> null
        }
    }

    private class Token(val text: String, val number: Double?, val unit: String, val line: Int, val afterEquals: Boolean)

    fun read(text: String): PayslipRead {
        val clean = text.replace(' ', ' ').replace("₩", "").replace("￦", "").replace('：', ':').replace('，', ',')
        val month = monthRe.find(clean)?.let { m ->
            runCatching { YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        val lines = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<SlipLine>()
        // Names waiting for their amount, oldest first; the last name read, for hours that follow it.
        val pending = ArrayDeque<Pair<String, String>>()
        var last: Int? = null
        for ((li, raw) in lines.withIndex()) {
            // Dates are not amounts.
            val line = monthRe.replace(raw, " ").replace(Regex("""\d{4}[./-]\d{1,2}[./-]\d{1,2}"""), " ")
            val eq = line.lastIndexOf('=')
            if (eq > 0 && pending.isEmpty() || eq > 0 && wordRe.find(line)?.let { roleOf(it.value) } != null) {
                // A 계산 방법 line: "연장근로수당 16시간 × 12,000 × 1.5 = 288,000".
                if (formulaLine(line, eq, raw, out) != null) {
                    last = out.lastIndex
                    continue
                }
            }
            var pos = 0
            for (m in Regex("""${wordRe.pattern}|${numberRe.pattern}""").findAll(line)) {
                if (m.range.first < pos) continue
                pos = m.range.last + 1
                val value = m.value.trim()
                if (value.isEmpty()) continue
                val num = numberRe.matchEntire(value)
                if (num == null) {
                    val name = value.replace(Regex("""\s+"""), " ").trim().trimEnd('(', '·').trim()
                    val compact = name.filterNot { it.isWhitespace() }
                    if (headers.any { compact == it || compact.startsWith(it) && compact.length <= it.length + 2 }) continue
                    // A long run of words is a sentence or a heading, not a pay line.
                    if (compact.length > 14 || roleOf(name) == null) continue
                    pending.addLast(name to raw)
                    continue
                }
                val n = num.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                val unit = num.groupValues[2]
                when {
                    unit == "시간" || unit == "hr" || unit.equals("h", true) || unit == "분" -> {
                        val minutes = if (unit == "분") n.roundToInt() else (n * 60).roundToInt()
                        // Hours belong to the name they follow; before its amount the name is still waiting.
                        val target = pending.lastOrNull()
                        if (target != null && minutes in 1..(744 * 60)) {
                            pending.removeLast()
                            out += SlipLine(target.first, roleOf(target.first)!!, null, minutes, target.second)
                            last = out.lastIndex
                        } else last?.let { i -> if (out[i].minutes == null) out[i] = out[i].copy(minutes = minutes) }
                    }
                    unit == "%" || unit == "배" || unit == "일" || unit == "회" || unit == "월" || unit == "년" -> Unit
                    // A name whose amount is still unknown, or one that waits: the oldest takes this amount.
                    else -> {
                        val isMoney = unit == "원" || "," in num.groupValues[1] || n >= 1_000 && n == Math.floor(n)
                        val hourLabel = pending.firstOrNull()?.first?.contains("시간") == true
                        if (hourLabel && !isMoney || hourLabel && n < 400) {
                            val (name, src) = pending.removeFirst()
                            out += SlipLine(name, roleOf(name)!!, null, (n * 60).roundToInt(), src)
                            last = out.lastIndex
                            continue
                        }
                        if (!isMoney) continue
                        val waiting = out.indexOfLast { it.amount == null && it.minutes != null && it.source == raw }
                        if (waiting >= 0 && pending.isEmpty()) {
                            out[waiting] = out[waiting].copy(amount = n.toLong())
                            last = waiting
                            continue
                        }
                        val (name, src) = pending.removeFirstOrNull() ?: continue
                        out += SlipLine(name, roleOf(name)!!, n.toLong(), null, src)
                        last = out.lastIndex
                    }
                }
            }
            // Names with no number on a line of their own wait for the column of amounts; "연장근로시간" lines
            // that were only hours stay as they are.
            if (li == lines.lastIndex) pending.clear()
        }
        return PayslipRead(merge(out), month)
    }

    /** "연장근로수당 16시간 × 12,000 × 1.5 = 288,000": the result after the last "=", the hours before it. */
    private fun formulaLine(line: String, eq: Int, raw: String, out: MutableList<SlipLine>): SlipLine? {
        val name = wordRe.find(line.substring(0, eq))?.value?.trim()?.trimEnd('(', ':')?.trim() ?: return null
        val role = roleOf(name) ?: return null
        val result = numberRe.findAll(line.substring(eq + 1)).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .lastOrNull()?.toLong() ?: return null
        val hours = numberRe.findAll(line.substring(0, eq)).firstOrNull { it.groupValues[2] == "시간" || it.groupValues[2].equals("h", true) }
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.let { (it * 60).roundToInt() }
        // The same line read again from the table: add the hours to it.
        val same = out.indexOfLast { it.role == role && it.amount == result }
        val line2 = SlipLine(name, role, result, hours, raw)
        if (same >= 0) out[same] = out[same].copy(minutes = out[same].minutes ?: hours) else out += line2
        return line2
    }

    /** Hours read on their own ("연장근로시간 16") join the amount line of the same role, so each role is one line. */
    private fun merge(lines: List<SlipLine>): List<SlipLine> {
        val result = lines.toMutableList()
        val hoursOnly = result.filter { it.amount == null && it.minutes != null }
        for (h in hoursOnly) {
            val target = result.indexOfFirst { it.role == h.role && it.amount != null && it.minutes == null }
            if (target >= 0) {
                result[target] = result[target].copy(minutes = h.minutes)
                result.remove(h)
            }
        }
        return result.filter { it.amount != null || it.minutes != null }
    }
}
