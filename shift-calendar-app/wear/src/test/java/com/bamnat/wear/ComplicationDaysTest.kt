package com.bamnat.wear

import com.bamnat.core.work.WatchDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ComplicationDaysTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 21)

    private fun day(offset: Long) = when (offset % 3) {
        0L -> WatchDay(today.plusDays(offset), "주간", "주", 0xFF2F80ED, LocalTime.of(9, 0), LocalTime.of(18, 0))
        1L -> WatchDay(today.plusDays(offset), "야간", "야", 0xFF000000, LocalTime.of(22, 0), LocalTime.of(7, 0))
        else -> WatchDay(today.plusDays(offset), "휴무", "휴", 0xFF999999, off = true)
    }

    private val days = (0L..4L).map(::day)

    @Test
    fun todayIsTodaysShift() {
        val d = ComplicationDays.today(days, today, zone)
        assertEquals(today.atStartOfDay(zone).toInstant(), d.start)
        assertEquals("주", d.shortText)
        assertEquals("오늘", d.shortTitle)
        assertEquals("주간 09:00–18:00", d.longText)
        assertEquals("내일 야간 22:00–07:00", d.longTitle)
        assertEquals("오늘 주간 09:00–18:00", d.description)
    }

    @Test
    fun entriesStartAtEachLocalMidnight() {
        val ahead = ComplicationDays.ahead(days, today, zone)
        // Four stored days after today, then one "no data" tail.
        assertEquals(5, ahead.size)
        ahead.dropLast(1).forEachIndexed { i, d ->
            val date = today.plusDays(i + 1L)
            assertEquals(date.atStartOfDay(zone).toInstant(), d.start)
            assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(), d.end)
            assertEquals(date, d.day?.date)
        }
        assertEquals(listOf("야", "휴", "주", "야"), ahead.dropLast(1).map { it.shortText })
        assertEquals("내일 휴무", ahead[0].longTitle)
        assertEquals("휴무", ahead[1].longText)
        // The last stored day has no tomorrow.
        assertEquals("오늘 근무", ahead[3].longTitle)
    }

    @Test
    fun pastTheLastDayItSaysNoData() {
        val tail = ComplicationDays.ahead(days, today, zone).last()
        assertEquals(today.plusDays(5).atStartOfDay(zone).toInstant(), tail.start)
        assertTrue(tail.end.isAfter(tail.start.plusSeconds(300L * 86_400)))
        assertNull(tail.day)
        assertEquals("-", tail.shortText)
        assertEquals(WatchText.NO_DATA, tail.longText)
    }

    @Test
    fun midnightFollowsDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 7)
        val ahead = ComplicationDays.ahead((0L..2L).map { WatchDay(start.plusDays(it), "주간", "주", 0) }, start, ny)
        // 8 March 2026 is 23 hours long there.
        assertEquals(23L * 3600, ahead[0].end.epochSecond - ahead[0].start.epochSecond)
        assertEquals(start.plusDays(2).atStartOfDay(ny).toInstant(), ahead[1].start)
    }

    @Test
    fun capsHowFarAhead() {
        val many = (0L..60L).map(::day)
        val ahead = ComplicationDays.ahead(many, today, zone)
        assertEquals(ComplicationDays.AHEAD + 1, ahead.size)
        assertEquals(today.plusDays(ComplicationDays.AHEAD + 1L).atStartOfDay(zone).toInstant(), ahead.last().start)
    }

    @Test
    fun noStoredDaysNoEntriesButTheTail() {
        assertEquals(listOf(null), ComplicationDays.ahead(listOf(day(-3)), today, zone).map { it.day })
        assertEquals(emptyList<ComplicationDay>(), ComplicationDays.ahead(emptyList(), today, zone))
        val none = ComplicationDays.today(null, today, zone)
        assertEquals("밤낮", none.shortTitle)
        assertEquals("오늘 근무", none.longTitle)
    }
}
