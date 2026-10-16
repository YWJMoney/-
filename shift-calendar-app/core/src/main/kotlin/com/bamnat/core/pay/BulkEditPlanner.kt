package com.bamnat.core.pay

import java.time.YearMonth

/** Where a changed value applies [PAYX-03]. */
sealed interface EditScope {
    /** "이 달만": the month, then back to what it would have been. */
    data class OnlyMonth(val month: YearMonth) : EditScope
    /** "이 달부터 계속": from the month on; later entered values are replaced. */
    data class FromMonth(val month: YearMonth) : EditScope
    /** "기간 지정": the months from [from] to [to], then back. */
    data class Range(val from: YearMonth, val to: YearMonth) : EditScope
}

data class MonthDiff(val month: YearMonth, val before: Long, val after: Long, val locked: Boolean) {
    val delta: Long get() = after - before
}

/** A change ready to apply: the new timeline, what it does to each month, and the way back. */
data class EditPlan(
    val before: ValueTimeline,
    val after: ValueTimeline,
    val diffs: List<MonthDiff>,
) {
    val changedMonths: List<MonthDiff> get() = diffs.filter { it.delta != 0L }
    val totalDelta: Long get() = diffs.filterNot { it.locked }.sumOf { it.delta }
}

/**
 * Plans a change to one value over time and previews its effect on monthly totals before it is
 * applied [PAYX-03]. Applying means storing [EditPlan.after]; undo means storing
 * [EditPlan.before], so every bulk change can be taken back in one step.
 */
object BulkEditPlanner {
    fun plan(
        timeline: ValueTimeline,
        newValue: Long,
        scope: EditScope,
        preview: List<YearMonth>,
        /** Monthly total for a timeline, e.g. a [PayCalculator] run with the value swapped in. */
        totalFor: (ValueTimeline, YearMonth) -> Long,
        /** Closed months keep their saved result unless the user asks to recalculate. */
        isLocked: (YearMonth) -> Boolean = { false },
    ): EditPlan {
        val after = apply(timeline, newValue, scope)
        val diffs = preview.map { m ->
            MonthDiff(m, totalFor(timeline, m), totalFor(after, m), isLocked(m))
        }
        return EditPlan(timeline, after, diffs)
    }

    fun apply(timeline: ValueTimeline, newValue: Long, scope: EditScope): ValueTimeline = when (scope) {
        is EditScope.FromMonth -> {
            val start = scope.month.atDay(1)
            timeline.copy(entries = timeline.entries.filter { it.from.isBefore(start) } + DatedValue(start, newValue))
        }
        is EditScope.OnlyMonth -> applyRange(timeline, newValue, scope.month, scope.month)
        is EditScope.Range -> applyRange(timeline, newValue, scope.from, scope.to)
    }

    private fun applyRange(timeline: ValueTimeline, newValue: Long, from: YearMonth, to: YearMonth): ValueTimeline {
        val start = from.atDay(1)
        val resume = to.plusMonths(1).atDay(1)
        // What the month after the range would have been, so it continues as before.
        val restore = timeline.on(resume)
        var t = timeline.copy(entries = timeline.entries.filter { it.from.isBefore(start) || !it.from.isBefore(resume) })
        t = t.with(DatedValue(start, newValue))
        if (timeline.entries.none { it.from == resume } && restore.source != ValueSource.NONE) {
            t = t.with(DatedValue(resume, restore.value))
        }
        return t
    }
}
