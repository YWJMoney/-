package com.bamnat.core

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepPlan
import com.bamnat.core.sleep.SleepSchedule
import com.bamnat.core.sleep.TodayFlow
import com.bamnat.core.sleep.TodayFlow.Action
import com.bamnat.core.sleep.TodayFlow.Part
import com.bamnat.core.sleep.TodayFlow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-14] 오늘의 흐름 in the eight cases of the plan, on 주간 07–19 · 야간 19–07 · 휴무 · 휴무 with
 * alarms 90 minutes before: the first night's afternoon, during the night, right after it, asleep,
 * after waking, an alarm turned off, a short rest and a day off. Then a 24-hour duty and a 대근.
 */
class TodayFlowTest {
    private val day = ShiftType(11, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    private val night = ShiftType(12, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    private val off = ShiftType(13, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val types = listOf(day, night, off)
    private val start = LocalDate.of(2026, 11, 2)

    /** 11/2 주간, 11/3–11/4 야간, 11/5–11/6 휴무, 11/7 주간 … */
    private fun resolver(vararg entries: DayEntry) =
        ScheduleResolver(types, WorkPattern.Rotation(start, listOf(day.id, night.id, night.id, off.id, off.id)), entries.toList())

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    private fun flow(now: LocalDateTime, r: ScheduleResolver = resolver(), log: List<SleepEntry> = emptyList(), alarmsOn: Boolean = true) =
        TodayFlow.of(now, r, alarmsOn, log)

    @Test
    fun `first night afternoon is the nap`() {
        val card = flow(at(3, 14, 30))
        assertEquals(State.NAP, card.state)
        assertEquals("오늘 · 야간 19:00–07:00", card.caption)
        assertEquals("15:00 낮잠 90분", card.headline)
        assertEquals("첫 야간 전 낮잠 시간이에요 · 16:30엔 일어나요", card.detail)
        assertNull(card.action)
        assertEquals("낮잠 15:00–16:30", card.line)
        // The strip: nap, the alarm, the night, and the sleep after it up to the strip's end.
        assertEquals(listOf(Part.NAP, Part.WORK, Part.SLEEP), card.segments.map { it.part })
        assertEquals(at(3, 15) to at(3, 16, 30), card.segments[0].from to card.segments[0].to)
        assertEquals(at(3, 19) to at(4, 7), card.segments[1].from to card.segments[1].to)
        assertEquals(night.colorArgb, card.segments[1].colorArgb)
        assertEquals(at(4, 8) to at(4, 14, 30), card.segments[2].from to card.segments[2].to)
        assertEquals(listOf(at(3, 17, 30)), card.alarms)
        assertEquals(at(3, 16, 30), card.changesAt)
        // Before the nap's hour, the card is about the night ahead.
        val morning = flow(at(3, 10))
        assertEquals(State.NEXT, morning.state)
        assertEquals("19:00 야간 · 알람 17:30", morning.headline)
        assertEquals("15:00 낮잠 90분 · 16:30엔 일어나요", morning.detail)
        // After the nap and the alarm, the night is next, and the card looks past it to the sleep.
        val leaving = flow(at(3, 18))
        assertEquals(State.NEXT, leaving.state)
        assertEquals("19:00 야간", leaving.headline)
        assertEquals("퇴근 뒤 잘 시간 08:00–15:00 · 커피는 02:00까지", leaving.detail)
        assertEquals("잘 시간 08:00–15:00", leaving.line)
    }

    @Test
    fun `during the night it says how long coffee is fine`() {
        val early = flow(at(4, 1))
        assertEquals(State.ON_SHIFT, early.state)
        assertEquals("지금 · 야간 19:00–07:00", early.caption)
        assertEquals("02:00까지만 커피", early.headline)
        assertEquals("08:00에 잠들 수 있게요", early.detail)
        assertEquals("커피는 02:00까지", early.line)
        val late = flow(at(4, 3))
        assertEquals("퇴근하면 08:00–15:00 잘 시간", late.headline)
        assertEquals("잠들기 6시간 안이라 커피는 쉬어 가요", late.detail)
    }

    @Test
    fun `right after the night it is time to sleep`() {
        val card = flow(at(4, 7, 20))
        assertEquals(State.AFTER_SHIFT, card.state)
        assertEquals("08:00–15:00 잘 시간", card.headline)
        assertEquals("수고했어요. 졸리면 운전 전에 쉬어 가요", card.detail)
        assertEquals(Action.ASLEEP, card.action)
        assertEquals("잘 시간 08:00–15:00", card.line)
        // Once home and still up, the same sleep with the next work under it.
        val home = flow(at(4, 9))
        assertEquals(State.SLEEP_TIME, home.state)
        assertEquals("08:00–15:00 잘 시간", home.headline)
        assertEquals("19:00 야간 · 알람 17:30", home.detail)
    }

    @Test
    fun `asleep it shows the next alarm and the wake button`() {
        val card = flow(at(4, 11), log = listOf(SleepEntry(1, at(4, 8, 12), null)))
        assertEquals(State.SLEEPING, card.state)
        assertEquals("08:12부터 자는 중", card.headline)
        assertEquals("다음 알람 17:30", card.detail)
        assertEquals(Action.AWAKE, card.action)
        // Left open for more than 16 hours: ask when the user woke.
        val forgot = flow(at(5, 1), log = listOf(SleepEntry(1, at(4, 8, 12), null)))
        assertEquals(State.FORGOT_WAKE, forgot.state)
        assertEquals(Action.AWAKE_AT, forgot.action)
        assertEquals("어제 08:12부터 자는 중으로 남아 있어요", forgot.detail)
        // At work since: the user is up, whatever the record says.
        val atWork = flow(at(4, 20), log = listOf(SleepEntry(1, at(4, 8, 12), null)))
        assertEquals(State.FORGOT_WAKE, atWork.state)
        assertEquals("깼을 때 누르는 걸 잊었나요?", atWork.headline)
    }

    @Test
    fun `after waking it says how long the sleep was against the target`() {
        // Up at 14:22, still inside the planned sleep: that sleep is done, so neither the card nor the strip offers it.
        val card = flow(at(4, 14, 40), log = listOf(SleepEntry(1, at(4, 8, 12), at(4, 14, 22))))
        assertEquals(State.AWAKE, card.state)
        assertEquals("17:30 알람 · 19:00 야간", card.headline)
        assertEquals("6시간 10분 잤어요 (목표보다 50분 적어요)", card.detail)
        assertTrue(card.segments.none { it.part == Part.SLEEP && it.from.isBefore(at(4, 19)) })
    }

    @Test
    fun `an alarm turned off for the next work comes first`() {
        val skipped = resolver(DayEntry(LocalDate.of(2026, 11, 7), alarmSkipped = true))
        val card = flow(at(6, 10), skipped)
        assertEquals(State.ALARM_OFF, card.state)
        assertEquals("내일 07:00 주간인데 알람이 꺼져 있어요", card.headline)
        assertEquals(Action.ALARM_ON, card.action)
        assertEquals(LocalDate.of(2026, 11, 7), card.actionDate)
        // With every alarm switched off by the user, the line says so without taking over the card.
        val allOff = flow(at(6, 10), alarmsOn = false)
        assertEquals(State.REST_DAY, allOff.state)
        assertEquals("오늘은 쉬는 날 · 내일 07:00 주간, 알람 꺼 둠", allOff.headline)
    }

    @Test
    fun `a short rest gives the whole gap and says why`() {
        val evening = ShiftType(21, "오후", "오", 0xFFFB8C00, LocalTime.of(14, 0), LocalTime.of(22, 0), breakMinutes = 30)
        val early = ShiftType(22, "새벽", "새", 0xFF00897B, LocalTime.of(6, 0), LocalTime.of(15, 0), breakMinutes = 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
        val r = ScheduleResolver(listOf(evening, early), WorkPattern.Rotation(start, listOf(evening.id, early.id)), emptyList())
        val card = TodayFlow.of(at(2, 22, 30), r, alarmsOn = true)
        assertEquals(State.SLEEP_TIME, card.state)
        assertEquals("23:00–04:30 잘 수 있어요", card.headline)
        assertEquals("오늘은 쉴 틈이 8시간뿐이에요", card.detail)
        // The rest sign would say the same, so it is left out.
        assertNull(card.rest)
        // Earlier in the shift, the short night is the small line.
        val during = TodayFlow.of(at(2, 16), r, alarmsOn = true)
        assertEquals("근무 중 · 22:00까지", during.headline)
        assertEquals("퇴근 뒤 잘 시간 23:00–04:30 · 오늘은 쉴 틈이 8시간뿐이에요", during.detail)
    }

    @Test
    fun `a day off is one line with the next work`() {
        val card = flow(at(6, 10))
        assertEquals(State.REST_DAY, card.state)
        assertTrue(card.compact)
        assertEquals("오늘은 쉬는 날 · 내일 07:00 주간, 알람 05:30", card.headline)
        assertNull(card.line)
        // In the evening the night before the early start takes over.
        val evening = flow(at(6, 21, 45))
        assertEquals(State.SLEEP_TIME, evening.state)
        assertEquals("22:30–05:30 잘 시간", evening.headline)
        assertEquals("내일 07:00 주간 · 알람 05:30", evening.detail)
    }

    @Test
    fun `a 24-hour duty is followed by sleep and the drowsy-driving line`() {
        val duty = ShiftType(31, "당번", "당", 0xFF00897B, LocalTime.of(9, 0), LocalTime.of(9, 0), breakMinutes = 180)
        val r = ScheduleResolver(listOf(duty, off), WorkPattern.Rotation(start, listOf(duty.id, off.id, off.id)), emptyList())
        val card = TodayFlow.of(at(3, 9, 20), r, alarmsOn = true)
        assertEquals(State.AFTER_SHIFT, card.state)
        assertEquals("10:00–17:00 잘 시간", card.headline)
        assertEquals("수고했어요. 졸리면 운전 전에 쉬어 가요", card.detail)
        val tips = SleepPlan.tips(SleepSchedule.of(r, start, start, alarmsOn = true), start)
        assertTrue(tips.toString(), tips.any { it.kind == SleepPlan.Kind.DROWSY && it.at == at(3, 9) })
        assertTrue(tips.toString(), tips.any { it.kind == SleepPlan.Kind.SLEEP_AFTER_NIGHT && it.at == at(3, 10) })
    }

    @Test
    fun `a night cover on a day off plans like a night, with the covered shift's alarm`() {
        val cover = DayEntry(LocalDate.of(2026, 11, 5), extras = listOf(
            ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(19, 0), LocalTime.of(7, 0), 60, coveredShiftId = night.id)))
        // 11/4's night ended this morning, so this one carries on the run: no nap, the sleep after it as for any night.
        val card = flow(at(5, 16), resolver(cover))
        assertEquals("오늘 · 야간 대근 19:00–07:00", card.caption)
        assertEquals(State.NEXT, card.state)
        assertEquals("19:00 야간 대근 · 알람 17:30", card.headline)
        val next = flow(at(6, 7, 30), resolver(cover))
        assertEquals(State.AFTER_SHIFT, next.state)
        assertEquals("08:00–15:00 잘 시간", next.headline)
    }

    @Test
    fun `the day view reads the real alarm for the night before an early start`() {
        // Fixtures.day starts at 09:00 with its alarm an hour before: up at 08:00, so in bed at 01:00.
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(start, listOf(Fixtures.off.id, Fixtures.day.id)), emptyList())
        val withAlarm = SleepPlan.tips(SleepSchedule.of(r, start, start, alarmsOn = true), start)
        assertEquals(at(3, 1), withAlarm.single { it.kind == SleepPlan.Kind.BEDTIME }.at)
        assertEquals(at(3, 8), withAlarm.single { it.kind == SleepPlan.Kind.BEDTIME }.until)
        // Without alarms it falls back to 90 minutes before the start.
        val without = SleepPlan.tips(SleepSchedule.of(r, start, start, alarmsOn = false), start)
        assertEquals(at(3, 0, 30), without.single { it.kind == SleepPlan.Kind.BEDTIME }.at)
    }
}
