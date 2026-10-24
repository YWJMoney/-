package com.bamnat.core.pay

/**
 * [DAILY-16] 결정 D36: the user's own 일당·시급·월급 next to the 광장 시세 the phone last looked at, compared on
 * the phone only (nothing about the user's pay goes to the server). A line shows only when the group is the
 * user's own job, region and kind of pay, at least [MIN_PEOPLE] people shared it, and the gap is
 * [MIN_GAP_PERCENT]% or more. A group of another job or region says nothing about the user's pay, so none is
 * borrowed when the user's own is missing.
 */
object RateHint {
    /** One 광장 시세 group as the phone keeps it: job key, 시·도 code, trade, unit ("day", "hour", "month") and its middle. */
    data class Group(val job: String, val region: String, val trade: String, val unit: String, val year: Int, val n: Int, val low: Long, val mid: Long, val high: Long)

    /** Where the user stands on the 광장: job key, 시·도 code and, when they shared a rate of their own, its trade. */
    data class Mine(val job: String, val region: String, val trade: String = "")

    data class Hint(val group: Group, val mine: Long, val percent: Int) {
        val below: Boolean get() = percent < 0

        fun text(unitName: String, regionName: String): String {
            val where = listOf(regionName, group.trade).filter { it.isNotBlank() }.joinToString(" ")
            val gap = if (below) "${-percent}% 낮아요" else "${percent}% 높아요"
            return "$where 광장 시세 $unitName 가운데값 ${won(group.mid)}보다 내 $unitName ${won(mine)}이 $gap (${group.n}명이 나눔)"
        }
    }

    const val MIN_PEOPLE = 3
    const val MIN_GAP_PERCENT = 10

    fun unitOf(wageType: WageType): String? = when (wageType) {
        WageType.DAILY -> "day"
        WageType.HOURLY -> "hour"
        WageType.MONTHLY -> "month"
    }

    /** The 광장 job of a pay setup's 직군; 직접 설정 has none (the user's own shared rate may still say it). */
    fun jobOf(occupation: Occupation): String? = when (occupation) {
        Occupation.NURSE -> "nurse"
        Occupation.PART_TIME -> "hourly"
        Occupation.MANUFACTURING -> "factory"
        Occupation.CARE -> "care"
        Occupation.SECURITY -> "security"
        Occupation.PUBLIC -> "public"
        Occupation.POLICE -> "police"
        Occupation.FIRE -> "fire"
        Occupation.CUSTOM -> null
    }

    /**
     * The newest, most shared group of the user's own job, region and unit, if the gap is worth a line. Without
     * a trade of the user's own, a job and region with more than one trade (형틀목수 and 철근공) is left alone.
     */
    fun of(groups: List<Group>, wageType: WageType, mine: Long, who: Mine?): Hint? {
        val unit = unitOf(wageType) ?: return null
        if (mine <= 0 || who == null || who.job.isBlank() || who.region.isBlank()) return null
        val same = groups.filter { it.job == who.job && it.region == who.region && it.unit == unit && it.n >= MIN_PEOPLE && it.mid > 0 }
        val pool = if (who.trade.isNotBlank()) same.filter { it.trade == who.trade }
        else same.takeIf { s -> s.map { it.trade }.distinct().size <= 1 } ?: return null
        val g = pool.maxWithOrNull(compareBy<Group> { it.year }.thenBy { it.n }) ?: return null
        val percent = Math.round((mine - g.mid) * 100.0 / g.mid).toInt()
        if (kotlin.math.abs(percent) < MIN_GAP_PERCENT) return null
        return Hint(g, mine, percent)
    }

    private fun won(v: Long) = "%,d원".format(v)
}
