package com.bamnat.core

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.notify.DayNotices
import com.bamnat.core.notify.DayNotices.Kind
import com.bamnat.core.notify.QuietHours
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-15] The day's notices on 4조2교대 (주간 07–19, 야간 19–07, alarms 90 minutes before): a silent
 * briefing for each work that needs planning, a sound only when a night ends, and money waiting while
 * the user sleeps.
 */
class DayNoticesTest {
    private val day = ShiftType(11, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    private val night = ShiftType(12, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    private val off = ShiftType(13, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val evening = ShiftType(14, "오후", "오", 0xFFFB8C00, LocalTime.of(14, 0), LocalTime.of(22, 0), breakMinutes = 30)
    private val early = ShiftType(15, "새벽", "새", 0xFF00897B, LocalTime.of(6, 0), LocalTime.of(14, 0), breakMinutes = 30,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    private val types = listOf(day, night, off, evening, early)
    private val start = LocalDate.of(2026, 11, 2)

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    /** 주주휴휴야야휴휴 from 11/2: 주간 11/2–3, 야간 11/6–7, then 주간 again on 11/10. */
    private fun fourTeam(vararg entries: DayEntry) = ScheduleResolver(types,
        WorkPattern.Rotation(start, listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id)), entries.toList())

    private fun plan(r: ScheduleResolver, now: LocalDateTime, wanted: DayNotices.Wanted = DayNotices.Wanted()) =
        DayNotices.plan(now, r, alarmsOn = true, wanted = wanted)

    @Test
    fun `one silent briefing per work and a sound only when a night ends`() {
        val notices = plan(fourTeam(), at(1, 12)).filter { !it.at.isBefore(at(1, 12)) }
        // 11/1 (휴무 before the first 주간), seen from noon: a briefing for 11/2's 주간 at the coffee cut-off.
        val first = notices.first()
        assertEquals(Kind.BRIEFING, first.kind)
        assertEquals(at(1, 16, 30), first.at)
        assertEquals(at(1, 22, 30), first.until)
        assertEquals("내일 07:00 주간이에요", first.title)
        assertEquals("커피는 지금까지 · 22:30에 누우면 7시간 · 알람 05:30", first.text)
        // During the first 주간 the cut-off for the second comes at work: it waits for 19:00, and coffee is past.
        val second = notices[1]
        assertEquals(at(2, 19), second.at)
        assertEquals("내일 07:00 주간이에요", second.title)
        assertEquals("22:30에 누우면 7시간 · 알람 05:30", second.text)
        assertEquals(2, notices.size)

        val nights = plan(fourTeam(), at(6, 0)).filter { !it.at.isBefore(at(6, 0)) }
        val firstNight = nights[0]
        assertEquals(Kind.BRIEFING, firstNight.kind)
        assertEquals(at(6, 14, 30), firstNight.at)
        assertEquals(at(6, 19), firstNight.until)
        assertEquals("오늘 야간 19:00–07:00", firstNight.title)
        assertEquals("15:00–16:30 낮잠 · 커피는 02:00까지 · 퇴근 후 08:00–15:00 잘 시간", firstNight.text)
        val home = nights[1]
        assertEquals(Kind.COMMUTE, home.kind)
        assertEquals(at(7, 7), home.at)
        assertEquals(at(7, 9), home.until)
        assertEquals("야간 수고했어요", home.title)
        assertEquals("졸리면 운전 전에 쉬어 가요 · 잘 시간 08:00–15:00", home.text)
        // The second night: 3 hours before, after the sleep that follows the first.
        val secondNight = nights[2]
        assertEquals(Kind.BRIEFING, secondNight.kind)
        assertEquals(at(7, 16), secondNight.at)
        assertEquals("커피는 02:00까지 · 퇴근 후 08:00–15:00 잘 시간", secondNight.text)
        assertEquals(Kind.COMMUTE, nights[3].kind)
        assertEquals(at(8, 7), nights[3].at)
    }

    @Test
    fun `a whole cycle makes a sound twice`() {
        // 주주야야휴 over two weeks, as in the plan's check.
        val r = ScheduleResolver(types, WorkPattern.Rotation(start, listOf(day.id, day.id, night.id, night.id, off.id)), emptyList())
        val all = (0L until 10L).flatMap { d ->
            val now = start.plusDays(d).atStartOfDay()
            plan(r, now).filter { !it.at.isBefore(now) && it.at.isBefore(now.plusDays(1)) }
        }
        val perCycle = all.groupBy { (it.at.toLocalDate().toEpochDay() - start.toEpochDay()) / 5 }
        for ((_, cycle) in perCycle) {
            assertEquals(2, cycle.count { it.kind == Kind.COMMUTE })
            assertTrue(cycle.all { it.kind == Kind.COMMUTE || it.kind == Kind.BRIEFING })
        }
        // Without the briefing and the commute nothing is planned; bed time only when asked for.
        assertTrue(plan(r, at(2, 0), DayNotices.Wanted(briefing = false, commute = false)).isEmpty())
        val bed = plan(r, at(2, 0), DayNotices.Wanted(briefing = false, commute = false, bedtime = true)).first { !it.at.isBefore(at(2, 0)) }
        assertEquals(Kind.BEDTIME, bed.kind)
        assertEquals(at(2, 22), bed.at)
        assertEquals("22:30 잘 시간이에요", bed.title)
        assertEquals("내일 07:00 주간 · 알람 05:30 켜져 있어요", bed.text)
    }

    @Test
    fun `a short rest says how much sleep fits`() {
        // 오후 14–22 on 11/2, then 새벽 06–14 on 11/3: home at 23:00, the alarm at 04:30.
        val r = fourTeam(
            DayEntry(LocalDate.of(2026, 11, 2), override = ShiftOverride.Shift(evening.id)),
            DayEntry(LocalDate.of(2026, 11, 3), override = ShiftOverride.Shift(early.id)),
        )
        val brief = plan(r, at(2, 12)).first { it.key.startsWith("brief-2026-11-03") }
        assertEquals(at(2, 22), brief.at)
        assertEquals("내일 06:00 새벽 · 쉴 틈 8시간", brief.title)
        assertEquals("잘 수 있는 시간 23:00–04:30 · 알람 04:30", brief.text)
    }

    @Test
    fun `no drive home between back to back work`() {
        // The first night runs straight into a 주간 cover at 07:00.
        val r = fourTeam(DayEntry(LocalDate.of(2026, 11, 7), override = ShiftOverride.Shift(day.id)))
        val notices = plan(r, at(6, 0))
        assertTrue(notices.none { it.kind == Kind.COMMUTE && it.at == at(7, 7) })
    }

    @Test
    fun `late notices are dropped or kept by what they are for`() {
        val notices = plan(fourTeam(), at(6, 0))
        // Ten minutes late the commute notice still comes; eleven, and the drive has started.
        assertEquals(listOf(Kind.COMMUTE), DayNotices.due(notices, at(7, 7), at(7, 7, 10)).map { it.kind })
        assertTrue(DayNotices.due(notices, at(7, 7), at(7, 7, 11)).isEmpty())
        // A briefing late by hours still says the day ahead, until the work starts.
        assertEquals(1, DayNotices.due(notices, at(6, 14, 30), at(6, 18)).size)
        assertTrue(DayNotices.due(notices, at(6, 14, 30), at(6, 19)).isEmpty())
    }

    @Test
    fun `money waits while the user sleeps`() {
        val r = fourTeam()
        val sleep = SleepSchedule.of(r, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 8), alarmsOn = true)
        val bill = at(7, 9)
        // Planned sleep 08:00–15:00 after the night: the 09:00 bill comes at 15:30.
        assertEquals(at(7, 15, 30), QuietHours.holdUntil(bill, bill, sleep, emptyList(), 420))
        // Not asleep at 16:00 by the plan: it comes on time.
        assertNull(QuietHours.holdUntil(at(7, 16), at(7, 16), sleep, emptyList(), 420))
        // Up at 12:00 by the log: the held bill comes at 12:30, and a 13:00 one on time.
        val woke = listOf(SleepEntry(1, at(7, 8, 10), at(7, 12)))
        assertEquals(at(7, 12, 30), QuietHours.holdUntil(bill, at(7, 12), sleep, woke, 420))
        assertNull(QuietHours.holdUntil(at(7, 13), at(7, 13), sleep, woke, 420))
        // Still asleep by the log: until the plan's wake, and no longer once that has passed.
        val open = listOf(SleepEntry(1, at(7, 8, 10), null))
        assertEquals(at(7, 15, 30), QuietHours.holdUntil(bill, bill, sleep, open, 420))
        assertNull(QuietHours.holdUntil(bill, at(7, 15, 31), sleep, open, 420))
        // A day off with no plan and no log: nothing waits.
        assertNull(QuietHours.holdUntil(bill, bill, null, emptyList(), 420))
    }
}
