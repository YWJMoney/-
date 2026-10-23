package com.bamnat.core.schedule

import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraTiming

/**
 * [CAL-38] Quick input's extra-work stamp (1.22 E 리뷰 '추가 근무 도장'): one piece of extra work painted over
 * many days, each kind by its own rule for where it sits on the clock.
 *
 * - 잔업·조출 (after or before the shift): only on working days, where there is a shift to follow or lead.
 * - A 대근 of a shift: only on days off; on a working day it would be a second shift, which is asked in the day sheet.
 * - Fixed hours (특근 09–18): on any day.
 *
 * A day that already holds the same piece is left as it is, so painting over a week twice never doubles it.
 */
object ExtraStamp {
    enum class Skip { NO_SHIFT, WORK_DAY, SAME, FULL }

    /** Why [entry] of [kind] does not go on [day], or null when it does. */
    fun skip(kind: ExtraKind, entry: ExtraEntry, day: ResolvedDay, maxExtras: Int): Skip? {
        val working = day.isWorkDay && day.shiftFrom != null
        when (kind.timing) {
            is ExtraTiming.AfterShift, is ExtraTiming.BeforeShift -> if (!working) return Skip.NO_SHIFT
            is ExtraTiming.AsShift -> if (day.isWorkDay || day.leave != null) return Skip.WORK_DAY
            is ExtraTiming.Band -> Unit
        }
        val pieces = day.entry?.extras.orEmpty()
        if (pieces.any { it.kindId == entry.kindId && it.start == entry.start && it.end == entry.end && it.minutes == entry.minutes }) return Skip.SAME
        if (pieces.size >= maxExtras) return Skip.FULL
        return null
    }

    /** "근무 없는 2일", "이미 있는 1일": the days left out, for the bar's one line. */
    fun skippedText(skips: Map<Skip, Int>): String = skips.entries.filter { it.value > 0 }.joinToString(", ") { (s, n) ->
        when (s) {
            Skip.NO_SHIFT -> "근무 없는 ${n}일"
            Skip.WORK_DAY -> "근무일 ${n}일"
            Skip.SAME -> "이미 있는 ${n}일"
            Skip.FULL -> "더 넣을 수 없는 ${n}일"
        }
    }
}
