package com.bamnat.core

import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.RecommendedFeature
import com.bamnat.core.consent.RecommendedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ONBOARD-03][ONBOARD-04] 추천 기능 한 번에 켜기: what is on, what is asked in turn, and the one offer after the update. */
class RecommendedTest {
    private val all = ConsentItem.entries.toSet()

    @Test fun aNewPhoneStartsWithEveryRecommendedFeatureOn() {
        assertEquals(RecommendedFeature.entries.toSet(), RecommendedPlan.NEW_INSTALL)
        RecommendedFeature.entries.forEach {
            assertTrue("${it.title} says why it is fine on", it.okay.isNotBlank())
            assertTrue("${it.title} says where it turns off", it.offAt.startsWith("설정"))
            // [SIMPLE-04] One place, said the same under the switches and on each line.
            assertEquals(RecommendedPlan.TURN_OFF, it.offAt)
            assertEquals(it, RecommendedFeature.ofKey(it.key))
        }
        assertEquals("keys are stored and must stay apart", RecommendedFeature.entries.size, RecommendedFeature.entries.map { it.key }.toSet().size)
    }

    @Test fun nothingThatGoesToAServerIsTurnedOnBeforehand() {
        // D42: usage statistics and ad personalisation are asked on their own, never pre-ticked.
        val words = RecommendedFeature.entries.joinToString(" ") { it.title + it.what }
        listOf("통계", "광고", "서버", "보내기").forEach { assertFalse("‘$it’ is not a recommended default", it in words) }
    }

    @Test fun notificationsComeFirstAndSleepOnlyWithAWatch() {
        assertEquals(
            listOf(ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS, ConsentItem.CALENDAR),
            RecommendedPlan.asks(all, granted = emptySet(), watch = false),
        )
        assertEquals(ConsentItem.SLEEP, RecommendedPlan.asks(all, emptySet(), watch = true).last())
    }

    @Test fun whatIsAllowedOrMissingIsSkipped() {
        assertEquals(
            "an Android 13 phone has no lock-screen question, and notices are already allowed",
            listOf(ConsentItem.CALENDAR),
            RecommendedPlan.asks(all - ConsentItem.ALARMS, granted = setOf(ConsentItem.NOTIFICATIONS), watch = false),
        )
        assertTrue("everything allowed: nothing to ask", RecommendedPlan.asks(all, all, watch = true).isEmpty())
        assertTrue("no Health Connect on this phone", ConsentItem.SLEEP !in RecommendedPlan.asks(all - ConsentItem.SLEEP, emptySet(), watch = true))
    }

    @Test fun aPhoneSetUpBeforeIsOfferedOnceAndOnlyWhenSomethingIsOff() {
        val some = setOf(RecommendedFeature.BRIEFING)
        assertEquals(RecommendedFeature.entries - RecommendedFeature.BRIEFING, RecommendedPlan.offNow(some))
        assertTrue(RecommendedPlan.offerOnce(freshInstall = false, offered = false, on = some))
        assertFalse("offered already", RecommendedPlan.offerOnce(false, offered = true, on = some))
        assertFalse("a new phone has the first-start screen", RecommendedPlan.offerOnce(true, false, some))
        assertFalse("all on: nothing to offer", RecommendedPlan.offerOnce(false, false, RecommendedFeature.entries.toSet()))
    }
}
