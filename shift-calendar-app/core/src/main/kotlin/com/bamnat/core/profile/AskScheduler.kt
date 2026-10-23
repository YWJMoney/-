package com.bamnat.core.profile

import com.bamnat.core.guide.Moment
import java.time.LocalDate

/**
 * [EASY-05] What the question card remembers, kept on the phone: today's count, the facts put off with
 * "나중에" and until when, how many times each was put off, and the day a question was last shown (on
 * that day the one-line tip of [com.bamnat.core.guide.GuideTips] stays away).
 */
data class AskState(
    val day: LocalDate? = null,
    val shownToday: Int = 0,
    /** The fact on the card now, kept all day until answered or put off. */
    val current: String? = null,
    val snoozedUntil: Map<String, LocalDate> = emptyMap(),
    val snoozes: Map<String, Int> = emptyMap(),
) {
    fun countOn(today: LocalDate): Int = if (day == today) shownToday else 0
    /** Put off twice: only on the profile summary in settings from now on. */
    fun parked(key: FactKey): Boolean = (snoozes[key.id] ?: 0) >= AskScheduler.MAX_SNOOZES
    fun snoozed(key: FactKey, today: LocalDate): Boolean = snoozedUntil[key.id]?.let { today.isBefore(it) } == true
}

/** [EASY-05] Where a question card goes: the calendar's own card ([HOME]) or above a feature's screen. */
sealed interface AskPlace {
    data object Home : AskPlace
    /** A screen holding these registry features: only facts one of them needs are asked there. */
    data class Screen(val features: Set<String>) : AskPlace
}

/** [EASY-05] The question to show now. */
data class Ask(val missing: Missing, val reward: String, val isNew: Boolean)

/**
 * [EASY-05] One fact at a time, at the right moment: at most [PER_DAY] a day, the same card all day until it is
 * answered or put off, "나중에" for [SNOOZE_DAYS] days, and after [MAX_SNOOZES] of those only on the profile
 * summary. A good moment ([Moment]) puts its fact first: after the first night shift the 야간수당 question.
 */
object AskScheduler {
    const val PER_DAY = 2
    const val SNOOZE_DAYS = 7L
    const val MAX_SNOOZES = 2

    /** The fact each good moment makes worth asking about. */
    val MOMENTS: Map<Moment, FactKey> = mapOf(
        Moment.AFTER_NIGHT to FactKey.NIGHT_OT,
        Moment.PAYDAY to FactKey.BASE_PAY,
        Moment.HOURLY_15H to FactKey.HOLIDAY_PAY,
        Moment.PAST_END_TIME to FactKey.OVERTIME_CAP,
    )

    fun pick(state: AskState, missing: List<Missing>, place: AskPlace, today: LocalDate, moments: Set<Moment> = emptySet()): Ask? {
        fun open(m: Missing) = !state.parked(m.key) && !state.snoozed(m.key, today) &&
            (place !is AskPlace.Screen || m.features.any { it in place.features })
        val candidates = missing.filter(::open)
        if (state.day == today) {
            candidates.firstOrNull { it.key.id == state.current }?.let { return Ask(it, Needs.reward(it), isNew = false) }
        }
        if (state.countOn(today) >= PER_DAY) return null
        val boosted = moments.mapNotNull { MOMENTS[it] }.toSet()
        val next = candidates.firstOrNull { it.key in boosted } ?: candidates.firstOrNull() ?: return null
        return Ask(next, Needs.reward(next), isNew = true)
    }

    /** The state once [ask] is on screen; a card already counted today is not counted again. */
    fun shown(state: AskState, ask: Ask, today: LocalDate): AskState =
        if (!ask.isNew) state else state.copy(day = today, shownToday = state.countOn(today) + 1, current = ask.missing.key.id)

    /** Answered: off the card, and the next one may come today while [PER_DAY] allows. */
    fun answered(state: AskState, key: FactKey): AskState =
        state.copy(current = state.current.takeUnless { it == key.id }, snoozedUntil = state.snoozedUntil - key.id, snoozes = state.snoozes - key.id)

    /** "나중에": a week off, and counted toward being parked. */
    fun later(state: AskState, key: FactKey, today: LocalDate): AskState = state.copy(
        current = state.current.takeUnless { it == key.id },
        snoozedUntil = state.snoozedUntil + (key.id to today.plusDays(SNOOZE_DAYS)),
        snoozes = state.snoozes + (key.id to (state.snoozes[key.id] ?: 0) + 1),
    )

    /** [GUIDE-03] The one-line tip waits on a day a question was shown: the question comes first. */
    fun tipAllowed(state: AskState, today: LocalDate): Boolean = state.countOn(today) == 0
}
