package com.bamnat.core.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [QA-08] The one place the app asks what day and time it is (결정 D66). Code reads [date], [dateTime], [millis]…
 * here instead of `LocalDate.now()` or `System.currentTimeMillis()`, so a test can [fix] the clock and draw the
 * same screen on any day. On a phone it is the system clock, read afresh each time: a change of time zone on the
 * phone is followed at once. ClockRulesTest keeps direct calls out of the main code.
 */
object Today {
    @Volatile private var fixed: Clock? = null

    /** The system clock in the phone's time zone, or the clock a test fixed. */
    val clock: Clock get() = fixed ?: Clock.systemDefaultZone()

    fun zone(): ZoneId = clock.zone
    fun instant(): Instant = clock.instant()
    fun millis(): Long = clock.millis()
    fun date(): LocalDate = LocalDate.now(clock)
    fun dateTime(): LocalDateTime = LocalDateTime.now(clock)
    fun time(): LocalTime = LocalTime.now(clock)
    fun month(): YearMonth = YearMonth.now(clock)
    fun zoned(): ZonedDateTime = ZonedDateTime.now(clock)

    /** The date in [zone], for code that keeps its own zone (UTC file stamps, a watch's time zone). */
    fun date(zone: ZoneId): LocalDate = LocalDate.now(clock.withZone(zone))
    fun dateTime(zone: ZoneId): LocalDateTime = LocalDateTime.now(clock.withZone(zone))

    /** Tests: stop the clock at [at]. */
    fun fix(at: ZonedDateTime) { fixed = Clock.fixed(at.toInstant(), at.zone) }

    /** Tests: use [clock] as it is (a fixed or offset clock). */
    fun use(clock: Clock) { fixed = clock }

    /** Back to the system clock. */
    fun reset() { fixed = null }

    val isFixed: Boolean get() = fixed != null
}
