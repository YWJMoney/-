package com.bamnat.core.pay

/**
 * [INPUT-01][INPUT-03] One company pay rule put together by taps instead of words (계획/1.24-Q-개발계획.md 347, 350;
 * research/1.24-리뷰-직접입력.md Q5, N5): what work it is about, how it is paid and by how much, and the premiums on
 * top. [sentence] says it the way people write it ("대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산"),
 * and [RuleText.read] reads that sentence back into the same rule, so a rule built here goes through the one reader
 * the pasted and photographed rules use, and a message to the developer carries words a person can read.
 */
data class RuleDraft(
    val target: Target = Target.COVER,
    val way: Way = Target.COVER.ways.first(),
    /** 기본급의 n% ([Way.BASE_SHARE]), 통상시급의 n% ([Way.ORDINARY]) or n% 가산 ([Way.PREMIUM]). */
    val percent: Double = way.startPercent,
    /** 시간당 or 1회 금액 in won ([Way.PER_HOUR], [Way.PER_TIME]). */
    val amount: Long = way.startAmount,
    /** The hours one share of 기본급 pays for ("8시간에"), or the hours one payment covers ("8시간까지"). */
    val unitHours: Int = 8,
    /** "n시간 넘으면 50% 가산"; null leaves it out. */
    val overPremium: Int? = null,
    /** "야간 50% 가산"; null leaves it out (the law's night premium still applies), 0 says "야간 가산 없음". */
    val nightPremium: Int? = null,
    /** "휴일 50% 가산"; null leaves it out. */
    val holidayPremium: Int? = null,
) {
    enum class Target(val label: String, val ways: List<Way>, val extraWork: Boolean) {
        COVER("대근", EXTRA_WAYS, true),
        SPECIAL("특근", EXTRA_WAYS, true),
        OVERTIME_WORK("잔업", EXTRA_WAYS, true),
        OVERTIME("연장", listOf(Way.PREMIUM), false),
        NIGHT("야간", listOf(Way.PREMIUM), false),
        HOLIDAY("휴일", listOf(Way.PREMIUM), false),
        SATURDAY("토요일 근무", DAY_WAYS, false),
        SUNDAY("일요일 근무", DAY_WAYS, false),
    }

    enum class Way(val label: String, val startPercent: Double, val startAmount: Long) {
        BASE_SHARE("기본급의 %", 10.0, 0),
        ORDINARY("통상시급의 %", 150.0, 0),
        PER_HOUR("시간당 금액", 0.0, 15_000),
        PER_TIME("1회 금액", 0.0, 80_000),
        PREMIUM("% 가산", 50.0, 0),
    }

    /** [target] with the way kept when it fits and the values that go with a new way. */
    fun withTarget(t: Target): RuleDraft {
        val w = way.takeIf { it in t.ways } ?: t.ways.first()
        val d = copy(target = t, way = w)
        return if (w == way) d else d.copy(percent = w.startPercent, amount = w.startAmount)
    }

    fun withWay(w: Way): RuleDraft = if (w == way || w !in target.ways) this else copy(way = w, percent = w.startPercent, amount = w.startAmount)

    /** Whether the premiums after the pay itself are asked: only extra work has an hour's pay to add them to. */
    val asksPremiums: Boolean get() = target.extraWork

    /** Nothing to say: a zero rate or amount. */
    val empty: Boolean get() = when (way) {
        Way.PER_HOUR, Way.PER_TIME -> amount <= 0
        else -> percent <= 0
    }

    /** The rule as one sentence that [RuleText.read] reads back. */
    fun sentence(): String {
        val pay = when (way) {
            Way.BASE_SHARE -> "${target.label}은 ${unitHours}시간에 기본급의 ${pct(percent)}"
            Way.ORDINARY -> "${target.label}은 통상시급의 ${pct(percent)}"
            Way.PER_HOUR -> if (target.extraWork) "${target.label} 시간당 ${won(amount)}" else "${target.label}는 시간당 ${won(amount)}"
            Way.PER_TIME -> if (target.extraWork) "${target.label} 1회 ${unitHours}시간까지 ${won(amount)}" else "${target.label} 1회 ${won(amount)}"
            Way.PREMIUM -> if (target.extraWork || target == Target.SATURDAY || target == Target.SUNDAY) "${target.label}는 시간당 ${pct(percent)} 더"
            else "${target.label} ${pct(percent)} 가산"
        }
        if (!asksPremiums) return pay
        val more = buildList {
            overPremium?.let { add("${unitHours}시간 넘으면 $it% 가산") }
            nightPremium?.let { add(if (it == 0) "야간 가산 없음" else "야간 $it% 가산") }
            holidayPremium?.let { add("휴일 $it% 가산") }
        }
        return (listOf(pay) + more).joinToString(", ")
    }

    companion object {
        /** Percent steps: 5% for shares of 기본급 and premiums, 10% for a multiple of 통상시급. */
        fun step(way: Way): Double = if (way == Way.ORDINARY) 10.0 else if (way == Way.BASE_SHARE) 1.0 else 5.0

        /** The values a tap gives first, the ones companies use most. */
        fun quick(way: Way): List<Double> = when (way) {
            Way.BASE_SHARE -> listOf(5.0, 10.0, 15.0)
            Way.ORDINARY -> listOf(100.0, 150.0, 200.0)
            Way.PREMIUM -> listOf(25.0, 50.0, 100.0)
            else -> emptyList()
        }

        private fun pct(p: Double): String = (if (p == Math.floor(p)) p.toLong().toString() else p.toString()) + "%"
    }
}

private val EXTRA_WAYS = listOf(RuleDraft.Way.BASE_SHARE, RuleDraft.Way.ORDINARY, RuleDraft.Way.PER_HOUR, RuleDraft.Way.PER_TIME)
private val DAY_WAYS = listOf(RuleDraft.Way.PREMIUM, RuleDraft.Way.PER_HOUR, RuleDraft.Way.PER_TIME)
