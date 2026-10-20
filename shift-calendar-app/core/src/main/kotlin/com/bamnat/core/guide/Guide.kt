package com.bamnat.core.guide

import com.bamnat.core.work.WorkStyle
import java.time.LocalDate
import java.time.LocalDateTime

/** [GUIDE-02] One calendar's checklist: for which kind of work it started, when, and whether it was put away. */
data class ChecklistState(
    val style: WorkStyle?,
    val startedOn: LocalDate,
    val dismissed: Boolean = false,
    /** "나중에": hidden until this day. */
    val laterUntil: LocalDate? = null,
)

/**
 * [GUIDE-02][GUIDE-03][GUIDE-04][GUIDE-05] What the guide keeps, all on the phone: the app's own switches
 * that are off ([off], "<calendar>/<id>", or "*" for every calendar), the features used, the tips not to show again,
 * today's tip, and each calendar's checklist.
 */
data class GuideState(
    val off: Set<String> = emptySet(),
    val used: Set<String> = emptySet(),
    val never: Set<String> = emptySet(),
    val tipDay: LocalDate? = null,
    val tipId: String? = null,
    val tipClosed: Boolean = false,
    val checklists: Map<String, ChecklistState> = emptyMap(),
) {
    /**
     * [GUIDE-05] Turned off with the app's own switch for [calendar], or the feature it belongs to is. A
     * switch kept elsewhere ([Off.ELSEWHERE]) is the app's to read; it is never "off" here.
     */
    fun isOff(feature: AppFeature, calendar: String): Boolean {
        val own = when (feature.off) {
            Off.CALENDAR -> offKey(calendar, feature.id) in off
            Off.APP -> offKey(ALL, feature.id) in off
            else -> false
        }
        if (own) return true
        return feature.under?.let { FeatureRegistry.get(it) }?.let { isOff(it, calendar) } ?: false
    }

    fun isUsed(id: String): Boolean = id in used

    companion object {
        const val ALL = "*"
        fun offKey(calendar: String, id: String) = "$calendar/$id"
    }
}

/**
 * [GUIDE-02] '이 달력으로 할 수 있는 것': the 3–6 most useful features of each kind of work, in the order
 * they are best tried, shown on a calendar's first [DAYS] days after it was made or its kind changed.
 */
object GuideChecklist {
    const val DAYS = 14L
    /** "나중에" puts it away for this long. */
    const val LATER_DAYS = 3L

    val ORDER: Map<WorkStyle, List<String>> = mapOf(
        WorkStyle.SHIFT to listOf("shift.pattern", "alarm.check", "share.link", "pay.calc", "shift.roster", "shift.health"),
        WorkStyle.DAY to listOf("pay.calc", "day.leaveNow", "day.overtimeCap", "day.bridges", "day.commute"),
        WorkStyle.HOURLY to listOf("pay.calc", "hourly.clock", "hourly.weeks", "hourly.takeHome", "hourly.contract", "hourly.offers"),
        WorkStyle.FIELD to listOf("field.mode", "field.units", "field.status", "field.due", "field.quote", "field.year"),
        WorkStyle.BUSINESS to listOf("shop.days", "shop.dayClose", "shop.cardDue", "shop.capture", "shop.target", "shop.staff"),
        WorkStyle.INVEST to listOf("invest.deposit", "invest.trade", "invest.quotes", "invest.journal", "invest.goal", "invest.alarms"),
    )

    /**
     * The checklist of [style]; a kind of work without its own list yet takes its first features from the
     * registry, so a new kind still gets a card.
     */
    fun features(style: WorkStyle?): List<AppFeature> {
        val s = style ?: WorkStyle.SHIFT
        val ids = ORDER[s] ?: return FeatureRegistry.of(s).filter { !it.id.startsWith("guide.") && it.styles.isNotEmpty() }.take(MAX)
        return ids.mapNotNull(FeatureRegistry::get)
    }

    /** A new checklist when there is none or the kind of work changed; otherwise the one there is. */
    fun start(current: ChecklistState?, style: WorkStyle?, today: LocalDate): ChecklistState =
        if (current != null && current.style == style) current else ChecklistState(style, today)

    /** One line of the card: the feature and whether it has been tried. */
    data class Item(val feature: AppFeature, val done: Boolean)

    /**
     * The card's lines for a calendar, or null when it is not shown: put away, later, past its days, all
     * tried, or turned off. A feature turned off is left out.
     */
    fun visible(state: ChecklistState?, style: WorkStyle?, today: LocalDate, used: (String) -> Boolean, off: (AppFeature) -> Boolean): List<Item>? {
        if (state == null || state.dismissed || state.style != style) return null
        if (state.laterUntil != null && today.isBefore(state.laterUntil)) return null
        if (today.isAfter(state.startedOn.plusDays(DAYS - 1))) return null
        val items = features(style).filter { !off(it) }.map { Item(it, used(it.id)) }
        if (items.isEmpty() || items.all { it.done }) return null
        return items
    }

    const val MAX = 6
}

/** [GUIDE-03] Facts about today the app reads off the calendar, from which the good moments follow. */
data class MomentFacts(
    val style: WorkStyle?,
    val now: LocalDateTime,
    val workDay: Boolean = false,
    /** Today's end of work, when today has one. */
    val shiftEnd: LocalDateTime? = null,
    /** This month's 급여일. */
    val payDate: LocalDate? = null,
    /** A part-timer's 주휴 week so far, in minutes. */
    val weekMinutes: Int? = null,
    /** Days with sales written down. */
    val salesDays: Int = 0,
    val jobs: Int = 0,
    val unpaidJobs: Int = 0,
    /** Yesterday (or the night that ended today) was a night shift. */
    val afterNight: Boolean = false,
    /** Trades written down in an investor calendar. */
    val trades: Int = 0,
)

/** [GUIDE-03] Which good moments hold now. */
object GuideMoments {
    /** "Still on the clock": from 10 minutes after the end, for 6 hours. */
    private const val PAST_FROM_MINUTES = 10L
    private const val PAST_UNTIL_HOURS = 6L
    private const val PAYDAY_DAYS = 3L

    fun of(f: MomentFacts): Set<Moment> {
        val style = f.style ?: WorkStyle.SHIFT
        val today = f.now.toLocalDate()
        val out = mutableSetOf<Moment>()
        if (style.usesPayEngine && f.payDate != null && !today.isBefore(f.payDate) && !today.isAfter(f.payDate.plusDays(PAYDAY_DAYS))) {
            out += Moment.PAYDAY
        }
        if (style == WorkStyle.DAY && f.workDay && f.shiftEnd != null &&
            !f.now.isBefore(f.shiftEnd.plusMinutes(PAST_FROM_MINUTES)) && f.now.isBefore(f.shiftEnd.plusHours(PAST_UNTIL_HOURS))
        ) out += Moment.PAST_END_TIME
        if (style == WorkStyle.HOURLY && (f.weekMinutes ?: 0) >= HOURLY_HOLIDAY_MINUTES) out += Moment.HOURLY_15H
        if (style == WorkStyle.BUSINESS && f.salesDays >= 1) out += Moment.FIRST_DAY_CLOSE
        if (style == WorkStyle.FIELD && f.jobs >= 1) out += Moment.FIRST_JOB
        if (style == WorkStyle.FIELD && f.unpaidJobs >= 1) out += Moment.UNPAID_JOB
        if (style == WorkStyle.SHIFT && f.afterNight) out += Moment.AFTER_NIGHT
        if (style == WorkStyle.INVEST && f.trades >= 1) out += Moment.FIRST_TRADE
        return out
    }

    /** 주휴수당 needs 15 hours in the week. */
    const val HOURLY_HOLIDAY_MINUTES = 15 * 60
}

/**
 * [GUIDE-03] The one tip of the day: at most one a day (the same one all day until closed), only for a
 * feature of this kind of work that is not tried yet, not turned off and not put away with "다시 보지 않기",
 * and none while 알맞은 때 한 줄 안내 is off.
 */
object GuideTips {
    data class Pick(val feature: AppFeature, val isNew: Boolean)

    fun pick(state: GuideState, calendar: String, style: WorkStyle?, moments: Set<Moment>, today: LocalDate): Pick? {
        val tips = FeatureRegistry.get("guide.tips") ?: return null
        if (state.isOff(tips, calendar)) return null
        fun fits(f: AppFeature) = f.tip != null && f.moment in moments && f.fits(style) && !state.isUsed(f.id) &&
            f.id !in state.never && !state.isOff(f, calendar)
        if (state.tipDay == today) {
            if (state.tipClosed) return null
            val shown = state.tipId?.let(FeatureRegistry::get) ?: return null
            return if (fits(shown)) Pick(shown, isNew = false) else null
        }
        val next = FeatureRegistry.all.firstOrNull(::fits) ?: return null
        return Pick(next, isNew = true)
    }
}
