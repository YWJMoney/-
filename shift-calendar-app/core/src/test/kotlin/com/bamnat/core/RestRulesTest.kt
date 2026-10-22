package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.DutyCheck
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.RestCheck.Kind
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [REST-01][REST-02] One set of limits for the calendar's 휴식 점검 and the month typed at once: the same roster gives
 * the same signs in both, every sign says where its limit comes from, named orders (N-O-D, E-D) and a day's
 * overlapping signs come once.
 */
class RestRulesTest {
    private val n = Fixtures.night; private val d = Fixtures.day; private val e = Fixtures.evening; private val o = Fixtures.off
    private val monday = LocalDate.of(2026, 11, 2)

    private fun rest(steps: List<com.bamnat.core.model.ShiftType>, rules: RestRules = RestRules()): List<RestCheck.Issue> {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, steps.map { it.id }), emptyList())
        return RestCheck.check((0L until steps.size).map { r.resolve(monday.plusDays(it)) }, rules)
    }
    private fun duty(steps: List<com.bamnat.core.model.ShiftType>, rules: RestRules = RestRules()) =
        DutyCheck.check(steps.mapIndexed { i, t -> monday.plusDays(i.toLong()) to t }, rules)

    @Test fun encodeDecodeAndOldStrict() {
        val r = RestRules(on = false, minRestHours = 12, offsAfterNights = 1, maxNightsInRow = 4, maxWorkDaysInRow = 5, maxNightsPerMonth = 14)
        assertEquals(r, RestRules.decode(r.encode()))
        assertEquals(RestRules(), RestRules.decode(""))
        assertEquals(RestRules.Preset.STRICT.rules, RestRules.decode(null, legacyStrict = true))
        // A broken value keeps its default, the rest is read.
        assertEquals(RestRules(minRestHours = 10), RestRules.decode("rest=10;nights=abc;month=99"))
        assertEquals(RestRules.Preset.BASIC, RestRules().preset)
        assertEquals(null, r.preset)
    }

    @Test fun bothChecksSeeTheSameNightsAndRuns() {
        val roster = listOf(n, n, n, n, o, o, d, d, d, d, d, d, d, o, o)
        assertTrue(rest(roster).any { it.kind == Kind.NIGHT_STREAK })
        assertTrue(duty(roster).any { it.kind == DutyCheck.Kind.MANY_NIGHTS })
        assertTrue(rest(roster).any { it.kind == Kind.LONG_RUN && it.date == monday.plusDays(12) })
        assertTrue(duty(roster).any { it.kind == DutyCheck.Kind.LONG_RUN })
        // Raised limits quiet both.
        val loose = RestRules(maxNightsInRow = 4, maxWorkDaysInRow = 7)
        assertTrue(rest(roster, loose).none { it.kind == Kind.NIGHT_STREAK || it.kind == Kind.LONG_RUN })
        assertTrue(duty(roster, loose).none { it.kind == DutyCheck.Kind.MANY_NIGHTS || it.kind == DutyCheck.Kind.LONG_RUN })
    }

    @Test fun offTurnsEverythingOff() {
        val roster = listOf(n, n, n, n, d, e, d)
        assertTrue(rest(roster).isNotEmpty())
        assertTrue(rest(roster, RestRules(on = false)).isEmpty())
        assertTrue(duty(roster, RestRules(on = false)).isEmpty())
    }

    @Test fun namedOrders() {
        // 나오데: nights, one day off, then a day shift.
        val nod = rest(listOf(n, n, o, d, o, o, o))
        val sign = nod.single { it.kind == Kind.SHORT_RECOVERY }
        assertEquals("N-O-D(나오데)", sign.pattern)
        assertTrue(sign.text, sign.text.startsWith("N-O-D(나오데) · "))
        assertEquals("N-O-D(나오데)", duty(listOf(n, n, o, d, o, o, o)).single().pattern)
        // 이브데이: 오후 then 주간 the next morning.
        val ed = rest(listOf(e, d, o, o, o, o, o))
        assertEquals("E-D(이브데이)", ed.single().pattern)
    }

    @Test fun oneSignForOneDaysRest() {
        // A day shift right after a night is short rest after a night and too few days off at once: one sign.
        val signs = rest(listOf(n, d, o, o, o, o, o))
        assertEquals(listOf(Kind.AFTER_NIGHT), signs.map { it.kind })
        assertEquals("N-D", signs.single().pattern)
    }

    @Test fun nightsInAMonth() {
        // A night every other day: 15 in November is the limit, not over it.
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday.withDayOfMonth(1), listOf(n.id, o.id)), emptyList())
        val nov = (0L until 30L).map { r.resolve(LocalDate.of(2026, 11, 1).plusDays(it)) }
        assertFalse(RestCheck.check(nov, RestRules()).any { it.kind == Kind.MONTH_NIGHTS })
        val over = RestCheck.check(nov, RestRules(maxNightsPerMonth = 14)).single { it.kind == Kind.MONTH_NIGHTS }
        assertEquals(LocalDate.of(2026, 11, 29), over.date)
        assertTrue(over.text, over.text.contains("15일"))
        assertEquals(15, RestCheck.nightDates(nov).size)
    }

    @Test fun everySignSaysWhereItComesFrom() {
        val all = rest(listOf(n, n, n, n, d, e, d, d, d, d, o)) + rest(listOf(e, d, o))
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue(it.toString(), it.source.isNotBlank()) }
        duty(listOf(n, n, n, n, d, e, d, d, d, d, o)).forEach { assertTrue(it.toString(), it.source.isNotBlank()) }
    }

    @Test fun lookbackCoversRunsAndTheMonth() {
        val from = LocalDate.of(2026, 11, 20)
        assertEquals(LocalDate.of(2026, 11, 1), RestCheck.lookbackFrom(from, RestRules()))
        assertEquals(from.minusDays(7), RestCheck.lookbackFrom(from, RestRules(maxNightsPerMonth = 0)))
    }
}
