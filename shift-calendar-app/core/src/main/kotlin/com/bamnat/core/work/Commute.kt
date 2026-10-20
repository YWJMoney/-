package com.bamnat.core.work

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.schedule.LegalBreak
import com.bamnat.core.schedule.ResolvedDay
import java.time.Duration
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
            val to = day.shiftTo!!
            val past = Duration.between(to, t).toMinutes()
            return when {
                past > SLACK_MINUTES -> {
                    val minutes = past.toInt().coerceAtMost(16 * 60)
                    Write.Overtime(ExtraEntry(ExtraKind.OVERTIME_ID, start = to.toLocalTime(), end = to.plusMinutes(minutes.toLong()).toLocalTime()), minutes)
                }
                past < -SLACK_MINUTES && t.isAfter(from) -> Write.EarlyEnd(from.toLocalTime(), t.toLocalTime())
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

    /** [write] on [entry]. A 잔업 or 특근 already there is replaced, so a later record just moves the time. */
    fun apply(entry: DayEntry, write: Write): DayEntry = when (write) {
        is Write.Late -> entry.copy(customStart = write.start, customEnd = entry.customEnd ?: write.end)
        is Write.EarlyEnd -> entry.copy(customStart = entry.customStart ?: write.start, customEnd = write.end)
        is Write.Overtime -> entry.copy(extras = replace(entry.extras, write.entry))
        is Write.RestDayWork -> entry.copy(extras = replace(entry.extras, write.entry))
        Write.None -> entry
    }

    /** [day] with the start [entry] now has, for deciding the end after a late start. */
    fun withStart(day: ResolvedDay, entry: DayEntry): ResolvedDay {
        val s = entry.customStart ?: return day
        val e = entry.customEnd ?: day.end ?: return day
        return day.copy(start = s, end = e, hasCustomTimes = true)
    }

    private fun replace(extras: List<ExtraEntry>, add: ExtraEntry): List<ExtraEntry> {
        val i = extras.indexOfFirst { it.kindId == add.kindId }
        return if (i < 0) extras + add else extras.toMutableList().also { it[i] = add }
    }
}
