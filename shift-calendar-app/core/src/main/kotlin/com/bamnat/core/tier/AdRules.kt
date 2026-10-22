package com.bamnat.core.tier

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MON-07][REL-02] When an ad may show (결정 1: A+D). One banner under the 근무·급여 and 돈 tabs, never
 * on the calendar, memos, settings, alarms or the day screen; none in the first three days after
 * install; none for 프로; nothing before the 정식 출시 switch or without ad unit ids. A rewarded ad is
 * only ever the user's choice, once a day, and opens the pro features until midnight.
 */
object AdRules {
    const val QUIET_DAYS = 3

    enum class Place { CALENDAR, MONEY, STATS, MEMOS, SETTINGS, OTHER }

    private val bannerPlaces = setOf(Place.MONEY, Place.STATS)

    fun banner(place: Place, live: Boolean, pro: Boolean, configured: Boolean, installedAt: Instant?, now: Instant, quietDays: Int = QUIET_DAYS): Boolean =
        live && !pro && configured && place in bannerPlaces && quietOver(installedAt, now, quietDays)

    fun rewardedOffer(live: Boolean, pro: Boolean, configured: Boolean, lastDayPass: LocalDate?, today: LocalDate): Boolean =
        live && !pro && configured && lastDayPass != today

    /** A day opened by a rewarded ad lasts until the next midnight on the phone's clock. */
    fun dayPassUntil(today: LocalDate, zone: ZoneId): Instant = today.plusDays(1).atStartOfDay(zone).toInstant()

    private fun quietOver(installedAt: Instant?, now: Instant, quietDays: Int): Boolean =
        installedAt == null || !now.isBefore(installedAt.plus(Duration.ofDays(quietDays.coerceAtLeast(0).toLong())))
}
