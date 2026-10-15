package com.bamnat.core.pay

import com.bamnat.core.model.LeaveKind
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate

/** 연차 used in a year against what the company grants [FEAT-10]. */
data class LeaveBalance(
    val year: Int,
    /** Days granted for the year; null when the user has not said. */
    val granted: Double?,
    val used: Double,
    /** Leave recorded on dates still ahead (planned). */
    val planned: Double,
    val byKind: Map<LeaveKind, Double>,
) {
    val remaining: Double? get() = granted?.let { it - used - planned }
}

object LeaveBook {
    /** Counts leave on working days of [year]; [resolve] is the calendar. */
    fun balance(year: Int, granted: Double?, today: LocalDate, resolve: (LocalDate) -> ResolvedDay): LeaveBalance {
        var used = 0.0
        var planned = 0.0
        val byKind = linkedMapOf<LeaveKind, Double>()
        var d = LocalDate.of(year, 1, 1)
        while (d.year == year) {
            val leave = resolve(d).leave
            if (leave != null) {
                byKind[leave.kind] = (byKind[leave.kind] ?: 0.0) + leave.days
                if (leave.kind.annual) if (d.isAfter(today)) planned += leave.days else used += leave.days
            }
            d = d.plusDays(1)
        }
        return LeaveBalance(year, granted, used, planned, byKind)
    }

    /** "3.75일", "2일". */
    fun days(value: Double): String =
        if (value == Math.floor(value)) "${value.toLong()}일" else "${String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')}일"
}
