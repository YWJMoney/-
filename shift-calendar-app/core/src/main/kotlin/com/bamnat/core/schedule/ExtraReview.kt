package com.bamnat.core.schedule

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern

/**
 * [EXTRA-05] Records from before extra work had its own times that need a look once.
 *
 * Before 1.9 a 대근 on a day off was often entered by changing the day's shift and tapping the
 * 대근 chip, which added hours of its own. The calendar then counted the shift as part of the
 * roster (already in the salary) and the chip's hours on top, so the day was paid wrong both ways.
 */
object ExtraReview {
    /** Days where a day off was changed into a shift and marked 특근·대근 with hours only, earliest first. */
    fun shiftSwapCovers(types: Collection<ShiftType>, patterns: List<WorkPattern>, entries: Collection<DayEntry>): List<DayEntry> {
        val roster = ScheduleResolver(types, patterns, emptyList())
        val byId = types.associateBy { it.id }
        return entries.filter { e ->
            val o = e.override as? ShiftOverride.Shift ?: return@filter false
            byId[o.shiftTypeId]?.hasTimes == true && !roster.resolve(e.date).isWorkDay && e.extras.any { it.isOldCover() }
        }.sortedBy { it.date }
    }

    /**
     * The day back to the roster's day off, with the shift it had as a 대근 of that shift's hours
     * (or the day's own times when it had them). The chip's hours are dropped: they were never worked.
     */
    fun convert(entry: DayEntry, type: ShiftType): DayEntry {
        val i = entry.extras.indexOfFirst { it.isOldCover() }
        val kindId = entry.extras.getOrNull(i)?.kindId ?: ExtraKind.COVER_ID
        val own = entry.hasCustomTimes
        val cover = ExtraEntry(
            kindId = kindId,
            start = if (own) entry.customStart else type.start,
            end = if (own) entry.customEnd else type.end,
            breakMinutes = (if (own) entry.customBreakMinutes else null) ?: type.breakMinutes,
            breakStart = type.breakStart.takeIf { !own },
            coveredShiftId = type.id,
        )
        return entry.copy(
            override = ShiftOverride.None, customStart = null, customEnd = null, customBreakMinutes = null,
            extras = listOf(cover) + entry.extras.filterIndexed { j, _ -> j != i },
        )
    }

    private fun ExtraEntry.isOldCover() =
        !hasTimes && coveredShiftId == null && (kindId == ExtraKind.SPECIAL_ID || kindId == ExtraKind.COVER_ID)
}
