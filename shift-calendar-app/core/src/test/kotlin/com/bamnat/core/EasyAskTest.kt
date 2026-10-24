package com.bamnat.core

import com.bamnat.core.guide.Moment
import com.bamnat.core.profile.Ask
import com.bamnat.core.profile.AskPlace
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.AskState
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Missing
import com.bamnat.core.profile.Needs
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [EASY-04][EASY-05] What is missing, which first, and how often the card asks. */
class EasyAskTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val payFeatures = listOf("pay.calc", "pay.report", "pay.whatIf", "leave.ledger", "leave.promotion", "leave.payout", "alarm.check")

    @Test fun `missing facts are ranked by the features they bring and how soon`() {
        val missing = Needs.of(emptyMap(), payFeatures) { if (it == FactKey.ALARM_LEAD) 0 else null }
        // The alarm is needed tomorrow, so it comes first; then 기본급, which brings the most features.
        assertEquals(listOf(FactKey.ALARM_LEAD, FactKey.BASE_PAY), missing.take(2).map { it.key })
        val base = missing[1]
        // In the registry's order.
        assertEquals(listOf("pay.calc", "pay.report", "leave.payout", "pay.whatIf"), base.features)
        assertTrue(base.score > missing.first { it.key == FactKey.PAY_DAY }.score)
        // Features that are off bring nothing to ask about.
        assertTrue(Needs.of(emptyMap(), listOf("calendar.year")).isEmpty())
    }

    @Test fun `known facts are not missing, guessed money facts are`() {
        val known = mapOf(
            FactKey.BASE_PAY to Profile.read(FactKey.BASE_PAY, 2_600_000L, null),
            FactKey.MONTHLY_HOURS to Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209),
            FactKey.SLEEP_TARGET to Profile.read(FactKey.SLEEP_TARGET, 420, null, default = 420),
        )
        val keys = Needs.of(known, listOf("pay.whatIf", "pay.gap", "today.flow")).map { it.key }
        assertFalse(FactKey.BASE_PAY in keys)
        assertTrue(FactKey.MONTHLY_HOURS in keys)
        assertFalse(FactKey.SLEEP_TARGET in keys)
    }

    @Test fun `the score`() {
        assertEquals(8.0, Needs.score(1, 0), 1e-9)
        assertEquals(2 * (1 + 7.0 / 31), Needs.score(2, 30), 1e-9)
    }

    @Test fun `the reward names what an answer brings`() {
        val m = Missing(FactKey.HIRE_DATE, listOf("leave.ledger", "leave.promotion"), 3, 1.0)
        val text = Needs.reward(m)
        assertTrue(text, text.startsWith("알려 주시면 "))
        assertTrue(text, text.endsWith(" 함께 맞춰져요"))
        assertEquals("알려 주시면 더 정확해져요", Needs.reward(m.copy(features = emptyList())))
        assertEquals("이", Needs.subject("연차 원장"))
        assertEquals("가", Needs.subject("미리 보기"))
        assertEquals("이", Needs.subject("PDF"))
    }

    @Test fun `two a day, the same card all day`() {
        val missing = Needs.of(emptyMap(), payFeatures)
        var state = AskState()
        val first = AskScheduler.pick(state, missing, AskPlace.Home, today)!!
        assertTrue(first.isNew)
        state = AskScheduler.shown(state, first, today)
        // Opened again later: the same card, not counted again.
        val again = AskScheduler.pick(state, missing, AskPlace.Home, today)!!
        assertFalse(again.isNew)
        assertEquals(first.missing.key, again.missing.key)
        assertEquals(state, AskScheduler.shown(state, again, today))
        // Answered: the next one may come today.
        state = AskScheduler.answered(state, first.missing.key)
        val rest = missing.filter { it.key != first.missing.key }
        val second = AskScheduler.pick(state, rest, AskPlace.Home, today)!!
        assertTrue(second.isNew)
        state = AskScheduler.answered(AskScheduler.shown(state, second, today), second.missing.key)
        assertNull(AskScheduler.pick(state, rest.filter { it.key != second.missing.key }, AskPlace.Home, today))
        // Tomorrow it starts again.
        assertNotNull(AskScheduler.pick(state, rest.filter { it.key != second.missing.key }, AskPlace.Home, today.plusDays(1)))
    }

    @Test fun `later puts off a week, twice parks it`() {
        val missing = Needs.of(emptyMap(), listOf("leave.promotion"))
        var state = AskState()
        state = AskScheduler.later(state, FactKey.HIRE_DATE, today)
        assertNull(AskScheduler.pick(state, missing, AskPlace.Home, today.plusDays(6)))
        assertNotNull(AskScheduler.pick(state, missing, AskPlace.Home, today.plusDays(7)))
        state = AskScheduler.later(state, FactKey.HIRE_DATE, today.plusDays(7))
        assertTrue(state.parked(FactKey.HIRE_DATE))
        assertNull(AskScheduler.pick(state, missing, AskPlace.Home, today.plusDays(60)))
        // Answered in settings anyway: the count goes.
        assertFalse(AskScheduler.answered(state, FactKey.HIRE_DATE).parked(FactKey.HIRE_DATE))
    }

    @Test fun `a screen asks only what its features need`() {
        val missing = Needs.of(emptyMap(), payFeatures)
        val onLeave = AskScheduler.pick(AskState(), missing, AskPlace.Screen(setOf("leave.promotion")), today)!!
        assertEquals(FactKey.HIRE_DATE, onLeave.missing.key)
        assertNull(AskScheduler.pick(AskState(), missing, AskPlace.Screen(setOf("calendar.year")), today))
    }

    @Test fun `a good moment puts its fact first`() {
        val missing = Needs.of(emptyMap(), listOf("pay.calc", "pay.report", "shift.premium"))
        assertEquals(FactKey.BASE_PAY, AskScheduler.pick(AskState(), missing, AskPlace.Home, today)!!.missing.key)
        val afterNight = AskScheduler.pick(AskState(), missing, AskPlace.Home, today, setOf(Moment.AFTER_NIGHT))!!
        assertEquals(FactKey.NIGHT_OT, afterNight.missing.key)
        AskScheduler.MOMENTS.values.forEach { k -> assertTrue(k.id, k.usedBy.isNotEmpty()) }
    }

    @Test fun `the tip waits on a day with a question`() {
        val missing = Needs.of(emptyMap(), payFeatures)
        val state = AskState()
        assertTrue(AskScheduler.tipAllowed(state, today))
        val ask: Ask = AskScheduler.pick(state, missing, AskPlace.Home, today)!!
        val shown = AskScheduler.shown(state, ask, today)
        assertFalse(AskScheduler.tipAllowed(shown, today))
        assertTrue(AskScheduler.tipAllowed(shown, today.plusDays(1)))
        assertEquals(Source.DEFAULT, Profile.read<Int>(FactKey.ALARM_LEAD, null, null).source)
    }
}
