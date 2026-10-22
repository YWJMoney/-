package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * [NURS-02] A month's duty typed in at once, read back for what hospitals and the law watch: too little
 * rest between two shifts (역순 E→D, N→D), nights not followed by days off, too many nights or work days
 * in a row. Every limit is the user's to change; the defaults and where they come from are in
 * research/1.21-직군-조사.md. These are 알림 only, the roster is saved as typed.
 */
object DutyCheck {
    data class Limits(
        /** Hours of rest between the end of one shift and the start of the next. */
        val minRestHours: Int = 11,
        /** Days off after the last night of a run (흔한 병원 지침 예: 나이트 뒤 OFF 2개). */
        val offsAfterNights: Int = 2,
        val maxNightsInRow: Int = 3,
        /** 근로기준법 제55조: at least one paid day off a week, so seven work days in a row is too many. */
        val maxWorkDaysInRow: Int = 6,
    )

    enum class Kind { SHORT_REST, NO_OFF_AFTER_NIGHT, MANY_NIGHTS, LONG_RUN }

    data class Warning(val kind: Kind, val date: LocalDate, val text: String)

    /** A night shift: one that runs past midnight or starts in the evening (20시 넘어). */
    fun isNight(t: ShiftType?): Boolean {
        if (t == null || t.isOff || !t.hasTimes) return t != null && !t.isOff && Regex("^(N|n)$|나이트|야간|밤").containsMatchIn(t.label + " " + t.name)
        return t.start!! >= LocalTime.of(20, 0) || (t.end!! <= t.start && t.end > LocalTime.of(3, 0))
    }

    private fun works(t: ShiftType?) = t != null && !t.isOff

    /** [days] in date order, gaps allowed; a null shift is a day with nothing on it (rest). */
    fun check(days: List<Pair<LocalDate, ShiftType?>>, limits: Limits = Limits()): List<Warning> {
        val sorted = days.sortedBy { it.first }
        val byDate = sorted.toMap()
        val out = ArrayList<Warning>()
        for ((date, t) in sorted) {
            val prev = byDate[date.minusDays(1)]
            // Rest between yesterday's end and today's start.
            if (works(t) && works(prev) && t!!.hasTimes && prev!!.hasTimes) {
                val prevEnd = date.minusDays(1).atTime(prev.start!!).plusMinutes(prev.durationMinutes.toLong())
                val rest = Duration.between(prevEnd, date.atTime(t.start!!)).toMinutes()
                if (rest < limits.minRestHours * 60L) out += Warning(Kind.SHORT_REST, date,
                    "${md(date)} ${prev.name} 뒤 ${t.name}: ${when { rest < 0 -> "시간이 겹쳐요"; rest == 0L -> "쉬지 않고 바로 이어져요"; else -> "${hours(rest)}만 쉬어요" }} (기준 ${limits.minRestHours}시간)")
            }
            // The last night of a run: the next days off.
            if (isNight(t) && !isNight(byDate[date.plusDays(1)])) {
                val need = limits.offsAfterNights
                val firstWork = (1..need).firstOrNull { k -> works(byDate[date.plusDays(k.toLong())]) }
                val known = (1..need).all { k -> byDate.containsKey(date.plusDays(k.toLong())) } || firstWork != null
                if (need > 0 && known && firstWork != null) out += Warning(Kind.NO_OFF_AFTER_NIGHT, date.plusDays(firstWork.toLong()),
                    "${md(date)} 나이트 끝나고 ${if (firstWork == 1) "바로 다음 날" else "${firstWork}일째"} 근무예요 (나이트 뒤 쉬는 날 ${need}일 기준)")
            }
        }
        runs(sorted) { isNight(it) }.filter { it.size > limits.maxNightsInRow }.forEach { r ->
            out += Warning(Kind.MANY_NIGHTS, r.first(), "${md(r.first())}부터 나이트 ${r.size}일 연속 (기준 ${limits.maxNightsInRow}일)")
        }
        runs(sorted) { works(it) }.filter { it.size > limits.maxWorkDaysInRow }.forEach { r ->
            out += Warning(Kind.LONG_RUN, r.first(), "${md(r.first())}부터 ${r.size}일 연속 근무 (쉬는 날 없이 ${limits.maxWorkDaysInRow}일 넘음)")
        }
        return out.sortedBy { it.date }
    }

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
