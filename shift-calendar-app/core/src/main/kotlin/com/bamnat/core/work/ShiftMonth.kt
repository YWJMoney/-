package com.bamnat.core.work

import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.NightShift
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth

/** [SHFT-02] How many times one shift code came up in a month. */
data class ShiftCount(val type: ShiftType, val days: Int)

/** [SHFT-03] A day worked in someone's place: swapped with them (교환) or covered for them (대근). */
data class SwapDay(val date: LocalDate, val name: String, val cover: Boolean)

/**
 * [SHFT-02][SHFT-03] A shift worker's month: each code's count in the roster's order, the night shifts
 * (the day's own and 대근 of a night), and the days swapped or covered with whom.
 */
data class ShiftMonth(
    val month: YearMonth,
    val counts: List<ShiftCount>,
    val nights: Int,
    val swaps: List<SwapDay>,
) {
    /** Days swapped with someone (근무 교환). */
    val swapped: List<SwapDay> get() = swaps.filter { !it.cover }
    /** 대근 worked this month, named or not. */
    val covers: List<SwapDay> get() = swaps.filter { it.cover }

    /** "김간호 2번, 박주임" — the people of the month's swaps and covers, most often first. */
    val names: List<Pair<String, Int>>
        get() = swaps.mapNotNull { it.name.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }

    companion object {
        /** [types] gives the roster's order; a code not on the calendar this month is left out. */
        fun of(month: YearMonth, types: List<ShiftType>, resolve: (LocalDate) -> ResolvedDay): ShiftMonth {
            val days = (1..month.lengthOfMonth()).map { resolve(month.atDay(it)) }
            val counts = counts(days, types)
            val nights = nightTimes(days)
            val swaps = days.flatMap { d ->
                val name = d.entry?.swapWith?.trim().orEmpty()
                val covers = d.extras.count { isCover(it.kind) }
                // A named day with a 대근 is the 대근's name; a named day without one is a 교환.
                when {
                    covers > 0 -> List(covers) { SwapDay(d.date, name, cover = true) }
                    name.isNotEmpty() -> listOf(SwapDay(d.date, name, cover = false))
                    else -> emptyList()
                }
            }
            return ShiftMonth(month, counts, nights, swaps)
        }

        /**
         * [STAT-07] Each code's count over [days], in [types]' order: the one count the calendar's badges, the month's
         * summary, the stats cards and the year use (1.23 점검 중-5). A working code on a day taken off whole with leave
         * is not worked, so it is not counted: the leave is, in [leaveDays].
         */
        fun counts(days: Collection<ResolvedDay>, types: List<ShiftType>): List<ShiftCount> {
            val byId = days.mapNotNull { d -> d.shiftType?.takeIf { d.isWorkDay || it.isOff } }.groupingBy { it.id }.eachCount()
            val order = types.map { it.id }
            return days.mapNotNull { it.shiftType }.distinctBy { it.id }
                .sortedBy { t -> order.indexOf(t.id).let { if (it < 0) Int.MAX_VALUE else it } }
                .mapNotNull { t -> byId[t.id]?.let { ShiftCount(t, it) } }
        }

        /** [STAT-07] Leave taken over [days] as the leave ledger counts it: 반차 half a day, 반반차 a quarter. */
        fun leaveDays(days: Collection<ResolvedDay>): Double = days.sumOf { it.leave?.days ?: 0.0 }

        /**
         * [STAT-07] Work that runs into 22~06시: each day's own shift and each 대근 of a night. Not a code's count, so
         * it goes by its own name on screen ("밤 시간에 걸친 근무"), never "야간 근무" beside the 야 badge.
         */
        fun nightTimes(days: Collection<ResolvedDay>): Int = days.sumOf { d ->
            val own = if (d.isWorkDay && d.start != null && d.end != null && NightShift.crosses(d.start, d.end)) 1 else 0
            own + d.extras.count { e -> isCover(e.kind) && e.start != null && e.end != null && NightShift.crosses(e.start!!, e.end!!) }
        }

        /** A 대근: the built-in kind or any kind that is a whole shift worked for someone else. */
        fun isCover(kind: ExtraKind): Boolean = kind.id == ExtraKind.COVER_ID || kind.timing is ExtraTiming.AsShift
    }
}
