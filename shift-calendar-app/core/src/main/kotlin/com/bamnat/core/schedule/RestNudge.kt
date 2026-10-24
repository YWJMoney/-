package com.bamnat.core.schedule

import java.time.LocalDate

/**
 * [SRV-10] N5: the rest rules where the user already looks, with no new rule: one line under the next alarm
 * when the work it wakes for breaks one of [RestRules], and the month's count on the month summary.
 */
object RestNudge {
    /** The signs on [date], reading the days before it that [rules] need. [days] covers at least that. */
    fun on(days: List<ResolvedDay>, date: LocalDate, rules: RestRules): List<RestCheck.Issue> =
        if (!rules.on) emptyList() else RestCheck.check(days.filter { !it.date.isAfter(date) }, rules).filter { it.date == date }

    /** "이 근무는 휴식 11시간 미만이에요" under the next alarm, or null when the day is fine. */
    fun alarmLine(issues: List<RestCheck.Issue>): String? {
        val first = issues.firstOrNull() ?: return null
        val more = if (issues.size > 1) " 외 ${issues.size - 1}건" else ""
        return "휴식 점검: ${first.kind.label}$more"
    }

    /** "휴식 점검 3건" on the month summary, or null with none. */
    fun monthNote(count: Int): String? = if (count > 0) "휴식 점검 ${count}건" else null
}
