package com.bamnat.core.work

import com.bamnat.core.model.Attendance
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [COMN-01] One piece of work on one calendar, as 합쳐 보기 shows it: a shift, extra work or a booked
 * job. [from] and [to] are null when it has no clock times (a job booked for the day).
 */
data class WorkSpan(
    val calendar: String,
    val date: LocalDate,
    val label: String,
    val from: LocalDateTime? = null,
    val to: LocalDateTime? = null,
) {
    val timed: Boolean get() = from != null && to != null && to.isAfter(from)

    /** Both on the clock and sharing at least a minute; touching ends (퇴근 9시, 출근 9시) do not count. */
    fun overlaps(other: WorkSpan): Boolean =
        timed && other.timed && from!!.isBefore(other.to) && other.from!!.isBefore(to)
}

/**
 * [COMN-01] Every calendar's work on one month grid: each calendar's pieces of work by day, and the
 * days where two calendars' work is on the clock at the same time, also when a night shift runs into
 * the next morning's other job.
 */
object CombinedView {
    /** Calendar [calendar]'s work on [day]: its shift unless missed or on leave all day, its extra work, and its jobs. */
    fun spansOf(calendar: String, day: ResolvedDay, jobs: List<Job>): List<WorkSpan> = buildList {
        val absent = day.entry?.attendance == Attendance.ABSENT
        val onLeave = day.leave?.isFullDay == true
        if (day.isWorkDay && !absent && !onLeave) {
            add(WorkSpan(calendar, day.date, day.shiftType?.name ?: "근무", day.shiftFrom, day.shiftTo))
        }
        day.extras.forEach { add(WorkSpan(calendar, day.date, it.kind.name, it.from, it.to)) }
        jobs.filter { it.covers(day.date) && it.status != JobStatus.CANCELLED }.forEach { j ->
            val from = j.start?.let { day.date.atTime(it) }
            val to = if (from != null && j.end != null) ResolvedDay.endAfter(from, j.end) else null
            add(WorkSpan(calendar, day.date, j.title.ifBlank { "일감" }, from, to))
        }
    }

    /** Pairs of work from different calendars that share time on the clock. */
    fun clashes(spans: List<WorkSpan>): List<Pair<WorkSpan, WorkSpan>> {
        val timed = spans.filter { it.timed }.sortedBy { it.from }
        val out = ArrayList<Pair<WorkSpan, WorkSpan>>()
        for (i in timed.indices) for (k in i + 1 until timed.size) {
            val a = timed[i]
            val b = timed[k]
            if (!b.from!!.isBefore(a.to)) break
            if (a.calendar != b.calendar && a.overlaps(b)) out += a to b
        }
        return out
    }

    /** The days with a clash: both days when a night shift runs into the next day's other work. */
    fun clashDays(spans: List<WorkSpan>): Set<LocalDate> =
        clashes(spans).flatMapTo(HashSet()) { (a, b) -> listOf(a.date, b.date) }

    /** [COMN-03] Days with work and minutes worked on one calendar: shifts less leave, extra work, timed jobs. */
    data class WorkTotal(val days: Int, val minutes: Int)

    fun workOf(days: List<ResolvedDay>, jobs: List<Job>): WorkTotal {
        var count = 0
        var minutes = 0
        for (day in days) {
            val spans = spansOf("", day, jobs)
            if (spans.isEmpty()) continue
            count++
            val absent = day.entry?.attendance == Attendance.ABSENT
            // scheduledMinutes is already what is left after leave (반차 09~13:30).
            if (!absent) minutes += day.scheduledMinutes
            minutes += day.extraMinutes
            minutes += jobs.filter { it.covers(day.date) && it.status != JobStatus.CANCELLED && it.start != null && it.end != null }
                .sumOf { j ->
                    val from = day.date.atTime(j.start)
                    java.time.Duration.between(from, ResolvedDay.endAfter(from, j.end!!)).toMinutes().toInt()
                }
        }
        return WorkTotal(count, minutes)
    }
}
