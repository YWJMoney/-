package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * [NURS-02] A month's duty typed in at once, read back for what hospitals and the law watch: too little
 * rest between two shifts (역순 E→D, N→D), nights not followed by days off, too many nights or work days
 * in a row. Every limit is the user's to change; since 1.22 they are the same [RestRules] the calendar's
 * 휴식 점검 uses [REST-01], with where each default comes from. These are 알림 only, the roster is saved as typed.
 */
object DutyCheck {
    enum class Kind { SHORT_REST, NO_OFF_AFTER_NIGHT, MANY_NIGHTS, LONG_RUN, MONTH_NIGHTS }

    /** [source] says where the limit comes from; [pattern] names the order (N-O-D 나오데, E-D 이브데이) [REST-02]. */
    data class Warning(val kind: Kind, val date: LocalDate, val text: String, val source: String = "", val pattern: String? = null)

    /** A night shift: one that runs past midnight or starts in the evening (20시 넘어). */
    fun isNight(t: ShiftType?): Boolean {
        if (t == null || t.isOff || !t.hasTimes) return t != null && !t.isOff && Regex("^(N|n)$|나이트|야간|밤").containsMatchIn(t.label + " " + t.name)
        return t.start!! >= LocalTime.of(20, 0) || (t.end!! <= t.start && t.end > LocalTime.of(3, 0))
    }

    private fun works(t: ShiftType?) = t != null && !t.isOff

    /** [days] in date order, gaps allowed; a null shift is a day with nothing on it (rest). */
    fun check(days: List<Pair<LocalDate, ShiftType?>>, rules: RestRules = RestRules()): List<Warning> {
        if (!rules.on) return emptyList()
        val sorted = days.sortedBy { it.first }
        val byDate = sorted.toMap()
        val out = ArrayList<Warning>()
        for ((date, t) in sorted) {
            val prev = byDate[date.minusDays(1)]
            // Rest between yesterday's end and today's start.
            if (rules.minRestHours > 0 && works(t) && works(prev) && t!!.hasTimes && prev!!.hasTimes) {
                val prevEnd = date.minusDays(1).atTime(prev.start!!).plusMinutes(prev.durationMinutes.toLong())
                val rest = Duration.between(prevEnd, date.atTime(t.start!!)).toMinutes()
                if (rest < rules.minRestHours * 60L) out += Warning(Kind.SHORT_REST, date,
                    "${md(date)} ${prev.name} 뒤 ${t.name}: ${when { rest < 0 -> "시간이 겹쳐요"; rest == 0L -> "쉬지 않고 바로 이어져요"; else -> "${hours(rest)}만 쉬어요" }} (${rules.note("${rules.minRestHours}시간보다 짧으면")})",
                    RestRules.Sources.REST, pattern = when {
                        isEvening(prev) && isDay(t) -> "E-D(이브데이)"
                        isNight(prev) && isDay(t) -> "N-D"
                        else -> null
                    })
            }
            // The last night of a run: the next days off.
            if (isNight(t) && !isNight(byDate[date.plusDays(1)])) {
                val need = rules.offsAfterNights
                val firstWork = (1..need).firstOrNull { k -> works(byDate[date.plusDays(k.toLong())]) }
                val known = (1..need).all { k -> byDate.containsKey(date.plusDays(k.toLong())) } || firstWork != null
                // Another night after a day off (야비야비) carries the run on; the rest is counted when the nights end.
                if (need > 0 && known && firstWork != null && !(firstWork > 1 && isNight(byDate[date.plusDays(firstWork.toLong())]))) {
                    val nextDate = date.plusDays(firstWork.toLong())
                    out += Warning(Kind.NO_OFF_AFTER_NIGHT, nextDate,
                        "${md(date)} 나이트 끝나고 ${if (firstWork == 1) "바로 다음 날" else "${firstWork}일째"} 근무예요 (${rules.note("나이트 뒤 쉬는 날 ${need}일보다 적으면")})",
                        RestRules.Sources.NIGHTS, pattern = when {
                            firstWork == 2 && isDay(byDate[nextDate]) -> "N-O-D(나오데)"
                            firstWork == 1 && isDay(byDate[nextDate]) -> "N-D"
                            else -> null
                        })
                }
            }
        }
        if (rules.maxNightsInRow > 0) runs(sorted) { isNight(it) }.filter { it.size > rules.maxNightsInRow }.forEach { r ->
            out += Warning(Kind.MANY_NIGHTS, r.first(), "${md(r.first())}부터 나이트 ${r.size}일 연속 (${rules.note("${rules.maxNightsInRow + 1}일 연속부터")})", RestRules.Sources.NIGHTS)
        }
        if (rules.maxWorkDaysInRow > 0) runs(sorted) { works(it) }.filter { it.size > rules.maxWorkDaysInRow }.forEach { r ->
            out += Warning(Kind.LONG_RUN, r.first(), "${md(r.first())}부터 ${r.size}일 연속 근무 (${rules.note("${rules.maxWorkDaysInRow + 1}일 연속부터")})", RestRules.Sources.WEEK)
        }
        if (rules.maxNightsPerMonth > 0) sorted.filter { isNight(it.second) }.groupBy { java.time.YearMonth.from(it.first) }.forEach { (m, ns) ->
            ns.getOrNull(rules.maxNightsPerMonth)?.let { (d, _) ->
                out += Warning(Kind.MONTH_NIGHTS, d, "${m.monthValue}월 나이트 ${ns.size}개 (${rules.note("한 달 ${rules.maxNightsPerMonth + 1}개째부터")})", RestRules.Sources.NIGHTS)
            }
        }
        // One sign per day for the rest before it: short rest wins over days off after nights [REST-02].
        val restDays = out.filter { it.kind == Kind.SHORT_REST }.map { it.date }.toSet()
        return out.filterNot { it.kind == Kind.NO_OFF_AFTER_NIGHT && it.date in restDays }
            .map { w -> w.pattern?.let { w.copy(text = "$it · ${w.text}") } ?: w }
            .sortedBy { it.date }
    }

    /** Starts in the afternoon: 이브닝·오후. */
    private fun isEvening(t: ShiftType?) = t != null && !t.isOff && t.hasTimes && !isNight(t) && t.start!!.hour in 12..17
    /** Starts in the morning: 데이·주간. */
    private fun isDay(t: ShiftType?) = t != null && !t.isOff && t.hasTimes && !isNight(t) && t.start!!.hour in 4..11

    /** Runs of consecutive dates whose shift matches. */
    private fun runs(days: List<Pair<LocalDate, ShiftType?>>, match: (ShiftType?) -> Boolean): List<List<LocalDate>> {
        val out = ArrayList<List<LocalDate>>()
        var cur = ArrayList<LocalDate>()
        for ((d, t) in days) {
            if (match(t) && (cur.isEmpty() || cur.last().plusDays(1) == d)) cur.add(d)
            else {
                if (cur.isNotEmpty()) out += cur
                cur = if (match(t)) arrayListOf(d) else ArrayList()
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun md(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    private fun hours(m: Long) = if (m % 60 == 0L) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
}
