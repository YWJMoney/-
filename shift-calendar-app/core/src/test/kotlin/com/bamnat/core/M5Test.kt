package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** M5: sleep times that fit the schedule, the sleep log and strict rest rules. */
class M5Test {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun days(steps: List<Long>): List<com.bamnat.core.schedule.ResolvedDay> {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, steps), emptyList())
        return (0L until 14L).map { r.resolve(monday.plusDays(it)) }
    }
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test
    fun `first night gets a nap, every night a sleep after it, a drowsy reminder and a caffeine cut-off`() {
        val n = Fixtures.night.id; val o = Fixtures.off.id; val d = Fixtures.day.id
        val all = days(listOf(o, n, n, o, o, d, d))
        // 11/3 is the first 야간 (22:00–07:00).
        val first = SleepPlan.forDay(all[0], all[1], all[2])
        assertEquals(listOf(SleepPlan.Kind.NAP, SleepPlan.Kind.CAFFEINE, SleepPlan.Kind.DROWSY, SleepPlan.Kind.SLEEP_AFTER_NIGHT), first.map { it.kind })
        assertEquals(at(3, 18), first[0].at)
        assertEquals(at(3, 19, 30), first[0].until)
        assertEquals(at(4, 7), first.single { it.kind == SleepPlan.Kind.DROWSY }.at)
        val sleep = first.single { it.kind == SleepPlan.Kind.SLEEP_AFTER_NIGHT }
        assertEquals(at(4, 8), sleep.at)
        assertEquals(at(4, 15), sleep.until)
        assertEquals(SleepPlan.Basis.GUIDANCE, sleep.basis)
        assertEquals(at(4, 2), first.single { it.kind == SleepPlan.Kind.CAFFEINE }.at)
        // The second night has no nap; a 6-hour target still sleeps 6 hours.
        val second = SleepPlan.forDay(all[1], all[2], all[3], targetMinutes = 300)
        assertTrue(second.none { it.kind == SleepPlan.Kind.NAP })
        assertEquals(at(5, 14), second.single { it.kind == SleepPlan.Kind.SLEEP_AFTER_NIGHT }.until)
        // The evening before an early 주간 (09:00): up at 07:30, in bed by 00:30.
        val beforeDay = SleepPlan.forDay(all[3], all[4], all[5])
        assertEquals(listOf(SleepPlan.Kind.CAFFEINE, SleepPlan.Kind.BEDTIME), beforeDay.map { it.kind })
        assertEquals(at(7, 0, 30), beforeDay.last().at)
        assertEquals(at(7, 7, 30), beforeDay.last().until)
        // Off today and tomorrow: nothing to say.
        assertTrue(SleepPlan.forDay(all[2], all[3], all[4]).none { it.kind == SleepPlan.Kind.BEDTIME && it.at.toLocalDate() == monday })
    }

    @Test
    fun `sleep debt counts only logged days and drops a missed wake tap`() {
        val today = LocalDate.of(2026, 11, 8)
        val log = listOf(
            SleepEntry(1, at(6, 23), at(7, 5)),          // 6 h
            SleepEntry(2, at(7, 23), at(8, 7)),          // 8 h
            SleepEntry(3, at(8, 13), at(8, 14)),         // nap, same wake day
            SleepEntry(4, LocalDateTime.of(2026, 10, 31, 22, 0), at(1, 6)), // older than 7 days
            SleepEntry(5, at(3, 22), at(4, 20)),         // 22 h: a missed tap
            SleepEntry(6, at(8, 22), null),              // still asleep
        )
        val debt = SleepDebt.of(log, today, targetMinutes = 7 * 60)
        assertEquals(2, debt.days)
        assertEquals(15 * 60L, debt.sleptMinutes)
        assertEquals(60L, debt.shortMinutes)
    }

    @Test
    fun `strict rules flag a third night and too few days off after nights`() {
        val n = Fixtures.night.id; val o = Fixtures.off.id; val d = Fixtures.day.id
        // 야 야 야 휴 주 …: legal by the default rules except the day after; strict adds two signs.
        val list = days(listOf(n, n, n, o, d, d, o))
        val normal = RestCheck.check(list).map { it.kind }
        assertTrue(normal.toString(), RestCheck.Kind.NIGHT_STREAK !in normal && RestCheck.Kind.SHORT_RECOVERY !in normal)
        val strict = RestCheck.check(list, strict = true)
        assertTrue(strict.toString(), strict.any { it.kind == RestCheck.Kind.NIGHT_STREAK && it.date == monday.plusDays(2) })
        assertTrue(strict.toString(), strict.any { it.kind == RestCheck.Kind.SHORT_RECOVERY && it.date == monday.plusDays(4) })
    }
}
