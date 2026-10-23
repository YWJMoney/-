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
    /** Skip the shift alarm on this day only. */
    val alarmSkipped: Boolean = false,
    /**
     * Alarms set by hand for this date. They replace the shift's alarms on this day;
     * null means the day follows its shift type.
     */
    val customAlarms: List<DayAlarm>? = null,
    /** 연차·반차·반반차 taken on this day [FEAT-10]. */
    val leave: Leave? = null,
    /**
     * [EXTRA-01] Work on top of the day's own shift, or on a day off: 잔업, 특근, 대근 … in the order
     * the user added them. A 대근 for a 나이트 [FEAT-02] is one of these with the night's times.
     */
    val extras: List<ExtraEntry> = emptyList(),
    /** [HRLY-01] How the day went for a part-timer: 지각, 조퇴 or 결근; null for a normal day. */
    val attendance: Attendance? = null,
    /** [SHFT-03] Who the day was swapped with (근무 교환), or empty. */
    val swapWith: String = "",
) {
    val hasCustomTimes: Boolean get() = customStart != null && customEnd != null

    /** True when this entry changes nothing and can be deleted. */
    val isEmpty: Boolean
        get() = override == ShiftOverride.None && customStart == null && customEnd == null &&
            customBreakMinutes == null && !alarmSkipped && customAlarms == null && leave == null && extras.isEmpty() &&
            attendance == null && swapWith.isBlank()
}

/**
 * [HRLY-01] A part-timer's attendance. 지각 and 조퇴 keep the week's 개근 for 주휴수당; 결근 does not
 * and the day is not worked (고용노동부 해석: 지각·조퇴는 결근이 아님).
 */
enum class Attendance(val label: String, val code: Int) {
    LATE("지각", 1), EARLY("조퇴", 2), ABSENT("결근", 3);

    companion object {
        fun fromCode(code: Int): Attendance? = entries.firstOrNull { it.code == code }
    }
}

/** An alarm at a clock time on one date, set from the calendar. */
/**
 * One alarm set by hand for a day. [DAYW-09] [dayBefore] rings on the evening before (a night shift's 23:00 nap
 * alarm), which the day sheet used to drop on saving (I 리뷰 P13).
 */
data class DayAlarm(val time: LocalTime, val enabled: Boolean = true, val dayBefore: Boolean = false) {
    /** When it rings for the shift day [date]. */
    fun at(date: java.time.LocalDate): java.time.LocalDateTime = (if (dayBefore) date.minusDays(1) else date).atTime(time)
}

/** Kinds of leave; [paid] keeps the day's pay, [annual] counts against 연차 [FEAT-10]. */
enum class LeaveKind(val label: String, val paid: Boolean, val annual: Boolean) {
    ANNUAL("연차", paid = true, annual = true),
    PAID("유급 휴가", paid = true, annual = false),
    UNPAID("무급 휴가", paid = false, annual = false),
    /** [PAYX-18] 보상휴가 (근로기준법 제57조): paid, taken from the 보상휴가 earned, not from 연차. Last, so codes stay. */
    COMP("보상휴가", paid = true, annual = false),
    ;

    companion object {
        fun fromCode(code: Int): LeaveKind? = entries.getOrNull(code - 1)
    }

    val code: Int get() = ordinal + 1
}

/**
 * Leave on one day in quarters of a day [FEAT-10]: 4 = 종일, 2 = 반차, 1 = 반반차.
 * Partial leave is taken from the end of the shift, or from its start when [fromStart] (오전 반차:
 * coming in later, so the morning alarm moves too).
 */
data class Leave(val kind: LeaveKind = LeaveKind.ANNUAL, val quarters: Int = 4, val fromStart: Boolean = false) {
    init {
        require(quarters in 1..4) { "quarters must be 1..4" }
    }

    val days: Double get() = quarters / 4.0
    val isFullDay: Boolean get() = quarters == 4
    /** Taken off the start of the shift; a whole day has no start or end to take it from. */
    val offStart: Boolean get() = fromStart && !isFullDay

    /** 연차, 반차, 반반차, or "0.75일 연차". */
    val label: String
        get() {
            val base = if (kind == LeaveKind.ANNUAL) "" else kind.label + " "
            val part = when (quarters) {
                4 -> return if (kind == LeaveKind.ANNUAL) "연차" else kind.label
                2 -> base + "반차"
                1 -> base + "반반차"
                else -> "0.75일 " + kind.label
            }
            return if (offStart) "오전 $part" else part
        }

    /** Short mark for calendar cells. */
    val short: String get() = when (quarters) { 4 -> when (kind) { LeaveKind.UNPAID -> "무"; LeaveKind.COMP -> "보"; else -> "연" }; 3 -> "¾"; 2 -> "반"; else -> "¼" }

    /** One stored integer: kind code × 10 + quarters, plus 100 when taken off the start; 0 means no leave. */
    val code: Int get() = kind.code * 10 + quarters + if (offStart) FROM_START else 0

    companion object {
        val QUARTER_OPTIONS = listOf(4, 2, 1, 3)

        /** Added to [code] for leave off the start; an app before 1.22 reads such a code as no leave. */
        private const val FROM_START = 100

        fun fromCode(code: Int): Leave? {
            if (code <= 0) return null
            val rest = code % FROM_START
            val kind = LeaveKind.fromCode(rest / 10) ?: return null
            val q = rest % 10
            return if (q in 1..4) Leave(kind, q, fromStart = code >= FROM_START && q < 4) else null
        }
    }
}
