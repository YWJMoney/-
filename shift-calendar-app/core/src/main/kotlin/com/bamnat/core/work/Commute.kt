package com.bamnat.core.work

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.schedule.LegalBreak
import com.bamnat.core.schedule.ResolvedDay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [DAYW-07][DAYW-06] What arriving at work and leaving it write on a day worker's calendar, from the phone
 * noticing the workplace (출퇴근 자동 기록) or from a company's 근태 export: a start later than the day's is
 * a later start, an end before it an earlier end, an end after it 잔업 from the end, and work on a day off
 * 특근. Arriving early writes nothing (일찍 온 것을 일한 것으로 치지 않아요).
 */
object Commute {
    /** Late or early by less than this is on time. */
    const val SLACK_MINUTES = 10
    /** Work on a day off shorter than this is not written (passing by the workplace). */
    const val MIN_REST_DAY_MINUTES = 30
    /** An arrival older than this is no longer the one a leave after midnight belongs to. */
    const val MAX_SHIFT_MINUTES = 20 * 60L

    sealed interface Write {
        /** The day starts later: [start] to the day's [end]. */
        data class Late(val start: LocalTime, val end: LocalTime) : Write
        /** The day ends earlier: the day's [start] to [end]. */
        data class EarlyEnd(val start: LocalTime, val end: LocalTime) : Write
        /** 잔업 after the day's end (an untimed one when [entry] has only minutes). */
        data class Overtime(val entry: ExtraEntry, val minutes: Int) : Write
        /** 특근 on a day off. */
        data class RestDayWork(val entry: ExtraEntry, val minutes: Int) : Write
        /** Nothing to write. */
        data object None : Write
    }

    /** [DAYW-07] Arriving at [at] on [day]: late when past the start by more than [SLACK_MINUTES]. */
    fun arrive(day: ResolvedDay, at: LocalDateTime, rounding: Boolean = true): Write {
        val from = day.shiftFrom ?: return Write.None
        val to = day.shiftTo ?: return Write.None
        if (!day.isWorkDay) return Write.None
        // On a 반차 day which half was taken is not known, so a late arrival may be the planned one.
        if (partialLeave(day)) return Write.None
        val t = if (rounding) DayWork.round(at) else at
        return if (Duration.between(from, t).toMinutes() > SLACK_MINUTES && t.isBefore(to)) Write.Late(t.toLocalTime(), to.toLocalTime()) else Write.None
    }

    /**
     * [DAYW-07] Leaving at [at] on [day], having arrived at [arrived] if known: on a working day an earlier end
     * or 잔업 to now (as 지금 퇴근 does, [DayWork.leaveNow]); on a day off 특근 from arriving to leaving.
     */
    fun leave(day: ResolvedDay, arrived: LocalDateTime?, at: LocalDateTime, rounding: Boolean = true): Write {
        val t = if (rounding) DayWork.round(at) else at
        if (day.isWorkDay && day.shiftFrom != null && day.shiftTo != null) {
            val from = day.shiftFrom!!
            // Against the planned end, never an end written earlier (a lunch walk's 퇴근 12:00).
            val to = plannedTo(day)
            val past = Duration.between(to, t).toMinutes()
            return when {
                past > SLACK_MINUTES -> {
                    val minutes = past.toInt().coerceAtMost(16 * 60)
                    Write.Overtime(ExtraEntry(ExtraKind.OVERTIME_ID, start = to.toLocalTime(), end = to.plusMinutes(minutes.toLong()).toLocalTime()), minutes)
                }
                past < -SLACK_MINUTES && t.isAfter(from) && !partialLeave(day) -> Write.EarlyEnd(from.toLocalTime(), t.toLocalTime())
                else -> Write.None
            }
        }
        if (day.isWorkDay || arrived == null) return Write.None
        val a = if (rounding) DayWork.round(arrived) else arrived
        val span = Duration.between(a, t).toMinutes().toInt()
        if (span < MIN_REST_DAY_MINUTES || span > 20 * 60) return Write.None
        val brk = LegalBreak.minutesFor(span)
        return Write.RestDayWork(ExtraEntry(ExtraKind.SPECIAL_ID, start = a.toLocalTime(), end = t.toLocalTime(), breakMinutes = brk), span - brk)
    }

    /**
     * [write] on [entry]. A 잔업 or 특근 already there is replaced, so a later record just moves the time.
     * 잔업 runs from the planned end, so an earlier end written that day goes back to it.
     */
    fun apply(entry: DayEntry, write: Write): DayEntry = when (write) {
        is Write.Late -> entry.copy(customStart = write.start, customEnd = entry.customEnd ?: write.end)
        is Write.EarlyEnd -> entry.copy(customStart = entry.customStart ?: write.start, customEnd = write.end)
        is Write.Overtime -> entry.copy(extras = replace(entry.extras, write.entry), customEnd = entry.customEnd?.let { write.entry.start ?: it })
        is Write.RestDayWork -> entry.copy(extras = replace(entry.extras, write.entry))
        Write.None -> entry
    }

    /** [day] with the start [entry] now has, for deciding the end after a late start. */
    fun withStart(day: ResolvedDay, entry: DayEntry): ResolvedDay {
        val s = entry.customStart ?: return day
        val e = entry.customEnd ?: day.end ?: return day
        return day.copy(start = s, end = e, hasCustomTimes = true)
    }

    /**
     * [DAYW-07] What a leave changed on its day: the day's own times and the 잔업 or 특근 it replaced, so
     * coming back the same day (a long lunch, not 퇴근) puts the day back as it was before the leave.
     */
    data class Undo(val customStart: LocalTime?, val customEnd: LocalTime?, val kindId: Long, val extra: ExtraEntry?)

    /** What writing leave [write] on [entry] would change, or null when it is not a leave. */
    fun undoOf(entry: DayEntry, write: Write): Undo? {
        val kind = when (write) {
            is Write.EarlyEnd, is Write.Overtime -> ExtraKind.OVERTIME_ID
            is Write.RestDayWork -> ExtraKind.SPECIAL_ID
            else -> return null
        }
        return Undo(entry.customStart, entry.customEnd, kind, entry.extras.firstOrNull { it.kindId == kind })
    }

    /** [entry] as it was before the leave [undo] was taken from. */
    fun undo(entry: DayEntry, undo: Undo): DayEntry {
        val extras = undo.extra?.let { replace(entry.extras, it) } ?: entry.extras.filter { it.kindId != undo.kindId }
        return entry.copy(customStart = undo.customStart, customEnd = undo.customEnd, extras = extras)
    }

    /**
     * [DAYW-07] The day a leave at [at] belongs to: the day arrived on when that is today; after midnight
     * yesterday's arrival, only while it is under [MAX_SHIFT_MINUTES] old and no leave was recorded after it
     * (a missed 출근 on day 2 must not turn day 1 into 16 hours of 잔업). Null when it belongs to none.
     */
    fun leaveDay(at: LocalDateTime, arrivedAt: LocalDateTime?, leftAt: LocalDateTime?): LocalDate? {
        val arrived = arrivedAt ?: return null
        val date = at.toLocalDate()
        if (arrived.toLocalDate() == date) return date
        if (arrived.toLocalDate() != date.minusDays(1)) return null
        if (leftAt != null && leftAt.isAfter(arrived) && leftAt.isBefore(at)) return null
        val minutes = Duration.between(arrived, at).toMinutes()
        return if (minutes in 0 until MAX_SHIFT_MINUTES) arrived.toLocalDate() else null
    }

    /** [DAYW-07] Coming in at [at] within [waitMinutes] of a leave seen at [pending]: a walk, not 퇴근 (midnight or not). */
    fun cameBack(pending: LocalDateTime, at: LocalDateTime, waitMinutes: Long): Boolean =
        Math.abs(Duration.between(pending, at).toMinutes()) <= waitMinutes

    /** A 반차·반반차 day: the resolver shortens its end by the leave. */
    private fun partialLeave(day: ResolvedDay): Boolean = day.leave?.isFullDay == false

    /**
     * The planned shift's end, never an end written earlier: the later of the day's end and the shift type's
     * (Commute only writes ends at or before the type's, so a later one is the user's own longer day). On a
     * 반차 day the full day's end, since the leave already shortened [ResolvedDay.end] (13:30–18:00 is not 잔업).
     */
    /** When the shift on [day] is planned to end, as 퇴근 is judged against (the app's 출퇴근 자동 기록 uses it too). */
    fun plannedTo(day: ResolvedDay): LocalDateTime {
        val from = day.shiftFrom!!
        val own = if (partialLeave(day)) {
            val fullEnd = if (day.hasCustomTimes) day.entry?.customEnd else day.shiftType?.end
            fullEnd?.let { ResolvedDay.endAfter(from, it) } ?: day.shiftTo!!
        } else day.shiftTo!!
        val type = day.shiftType
        val ts = type?.start
        val te = type?.end
        if (!day.hasCustomTimes || ts == null || te == null) return own
        val typeEnd = ResolvedDay.endAfter(day.date.atTime(ts), te)
        return if (typeEnd.isAfter(own)) typeEnd else own
    }

    private fun replace(extras: List<ExtraEntry>, add: ExtraEntry): List<ExtraEntry> {
        val i = extras.indexOfFirst { it.kindId == add.kindId }
        return if (i < 0) extras + add else extras.toMutableList().also { it[i] = add }
    }
}
