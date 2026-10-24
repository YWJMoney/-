package com.bamnat.core

import com.bamnat.core.alarm.AlarmSuggest
import com.bamnat.core.alarm.AlarmSuggest.Kind
import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.sleep.SleepEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** [ALRM-03] 스마트 알람 제안: three signals per kind of shift, at most one card a week, nothing moved without a tap. */
class AlarmSuggestTest {
    private val today = LocalDate.of(2026, 10, 23)
    // 주간 09:00 with alarms 90 and 60 minutes before: the first one rings at 07:30.
    private val day = Fixtures.day.copy(alarmEnabled = true, alarms = listOf(AlarmSpec(60), AlarmSpec(90)))
    // 야간 22:00 with one alarm 2 hours before: 20:00.
    private val night = Fixtures.night.copy(alarmEnabled = true, alarms = listOf(AlarmSpec(120)))
    private val types = listOf(day, night, Fixtures.off)
    private val dayDates = (1..5).map { today.minusDays(it * 2L) }
    private val nightDates = (1..5).map { today.minusDays(it * 2L - 1) }
    private val days = dayDates.map { it to day } + nightDates.map { it to night }
    private val none = AlarmSuggest.Memory()

    private fun dayDue(d: LocalDate) = d.atTime(7, 30)
    private fun nightDue(d: LocalDate) = d.atTime(20, 0)
    private fun offs(late: List<Long>, dates: List<LocalDate> = dayDates, due: (LocalDate) -> LocalDateTime = ::dayDue, offset: Int = 90, id: Long = day.id) =
        dates.zip(late).map { (d, m) -> AlarmSuggest.Off(id, due(d), offset, due(d).plusMinutes(m)) }
    /** Sleeps that end [early] minutes before each due and last [minutes]. */
    private fun sleeps(early: List<Long>, minutes: List<Long>, dates: List<LocalDate> = dayDates, due: (LocalDate) -> LocalDateTime = ::dayDue) =
        dates.indices.map { i -> val end = due(dates[i]).minusMinutes(early[i]); SleepEntry(i.toLong(), end.minusMinutes(minutes[i]), end) }
    private fun find(offs: List<AlarmSuggest.Off> = emptyList(), sleeps: List<SleepEntry> = emptyList(), memory: AlarmSuggest.Memory = none,
        bedtimeOn: Boolean = false) = AlarmSuggest.find(today, types, days, offs, sleeps, 7 * 60, bedtimeOn, memory)

    @Test fun `turned off late three times in five moves that shift's alarms 20 minutes earlier`() {
        val s = find(offs(listOf(25, 30, 5, 40, 0)))!!
        assertEquals(Kind.LATE, s.kind)
        assertEquals(day.id, s.type.id)
        assertEquals(listOf(80, 110), s.moved!!.alarms.map { it.offsetMinutes })
        assertEquals("주간 알람을 보통 30분 뒤에 꺼요. 알람을 20분 앞당길까요?", s.text)
    }

    @Test fun `two late in five, or fewer than five, says nothing`() {
        assertNull(find(offs(listOf(25, 30, 5, 10, 0))))
        assertNull(find(offs(listOf(25, 30, 40, 50))))
        // The second alarm (60 minutes before) is not the first one.
        assertNull(find(offs(listOf(25, 30, 35, 40, 45), offset = 60)))
    }

    @Test fun `each kind of shift is counted on its own`() {
        val mixed = offs(listOf(25, 30, 0, 0, 0)) + offs(listOf(30, 0, 0), nightDates.take(3), ::nightDue, 120, night.id)
        assertNull(find(mixed))
        val s = find(offs(listOf(25, 30, 35, 0, 0), nightDates, ::nightDue, 120, night.id))!!
        assertEquals(night.id, s.type.id)
        assertEquals(140, s.moved!!.alarms.single().offsetMinutes)
    }

    @Test fun `awake well before the alarm moves it 15 minutes later`() {
        val s = find(sleeps = sleeps(listOf(40, 45, 50, 0, 10), List(5) { 420L }))!!
        assertEquals(Kind.EARLY, s.kind)
        assertEquals(listOf(45, 75), s.moved!!.alarms.map { it.offsetMinutes })
        assertTrue(s.text, s.text.startsWith("주간 전에는 알람보다 45분쯤 먼저 깨요"))
        assertNull(find(sleeps = sleeps(listOf(40, 45, 10, 0, 10), List(5) { 420L })))
    }

    @Test fun `short sleep before a shift offers the bedtime notice, not when it is on`() {
        val s = find(sleeps = sleeps(List(5) { 0L }, listOf(300, 300, 280, 420, 400)))!!
        assertEquals(Kind.SHORT, s.kind)
        assertTrue(s.bedtime)
        assertNull(s.moved)
        assertEquals("주간 전 잠이 목표보다 보통 2시간 짧아요. 잘 시간 30분 전에 알려 드릴까요?", s.text)
        assertNull(find(sleeps = sleeps(List(5) { 0L }, listOf(300, 300, 280, 420, 400)), bedtimeOn = true))
    }

    @Test fun `a night worker's sleep is the nap before the alarm, not the one by the wake date`() {
        // Slept 09:00–13:00 before each 20:00 alarm: four hours, three under 7h−90m.
        val naps = nightDates.map { d -> SleepEntry(0, d.atTime(9, 0), d.atTime(13, 0)) }
        assertEquals(LocalDateTime.of(nightDates[0], java.time.LocalTime.of(13, 0)), AlarmSuggest.sleepBefore(nightDue(nightDates[0]), naps)!!.end)
        val s = find(sleeps = naps)!!
        assertEquals(night.id, s.type.id)
        // Also woke 7 hours early: early comes before short.
        assertEquals(Kind.EARLY, s.kind)
        // A sleep left open for 20 hours is a missed tap and does not count.
        assertNull(AlarmSuggest.sleepBefore(nightDue(today), listOf(SleepEntry(0, today.minusDays(1).atTime(0, 0), today.atTime(19, 0)))))
    }

    @Test fun `a card a week, the same one rests 30 days, and never means never`() {
        val late = offs(listOf(25, 30, 35, 0, 0))
        val key = AlarmSuggest.key(day.id, Kind.LATE)
        val yesterday = none.answered("other", today.minusDays(1))
        assertNull(find(late, memory = yesterday))
        val weekAgo = none.answered(key, today.minusDays(8))
        assertNull(find(late, memory = weekAgo))
        assertNotNull(find(late, memory = none.answered(key, today.minusDays(31))))
        val never = none.answered(key, today.minusDays(400), forGood = true)
        assertNull(find(late, memory = never))
        assertEquals(never, AlarmSuggest.Memory.decode(never.encode()))
        assertEquals(none, AlarmSuggest.Memory.decode(""))
    }

    @Test fun `undoing puts the shift back as it was`() {
        val s = find(offs(listOf(25, 30, 35, 0, 0)))!!
        // The app saves s.moved, and on 되돌리기 saves s.type: both whole shifts.
        assertEquals(day, s.type)
        assertEquals(day.copy(alarms = listOf(AlarmSpec(80), AlarmSpec(110))), s.moved)
    }

    @Test fun `dismissals are read back from the log, old lines left out`() {
        val due = LocalDateTime.of(2026, 10, 20, 7, 30)
        val dueMillis = due.toInstant(ZoneOffset.UTC).toEpochMilli()
        val events = listOf(
            DiagEvent(dueMillis + 25 * 60_000, DiagEvents.ALARM_DISMISSED, "mission " + AlarmSuggest.offDetail(1, dueMillis, 90)),
            DiagEvent(dueMillis, DiagEvents.ALARM_DISMISSED, "auto_stop"),
            DiagEvent(dueMillis, DiagEvents.ALARM_FIRED, AlarmSuggest.offDetail(1, dueMillis, 90)),
        )
        val offs = AlarmSuggest.offsOf(events, ZoneOffset.UTC)
        assertEquals(listOf(AlarmSuggest.Off(1, due, 90, due.plusMinutes(25))), offs)
    }
}
