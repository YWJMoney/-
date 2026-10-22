package com.bamnat.core

import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.DutyCheck
import com.bamnat.core.schedule.DutyCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** [NURS-02] A typed month read back: 역순, nights without days off, long runs. */
class DutyCheckTest {
    private val d = ShiftType(1, "데이", "D", 0, LocalTime.of(6, 30), LocalTime.of(15, 0))
    private val e = ShiftType(2, "이브닝", "E", 0, LocalTime.of(14, 0), LocalTime.of(22, 30))
    private val n = ShiftType(3, "나이트", "N", 0, LocalTime.of(22, 0), LocalTime.of(7, 0))
    private val o = ShiftType(4, "오프", "O", 0, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 2)

    private fun month(vararg s: ShiftType?) = s.mapIndexed { i, t -> start.plusDays(i.toLong()) to t }

    @Test fun nightIsNight() {
        assertTrue(DutyCheck.isNight(n))
        assertEquals(false, DutyCheck.isNight(e))
        assertEquals(false, DutyCheck.isNight(d))
    }

    @Test fun reverseOrderIsShortRest() {
        val w = DutyCheck.check(month(e, d, o))
        assertEquals(listOf(Kind.SHORT_REST), w.map { it.kind })
        assertTrue(w[0].text, w[0].text.contains("8시간"))
    }

    /** [REST-02] Short rest and no day off after the night say the same about one day: one sign, named N-D. */
    @Test fun nightThenDayIsOneSign() {
        val w = DutyCheck.check(month(n, d, o, o))
        assertEquals(listOf(Kind.SHORT_REST), w.map { it.kind })
        assertEquals("N-D", w[0].pattern)
        assertTrue(w[0].text, w[0].text.startsWith("N-D · "))
    }

    @Test fun nightsThenTwoOffsAreFine() {
        assertTrue(DutyCheck.check(month(n, n, n, o, o, d, d)).isEmpty())
        val one = DutyCheck.check(month(n, n, o, e))
        assertEquals(listOf(Kind.NO_OFF_AFTER_NIGHT), one.map { it.kind })
        assertTrue(DutyCheck.check(month(n, n, o, e), com.bamnat.core.schedule.RestRules(offsAfterNights = 1)).isEmpty())
    }

    @Test fun manyNightsAndLongRuns() {
        val w = DutyCheck.check(month(n, n, n, n, o, o))
        assertEquals(listOf(Kind.MANY_NIGHTS), w.map { it.kind })
        val run = DutyCheck.check(month(d, d, d, d, d, d, d, o))
        assertEquals(listOf(Kind.LONG_RUN), run.map { it.kind })
    }

    @Test fun endOfMonthUnknownIsNotAWarning() {
        assertTrue(DutyCheck.check(month(o, n)).isEmpty())
    }
}
