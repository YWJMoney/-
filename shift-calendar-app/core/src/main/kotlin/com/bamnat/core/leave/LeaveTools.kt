package com.bamnat.core.leave

import com.bamnat.core.report.Csv
import com.bamnat.core.work.DayWork
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [LEAVE-10] The 연차 원장 as a file to keep or hand over: every line with its date, what it is, the days and the
 * balance after it. CSV the way the app's other exports write it (BOM, CRLF), each cell kept from being read as a
 * formula ([Csv.text]).
 */
object LeaveExport {
    val HEADER = listOf("날짜", "구분", "내용", "일수", "남은 연차", "기한", "메모")

    fun rows(summary: LeaveSummary): List<List<String>> = listOf(HEADER) + summary.lines.map { l ->
        listOf(
            l.date.toString(),
            l.kind.label + if (l.pool == LeavePool.COMP) " (보상휴가)" else "",
            Csv.text(l.title),
            number(l.days),
            if (l.pool == LeavePool.ANNUAL) number(l.balance) else "",
            l.expires?.toString().orEmpty(),
            Csv.text(l.note),
        )
    } + listOf(
        emptyList(),
        listOf("${summary.today} 기준", "쓸 수 있는 연차", "", number(summary.available)),
        listOf("", "쓸 예정", "", number(summary.planned)),
        listOf("", "생긴 연차 / 쓴 연차", "", number(summary.arisen), number(summary.used)),
        listOf("근로기준법 제60조(연차)·제61조(사용 촉진). 앱이 센 값이라 회사 기록과 다를 수 있어요."),
    )

    fun csv(summary: LeaveSummary): String = Csv.write(rows(summary))

    fun fileName(today: LocalDate): String = "연차원장_${today}.csv"

    private fun number(v: Double): String = (Math.round(v * 100) / 100.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
}

/**
 * [LEAVE-12] 시간 단위 연차 (법률 제21784호, 2027-06-10 시행 예정, research/1.23-K-원문.md 11장): the worker may ask for leave
 * in hours and it must be given. The 시행령 setting the unit is not out yet (확인 필요), so this is only the shape the
 * calendar will store: minutes of leave on a day, counted against the ledger as minutes ÷ the day's 소정근로시간.
 */
data class HourlyLeave(val date: LocalDate, val minutes: Int) {
    /** Days off the ledger for a day of [dayMinutes] 소정근로 (8시간 when not known). */
    fun days(dayMinutes: Int = 8 * 60): Double = Math.round(minutes.toDouble() / dayMinutes.coerceIn(60, 8 * 60) * 10_000) / 10_000.0

    companion object {
        /** The day the 법률 제21784호 rule on 시간 단위 연차 takes effect. */
        val FROM: LocalDate = LocalDate.of(2027, 6, 10)
        /** Until the 시행령 says, the smallest piece the app will offer. */
        const val UNIT_MINUTES = 60
    }
}

/**
 * [LEAVE-13] 연차 쓰기 최적화: days that would lapse unused, placed where they make the longest rest — next to 공휴일 and
 * days off before the lapse date ([DayWork.Bridge]s) — fewest days for the most rest first. Leave is paid, so it does
 * not lower the month's pay; [payNote] says so for the kind of pay.
 */
object LeavePlanner {
    data class Plan(val lapse: LocalDate, val lapsing: Double, val picks: List<DayWork.Bridge>, val leftOver: Double) {
        val days: Int get() = picks.sumOf { it.leave.size }
        val restDays: Int get() = picks.sumOf { it.days }
    }

    /**
     * For the next lapse in [summary] (after the planned days), the [bridges] before it that its days cover, best
     * value first (rest days per day of leave), never two that share a day. Null when nothing lapses.
     */
    fun plan(summary: LeaveSummary, bridges: List<DayWork.Bridge>): Plan? {
        val (lapse, lapsing) = summary.nextLapse?.takeIf { it.second > 1e-9 } ?: return null
        var left = lapsing
        val used = mutableSetOf<LocalDate>()
        val picks = mutableListOf<DayWork.Bridge>()
        val ranked = bridges.filter { b -> b.leave.all { !it.isAfter(lapse) && it.isAfter(summary.today) } }
            .sortedWith(compareByDescending<DayWork.Bridge> { it.days.toDouble() / it.leave.size }.thenBy { it.from })
        for (b in ranked) {
            if (b.leave.size > left + 1e-9) continue
            val span = (0..ChronoUnit.DAYS.between(b.from, b.to)).map { b.from.plusDays(it) }
            if (span.any { it in used }) continue
            picks += b
            used += span
            left -= b.leave.size
            if (left < 1 - 1e-9) break
        }
        return Plan(lapse, lapsing, picks.sortedBy { it.from }, Math.round(left * 100) / 100.0)
    }

    fun payNote(hourly: Boolean): String =
        if (hourly) "연차는 유급이라 쉬는 날도 그날 근무시간만큼 시급을 받아요(근로기준법 제60조)."
        else "연차는 유급이라 월급이 줄지 않아요. 안 쓰고 없어지면 회사가 사용 촉진을 했는지에 따라 수당이 없을 수 있어요(제61조)."
}
