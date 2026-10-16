package com.bamnat.core.model

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
    /**
     * Alarms set by hand for this date. They replace the shift's alarms on this day;
     * null means the day follows its shift type.
     */
    val customAlarms: List<DayAlarm>? = null,
    /** What the extra hours were: 잔업, 특근 or 대근. */
    val extraWork: ExtraWork? = null,
    /** 연차·반차·반반차 taken on this day [FEAT-10]. */
    val leave: Leave? = null,
    /**
     * A second shift on the same day, e.g. 데이 then a 나이트 대근 [FEAT-02]. It keeps its own
     * clock times; all of it is work on top of the day's own shift.
     */
    val secondShiftId: Long? = null,
) {
    val hasCustomTimes: Boolean get() = customStart != null && customEnd != null

    /** True when this entry changes nothing and can be deleted. */
    val isEmpty: Boolean
        get() = override == ShiftOverride.None && customStart == null && customEnd == null &&
            customBreakMinutes == null && overtimeMinutes == 0 && holidayWorkMinutes == 0 && !alarmSkipped &&
            customAlarms == null && extraWork == null && leave == null && secondShiftId == null
}

/** Kinds of extra work shift workers mark on the calendar. */
enum class ExtraWork(val label: String, val short: String) {
    OVERTIME("잔업", "잔"),
    SPECIAL("특근", "특"),
    COVER("대근", "대"),
    ;

    companion object {
        fun fromCode(code: Int): ExtraWork? = entries.getOrNull(code - 1)
    }

    /** Stored code; 0 means none. */
    val code: Int get() = ordinal + 1
}

/** An alarm at a clock time on one date, set from the calendar. */
data class DayAlarm(val time: LocalTime, val enabled: Boolean = true)

/** Kinds of leave; [paid] keeps the day's pay, [annual] counts against 연차 [FEAT-10]. */
enum class LeaveKind(val label: String, val paid: Boolean, val annual: Boolean) {
    ANNUAL("연차", paid = true, annual = true),
    PAID("유급 휴가", paid = true, annual = false),
    UNPAID("무급 휴가", paid = false, annual = false),
    ;

    companion object {
        fun fromCode(code: Int): LeaveKind? = entries.getOrNull(code - 1)
    }

    val code: Int get() = ordinal + 1
}

/**
 * Leave on one day in quarters of a day [FEAT-10]: 4 = 종일, 2 = 반차, 1 = 반반차.
 * Partial leave is taken from the end of the shift.
 */
data class Leave(val kind: LeaveKind = LeaveKind.ANNUAL, val quarters: Int = 4) {
    init {
        require(quarters in 1..4) { "quarters must be 1..4" }
    }

    val days: Double get() = quarters / 4.0
    val isFullDay: Boolean get() = quarters == 4

    /** 연차, 반차, 반반차, or "0.75일 연차". */
    val label: String
        get() {
            val base = if (kind == LeaveKind.ANNUAL) "" else kind.label + " "
            return when (quarters) {
                4 -> if (kind == LeaveKind.ANNUAL) "연차" else kind.label
                2 -> base + "반차"
                1 -> base + "반반차"
                else -> "0.75일 " + kind.label
            }
        }

    /** Short mark for calendar cells. */
    val short: String get() = when (quarters) { 4 -> if (kind == LeaveKind.UNPAID) "무" else "연"; 3 -> "¾"; 2 -> "반"; else -> "¼" }

    /** One stored integer: kind code × 10 + quarters; 0 means no leave. */
    val code: Int get() = kind.code * 10 + quarters

    companion object {
        val QUARTER_OPTIONS = listOf(4, 2, 1, 3)

        fun fromCode(code: Int): Leave? {
            if (code <= 0) return null
            val kind = LeaveKind.fromCode(code / 10) ?: return null
            val q = code % 10
            return if (q in 1..4) Leave(kind, q) else null
        }
    }
}
