package com.shiftmate.core.model

import java.time.LocalDate
import java.time.LocalTime

/** How a single day differs from the pattern. */
sealed interface ShiftOverride {
    /** Follow the pattern. */
    data object None : ShiftOverride
    /** Force a day off regardless of the pattern. */
    data object Off : ShiftOverride
    /** Work this shift type instead of the pattern's. */
    data class Shift(val shiftTypeId: Long) : ShiftOverride
}

/**
 * Everything the user changed on one date. A date without an entry follows the pattern.
 */
data class DayEntry(
    val date: LocalDate,
    val override: ShiftOverride = ShiftOverride.None,
    /** Optional start/end/break for this day only, e.g. a part-timer who left early. */
    val customStart: LocalTime? = null,
    val customEnd: LocalTime? = null,
    val customBreakMinutes: Int? = null,
    /** Extra minutes worked on a scheduled working day (연장 근무). */
    val overtimeMinutes: Int = 0,
    /** Minutes worked on a day off (휴일 근무). */
    val holidayWorkMinutes: Int = 0,
    /** Skip the shift alarm on this day only. */
    val alarmSkipped: Boolean = false,
) {
    val hasCustomTimes: Boolean get() = customStart != null && customEnd != null

    /** True when this entry changes nothing and can be deleted. */
    val isEmpty: Boolean
        get() = override == ShiftOverride.None && customStart == null && customEnd == null &&
            customBreakMinutes == null && overtimeMinutes == 0 && holidayWorkMinutes == 0 && !alarmSkipped
}
