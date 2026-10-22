package com.bamnat.core.pay

/**
 * [DAILY-16] 결정 D36: the user's own 일당·시급 next to the 광장 시세 the phone last looked at, compared on
 * the phone only (nothing about the user's pay goes to the server). A line shows only when the group is
 * the same kind of pay, at least [MIN_PEOPLE] people shared it, and the gap is [MIN_GAP_PERCENT]% or more.
 */
object RateHint {
    /** One 광장 시세 group as the phone keeps it: region, trade, unit ("day", "hour", "month") and its middle. */
    data class Group(val region: String, val trade: String, val unit: String, val year: Int, val n: Int, val low: Long, val mid: Long, val high: Long)

    data class Hint(val group: Group, val mine: Long, val percent: Int) {
        val below: Boolean get() = percent < 0

        fun text(unitName: String): String {
            val where = listOf(group.region, group.trade).filter { it.isNotBlank() }.joinToString(" ")
            val gap = if (below) "${-percent}% 낮아요" else "${percent}% 높아요"
            return "$where 광장 시세 $unitName 가운데값 ${won(group.mid)}보다 내 $unitName ${won(mine)}이 $gap (${group.n}명이 나눔)"
        }
    }

    const val MIN_PEOPLE = 3
    const val MIN_GAP_PERCENT = 10

    fun unitOf(wageType: WageType): String? = when (wageType) {
        WageType.DAILY -> "day"
        WageType.HOURLY -> "hour"
        else -> null
    }

    /** The group most people shared among those of the user's kind of pay, if the gap is worth a line. */
    fun of(groups: List<Group>, wageType: WageType, mine: Long): Hint? {
        val unit = unitOf(wageType) ?: return null
        if (mine <= 0) return null
        val g = groups.filter { it.unit == unit && it.n >= MIN_PEOPLE && it.mid > 0 }
            .maxWithOrNull(compareBy<Group> { it.year }.thenBy { it.n }) ?: return null
        val percent = Math.round((mine - g.mid) * 100.0 / g.mid).toInt()
        if (kotlin.math.abs(percent) < MIN_GAP_PERCENT) return null
        return Hint(g, mine, percent)
    }

    private fun won(v: Long) = "%,d원".format(v)
}
