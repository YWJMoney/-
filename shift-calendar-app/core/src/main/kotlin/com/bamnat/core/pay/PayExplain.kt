package com.bamnat.core.pay

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [PAYX-37] 계산 풀이 for every line at once, like a 명세서 with its working: the formula, the law it comes from
 * and whether part of it was guessed. A line that carries no basis of its own gets its kind's article.
 */
object PayExplain {
    fun basisOf(line: PayLine): String = line.basis.ifBlank {
        when (line.kind) {
            LineKind.BASE -> "근로계약의 기본급·시급 (근로기준법 제17조)"
            LineKind.OVERTIME -> "근로기준법 제56조 제1항 (연장근로 50% 가산)"
            LineKind.NIGHT -> "근로기준법 제56조 제3항 (22시~06시 50% 가산)"
            LineKind.HOLIDAY -> "근로기준법 제56조 제2항 (8시간까지 50%, 넘으면 100% 가산)"
            LineKind.EXTRA_HOURS -> "근로기준법 제50조·제56조 (하루 8시간·주 40시간 안은 가산 없이 100%)"
            LineKind.EXTRA -> "회사가 정한 추가 근무 수당"
            LineKind.ALLOWANCE -> "근로계약·취업규칙에 정한 수당"
            LineKind.LEAVE -> "근로기준법 제60조 (유급 연차)"
        }
    }

    /** The line written out: "formula · 근거: ..." and a note when it holds a guess. */
    fun explain(line: PayLine): String = buildString {
        append(line.formula.ifBlank { line.label })
        append("\n근거: ").append(basisOf(line))
        if (line.estimated) append("\n입력하지 않은 값을 짐작해 넣은 부분이 있어요")
    }
}

/**
 * [PAYX-39] What changed between a closed month and the same month worked out today, line by line:
 * "야간근로수당 2시간 늘어 +34,000원". Lines are matched by kind and name.
 */
object SnapshotDiff {
    data class Change(val label: String, val before: Long, val after: Long, val minutesBefore: Int, val minutesAfter: Int) {
        val diff: Long get() = after - before
        val text: String get() {
            val dm = minutesAfter - minutesBefore
            val hours = if (dm != 0) " ${PayCalculator.hours(abs(dm))} ${if (dm > 0) "늘어" else "줄어"}" else
                if (before == 0L) " 새로 생겨" else if (after == 0L) " 없어져" else ""
            return "$label$hours ${if (diff > 0) "+" else "−"}${"%,d원".format(abs(diff))}"
        }
    }

    fun lines(before: List<PayLine>, after: List<PayLine>): List<Change> {
        fun key(l: PayLine) = l.kind to l.label
        val a = before.groupBy(::key)
        val b = after.groupBy(::key)
        return (a.keys + b.keys).distinct().mapNotNull { k ->
            val x = a[k].orEmpty(); val y = b[k].orEmpty()
            Change(k.second, x.sumOf { it.amount }, y.sumOf { it.amount }, x.sumOf { it.minutes }, y.sumOf { it.minutes })
                .takeIf { it.diff != 0L || it.minutesBefore != it.minutesAfter }
        }
    }

    fun of(closed: CalcSnapshot, now: MonthPay): List<Change> = lines(closed.lines, now.lines)
}

/**
 * [PAYX-38] 만약에: the month again with a raise, more night shifts or another 월 소정근로시간, so the user sees what
 * moves the pay and how far the 추정 range narrows when an answer is known. The raise and the hours run the
 * calculator again; more nights add the month's own average night day ([DayPay]), an 어림.
 */
object WhatIf {
    data class Ask(val raisePercent: Int = 0, val moreNights: Int = 0, val monthlyHours: Int? = null)
    data class Answer(val total: Long, val base: Long, val nightDay: Long?, val ask: Ask) {
        val diff: Long get() = total - base
    }

    fun raised(profile: PayProfile, percent: Int, from: LocalDate): PayProfile {
        if (percent == 0) return profile
        fun up(t: ValueTimeline): ValueTimeline {
            val v = t.on(from).value
            return if (v == 0L) t else t.with(DatedValue(from, (v * (100 + percent) / 100.0).roundToLong()))
        }
        return profile.copy(base = up(profile.base))
    }

    /** The average money a worked night shift brings in [pay], or null without one. */
    fun nightDay(pay: MonthPay): Long? {
        val nights = pay.days.filter { it.minutes.night >= 4 * 60 }
        if (nights.isEmpty()) return null
        return nights.sumOf { it.amount } / nights.size
    }

    fun answer(profile: PayProfile, month: YearMonth, resolve: (LocalDate) -> com.bamnat.core.schedule.ResolvedDay, ask: Ask,
               calculator: PayCalculator = PayCalculator()): Answer {
        val now = calculator.calculate(profile, month, resolve, checks = false)
        var p = raised(profile, ask.raisePercent, profile.period.start(month))
        ask.monthlyHours?.let { h -> p = p.copy(monthlyHours = h.coerceIn(100, 260), estimatedFields = p.estimatedFields - Unknowns.MONTHLY_HOURS) }
        val then = if (p == profile) now else calculator.calculate(p, month, resolve, checks = false)
        val night = nightDay(then)
        return Answer(then.total + (night ?: 0) * ask.moreNights.coerceIn(0, 31), now.total, night, ask)
    }
}
