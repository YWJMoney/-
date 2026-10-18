package com.bamnat.core

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepInsights
import com.bamnat.core.sleep.SleepInsights.Context
import com.bamnat.core.sleep.TodayFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [SLP-16] The sleep log read against the work around it: averages per kind of day and plain findings. */
class SleepInsightsTest {
    private val day = ShiftType(11, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60)
    private val night = ShiftType(12, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60)
    private val off = ShiftType(13, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)

    /** 11/2 주간, 11/3–4 야간, 11/5–6 휴무, 11/7 주간, 11/8–9 야간, 11/10–11 휴무, 11/12 주간. */
    private val resolver = ScheduleResolver(listOf(day, night, off),
        WorkPattern.Rotation(LocalDate.of(2026, 11, 2), listOf(day.id, night.id, night.id, off.id, off.id)), emptyList())

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)
    private var nextId = 1L
    private fun sleep(from: LocalDateTime, to: LocalDateTime?) = SleepEntry(nextId++, from, to)

    private val log = listOf(
        sleep(at(4, 8), at(4, 12, 30)),        // after the night, 4h30
        sleep(at(5, 8), at(5, 13)),            // after the night, 5h
        sleep(at(5, 23), at(6, 9, 30)),        // day off, 10h30
        sleep(at(6, 23), at(7, 5)),            // before the 07:00 day shift, 6h
        sleep(at(9, 8, 30), at(9, 13)),        // after the night, 4h30
        sleep(at(10, 22, 30), at(11, 9)),      // day off, 10h30
        sleep(at(11, 14), at(11, 14, 50)),     // a nap
        sleep(at(12, 1), null),                // still open: not counted
    )
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun eachSleepGetsTheWorkAroundIt() {
        val nights = SleepInsights.nights(log, resolver, today)
        assertEquals(7, nights.size)
        assertEquals(
            listOf(Context.DAY_OFF, Context.DAY_OFF, Context.AFTER_NIGHT, Context.BEFORE_EARLY, Context.DAY_OFF, Context.AFTER_NIGHT, Context.AFTER_NIGHT),
            nights.map { it.context },
        )
        assertTrue(nights.first().nap)
    }

    @Test
    fun theReportAveragesPerKindAndSaysWhatStandsOut() {
        val r = SleepInsights.report(log, resolver, today, targetMinutes = 420)
        val afterNight = r.group(Context.AFTER_NIGHT)!!
        assertEquals(3, afterNight.count)
        assertEquals(280, afterNight.averageMinutes)
        assertEquals(8 * 60 + 10, afterNight.averageBedtime)
        assertEquals(630, r.group(Context.DAY_OFF)!!.averageMinutes)
        assertEquals(1, r.group(Context.BEFORE_EARLY)!!.count)
        assertNull(r.group(Context.WORK_DAY))
        assertEquals(1, r.naps)
        assertEquals(6, r.loggedDays)
        assertEquals(listOf("after-night", "catch-up"), r.findings.map { it.key })
        assertTrue(r.findings[0].text, r.findings[0].text.startsWith("야간 뒤에는 평균 4시간 40분 잤어요. 목표보다 2시간 20분 적어요."))
        assertTrue(r.findings[1].text, r.findings[1].text.startsWith("쉬는 날은 근무일보다 평균 5시간 50분 더 잤어요."))
        assertEquals(280L, SleepInsights.afterNightAverage(log, resolver, today))
    }

    @Test
    fun aFewLogsAskForMoreAndNothingIsClaimed() {
        val r = SleepInsights.report(log.take(1), resolver, LocalDate.of(2026, 11, 4), targetMinutes = 420)
        assertEquals(listOf("more-logs"), r.findings.map { it.key })
        assertNull(SleepInsights.afterNightAverage(log.take(1), resolver, LocalDate.of(2026, 11, 4)))
        assertTrue(SleepInsights.report(emptyList(), resolver, today, 420).findings.isEmpty())
    }

    @Test
    fun clockTimesAverageAroundMidnight() {
        assertEquals(0, SleepInsights.averageClock(listOf(at(1, 23, 30), at(2, 0, 30))))
        assertEquals(23 * 60, SleepInsights.averageClock(listOf(at(1, 22, 0), at(2, 0, 0))))
        assertNull(SleepInsights.spread(listOf(at(1, 22), at(2, 23))))
        assertEquals(60L, SleepInsights.spread(listOf(at(1, 22), at(2, 0), at(3, 22), at(4, 0))))
        assertEquals("08:10", SleepInsights.clock(490))
    }

    @Test
    fun theWayHomeCardUsesTheUsersOwnAverage() {
        val card = TodayFlow.of(at(4, 7, 20), resolver, alarmsOn = true, afterNightMinutes = 280)
        assertEquals(TodayFlow.State.AFTER_SHIFT, card.state)
        assertEquals("지난 야간 뒤엔 평균 4시간 40분 잤어요 · 오늘은 15:00까지 누워 있어 봐요", card.detail)
        val enough = TodayFlow.of(at(4, 7, 20), resolver, alarmsOn = true, afterNightMinutes = 400)
        assertEquals("지난 야간 뒤엔 평균 6시간 40분 잤어요", enough.detail)
    }
}
