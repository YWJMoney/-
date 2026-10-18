package com.bamnat.core.setup

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkTime
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [SETUP-01] "오늘 무슨 근무예요?": where the user is in the rotation, asked by what they work rather
 * than by a step number, and the anchor (step 0's date) the answer gives.
 */
object TodayQuestion {
    /** A night shift is still "yesterday's" until an hour after it ends, but never past noon. */
    private const val AFTER_END_MINUTES = 60
    private const val NOON_MINUTES = 12 * 60

    /**
     * The day the question is about. In the small hours a night shift that began yesterday is
     * yesterday's work, so while one may still be on (or just ended) the question asks about yesterday.
     */
    fun questionDate(now: LocalDateTime, types: Collection<ShiftType>): LocalDate {
        val ends = types.filter { it.hasTimes && WorkTime.isOvernight(it.start!!, it.end!!) }
            .map { it.end!!.hour * 60 + it.end.minute }
        val until = ends.maxOrNull()?.let { minOf(it + AFTER_END_MINUTES, NOON_MINUTES) } ?: return now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        return if (minute < until) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    /** The shifts to choose from, each once, in the order the rotation first has them. */
    fun choices(steps: List<Long?>): List<Long?> = steps.distinct()

    /** The step 0 date that puts [date] on [step]. */
    fun anchor(date: LocalDate, step: Int): LocalDate = date.minusDays(step.toLong())

    /** The steps [answer] could be. */
    fun candidates(steps: List<Long?>, answer: Long?): List<Int> = steps.indices.filter { steps[it] == answer }

    /** What else to ask when [answer] alone does not say which step today is; null when it does. */
    fun followUp(steps: List<Long?>, answer: Long?, isOff: (Long?) -> Boolean): FollowUp? {
        val n = steps.size
        val found = distinctSteps(steps, candidates(steps, answer))
        if (found.size <= 1) return null
        run(steps, found)?.let { return FollowUp.RunDay(answer, it) }
        if (isOff(answer)) {
            val next = found.map { i ->
                val d = (1..n).firstOrNull { !isOff(steps[(i + it) % n]) }
                FollowUp.NextWork.Option(i, d ?: 0, d?.let { steps[(i + it) % n] })
            }
            if (next.none { it.inDays == 0 } && next.distinctBy { it.inDays to it.type }.size == next.size) {
                return FollowUp.NextWork(next.sortedWith(compareBy({ it.inDays }, { it.step })))
            }
        }
        return FollowUp.Upcoming(upcoming(steps, found))
    }

    /**
     * Steps that give a different calendar: in a rotation that repeats inside itself (주야주야 as 4 days)
     * two steps can be the same day, so only the first of them is kept.
     */
    private fun distinctSteps(steps: List<Long?>, found: List<Int>): List<Int> {
        val n = steps.size
        val seen = HashSet<List<Long?>>()
        return found.filter { i -> seen.add((0 until n).map { steps[(i + it) % n] }) }
    }

    /** [found] in run order when they are one run of the same shift (around the end of the order too). */
    private fun run(steps: List<Long?>, found: List<Int>): List<Int>? {
        val n = steps.size
        if (found.size == n) return null
        val set = found.toSet()
        // The run starts where the step before is something else.
        val start = found.firstOrNull { (it - 1 + n) % n !in set } ?: return null
        val ordered = (0 until found.size).map { (start + it) % n }
        return ordered.takeIf { it.toSet() == set }
    }

    /** The days after each step, as few as tell them apart (at least three, at most the whole order). */
    private fun upcoming(steps: List<Long?>, found: List<Int>): List<FollowUp.Upcoming.Option> {
        val n = steps.size
        val length = (1..n).firstOrNull { len ->
            found.map { i -> (1..len).map { steps[(i + it) % n] } }.distinct().size == found.size
        } ?: n
        val shown = length.coerceAtLeast(3).coerceAtMost(n)
        return found.map { i -> FollowUp.Upcoming.Option(i, (1..shown).map { steps[(i + it) % n] }) }
    }

    /** Where [step] sits in its run of the same shift: 1-based position and the run's length. */
    fun runPosition(steps: List<Long?>, step: Int): Pair<Int, Int> {
        val n = steps.size
        val id = steps[step]
        if (steps.all { it == id }) return 1 to 1
        var back = 0
        while (steps[(step - back - 1 + 2 * n) % n] == id) back++
        var ahead = 0
        while (steps[(step + ahead + 1) % n] == id) ahead++
        return (back + 1) to (back + ahead + 1)
    }

    /** Days from [step] to the next step that is not [step]'s shift, and that step. */
    fun nextChange(steps: List<Long?>, step: Int): Pair<Int, Int>? {
        val n = steps.size
        val id = steps[step]
        return (1 until n).firstOrNull { steps[(step + it) % n] != id }?.let { it to (step + it) % n }
    }
}

/** [SETUP-01] The second question, when the shift alone does not tell the day. */
sealed interface FollowUp {
    /** "야간 며칠째예요?": [steps] are the run's days in order (첫째 날, 둘째 날 …). */
    data class RunDay(val type: Long?, val steps: List<Int>) : FollowUp

    /** "다음 출근이 언제예요?": for a day off, days until the next work and what it is. */
    data class NextWork(val options: List<Option>) : FollowUp {
        data class Option(val step: Int, val inDays: Int, val type: Long?)
    }

    /** "내일부터는 어떻게 돼요?": the days that follow each step, enough to tell them apart. */
    data class Upcoming(val options: List<Option>) : FollowUp {
        data class Option(val step: Int, val days: List<Long?>)
    }
}
