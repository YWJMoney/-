package com.bamnat.core.sleep

import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.ScheduleResolver
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-14] Work, sleep and naps over a few days, worked out once so the today card, the day view
 * and the notices say the same thing. It reads the work as it sits on the clock (a 대근 or 특근 the
 * same day, a 24-hour duty), the alarms that will really ring, and the user's time to get home.
 *
 * - After work that runs past midnight (a night, a 24-hour duty): sleep from the end plus the way
 *   home, for the target or at least 6 hours, ended by the next work's alarm at the latest.
 * - Before work that starts between 03:00 and 10:00: in bed the target before its alarm (90 minutes
 *   before the start without one), but no earlier than home from the work before.
 * - Before the first night of a run: a nap of up to 90 minutes, ending 2.5 hours before it.
 */
class SleepSchedule private constructor(
    /** The work on the clock, earliest first. */
    val work: List<Work>,
    val sleeps: List<Sleep>,
    val naps: List<Nap>,
    /** The alarms that will ring, earliest first. */
    val alarms: List<PlannedAlarm>,
) {
    /** One stretch of work, on the calendar day it belongs to. */
    data class Work(val date: LocalDate, val from: LocalDateTime, val to: LocalDateTime, val name: String, val colorArgb: Long? = null) {
        val minutes: Long get() = Duration.between(from, to).toMinutes()
        /** Mostly between 22:00 and 06:00. */
        val night: Boolean get() = minutes > 0 && RestCheck.nightMinutes(from, to) * 2 >= minutes
        /** Runs past midnight, so the sleep after it falls in the day. */
        val overnight: Boolean get() = to.toLocalDate().isAfter(from.toLocalDate())
        /** Starts in the morning, so the night before it is cut short by the clock. */
        val early: Boolean get() = from.toLocalTime().let { !it.isBefore(EARLY_FROM) && it.isBefore(SleepPlan.EARLY_START) }
    }

    /** A main sleep: after work that ran overnight, or the night before an early start. */
    data class Sleep(
        val from: LocalDateTime,
        val to: LocalDateTime,
        /** The overnight work it follows; null for the night before an early start. */
        val after: Work?,
        /** The work whose alarm (or start) ends it, when that comes before the sleep is done. */
        val before: Work?,
        /** Shorter than the target, because the next work comes too soon. */
        val short: Boolean,
        /** Time off between the work before and the work after, when both are known. */
        val restMinutes: Long?,
    ) {
        val minutes: Long get() = Duration.between(from, to).toMinutes()
    }

    /** A nap before the first night of a run. */
    data class Nap(val from: LocalDateTime, val to: LocalDateTime, val before: Work) {
        val minutes: Long get() = Duration.between(from, to).toMinutes()
    }

    /** The sleep planned after [w], if there is room for one. */
    fun sleepAfter(w: Work): Sleep? = sleeps.firstOrNull { it.after == w }

    /** The nap planned before [w], if it starts a run of nights. */
    fun napBefore(w: Work): Nap? = naps.firstOrNull { it.before == w }

    /** The first alarm that rings for [w]. */
    fun alarmFor(w: Work): PlannedAlarm? = alarms.firstOrNull { belongs(it, w) }

    companion object {
        /** Starts from here to [SleepPlan.EARLY_START] count as early; earlier ones are nights. */
        val EARLY_FROM: LocalTime = LocalTime.of(3, 0)
        /** Less room than this is no sleep at all. */
        const val MIN_SLEEP_MINUTES = 60L
        const val MIN_NAP_MINUTES = 30L
        /** Nights further apart than this start a new run, with a nap before the first. */
        const val NIGHT_RUN_GAP_MINUTES = 24 * 60L

        /** Whether [a] wakes the user for [w]: it is for a shift starting within [w] and rings by its start. */
        fun belongs(a: PlannedAlarm, w: Work): Boolean {
            if (a.shiftDate != w.date) return false
            val start = a.shiftDate.atTime(a.shiftStart)
            return !start.isBefore(w.from) && start.isBefore(w.to) && !a.triggerAt.isAfter(w.from)
        }

        /** [day]'s work on the clock, as [RestCheck.blocks] sees it. */
        fun work(day: ResolvedDay): List<Work> = RestCheck.blocks(day).map { Work(day.date, it.from, it.to, it.name, it.colorArgb) }

        /** Over [from]..[to] plus a day either side, with the alarms that ring when [alarmsOn]. */
        fun of(
            resolver: ScheduleResolver,
            from: LocalDate,
            to: LocalDate,
            alarmsOn: Boolean,
            targetMinutes: Int = SleepPlan.DEFAULT_TARGET_MINUTES,
            commuteMinutes: Int = SleepPlan.COMMUTE_MINUTES.toInt(),
        ): SleepSchedule {
            val dates = generateSequence(from.minusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(to.plusDays(1)) }.toList()
            val alarms = if (alarmsOn) dates.flatMap { AlarmPlanner.alarmsOn(resolver, it) } else emptyList()
            return of(dates.map(resolver::resolve), alarms, targetMinutes, commuteMinutes)
        }

        /** From [days] (one calendar day each) and the alarms that ring. */
        fun of(
            days: List<ResolvedDay>,
            alarms: List<PlannedAlarm>,
            targetMinutes: Int = SleepPlan.DEFAULT_TARGET_MINUTES,
            commuteMinutes: Int = SleepPlan.COMMUTE_MINUTES.toInt(),
        ): SleepSchedule = plan(days.flatMap(::work), alarms, targetMinutes, commuteMinutes)

        fun plan(work: List<Work>, alarms: List<PlannedAlarm>, targetMinutes: Int, commuteMinutes: Int): SleepSchedule {
            val list = work.filter { it.minutes > 0 }.sortedBy { it.from }
            val rings = alarms.distinctBy { it.triggerAt to it.shiftDate }.sortedBy { it.triggerAt }
            val target = targetMinutes.toLong().coerceAtLeast(MIN_SLEEP_MINUTES)
            val home = commuteMinutes.toLong().coerceAtLeast(0)
            fun wake(w: Work): LocalDateTime = rings.firstOrNull { belongs(it, w) }?.triggerAt ?: w.from.minusMinutes(SleepPlan.WAKE_BEFORE_MINUTES)
            fun gap(a: Work, b: Work) = Duration.between(a.to, b.from).toMinutes()

            val sleeps = mutableListOf<Sleep>()
            val naps = mutableListOf<Nap>()
            for ((i, w) in list.withIndex()) {
                val prev = list.getOrNull(i - 1)
                val next = list.getOrNull(i + 1)
                if (w.early) {
                    val up = wake(w)
                    val bed = up.minusMinutes(target)
                    val back = prev?.to?.plusMinutes(home)
                    val from = if (back != null && back.isAfter(bed)) back else bed
                    // The sleep after a night may already run into this one.
                    val covered = sleeps.lastOrNull()?.let { !it.to.isBefore(from) } == true
                    if (!covered && Duration.between(from, up).toMinutes() >= MIN_SLEEP_MINUTES) {
                        sleeps += Sleep(from, up, after = null, before = w, short = from.isAfter(bed), restMinutes = prev?.let { gap(it, w) })
                    }
                } else if (w.night && (prev == null || !prev.night || gap(prev, w) > NIGHT_RUN_GAP_MINUTES)) {
                    val end = w.from.minusMinutes(SleepPlan.NAP_GAP_MINUTES)
                    var start = end.minusMinutes(SleepPlan.NAP_MINUTES)
                    prev?.to?.plusMinutes(home)?.let { if (it.isAfter(start)) start = it }
                    sleeps.lastOrNull()?.to?.let { if (it.isAfter(start)) start = it }
                    if (Duration.between(start, end).toMinutes() >= MIN_NAP_MINUTES) naps += Nap(start, end, w)
                }
                if (w.overnight) {
                    val from = w.to.plusMinutes(home)
                    val want = from.plusMinutes(maxOf(target, SleepPlan.MIN_AFTER_NIGHT_MINUTES.toLong()))
                    val up = next?.let(::wake)?.takeIf { it.isBefore(want) }
                    val to = up ?: want
                    if (Duration.between(from, to).toMinutes() >= MIN_SLEEP_MINUTES) {
                        sleeps += Sleep(from, to, after = w, before = next?.takeIf { up != null },
                            short = up != null && Duration.between(from, up).toMinutes() < target,
                            restMinutes = next?.let { gap(w, it) })
                    }
                }
            }
            return SleepSchedule(list, sleeps, naps, rings)
        }
    }
}
