package com.bamnat.core

import com.bamnat.core.consent.RecommendedFeature
import com.bamnat.core.consent.RecommendedPlan
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SETUP-15] The first-start screen after "무슨 일 하세요?": each kind of work sees only its own notices (점검 보고서 중-9). */
class RecommendedStyleTest {
    @Test fun `shift work and no answer keep the four notices and the shift line`() {
        listOf(null, WorkStyle.SHIFT).forEach { s ->
            val plan = RecommendedPlan.forStyle(s)
            assertEquals(RecommendedPlan.NEW_INSTALL, plan.start)
            assertEquals(RecommendedPlan.ALREADY_ON, plan.alreadyOn)
            assertNull(plan.leftOff)
        }
    }

    @Test fun `weekday and part-time work are not told about coffee, naps or the way home after nights`() {
        listOf(WorkStyle.DAY, WorkStyle.HOURLY).forEach { s ->
            val plan = RecommendedPlan.forStyle(s)
            assertFalse(RecommendedFeature.BRIEFING in plan.start)
            assertFalse(RecommendedFeature.COMMUTE in plan.start)
            assertTrue(RecommendedFeature.TODAY_SHIFT in plan.start)
            assertFalse("$s", plan.features.any { "커피" in it.what || "야간" in it.what })
            assertFalse("커피" in plan.alreadyOn || "오늘의 흐름" in plan.alreadyOn)
            // What is off is said, with where to turn it on.
            assertTrue(plan.leftOff!!.contains(RecommendedPlan.TURN_OFF))
            // The reassurance does not promise a 퇴근길 notice that is off.
            assertFalse("퇴근길" in plan.reassure)
        }
        assertTrue("주휴수당" in RecommendedPlan.forStyle(WorkStyle.HOURLY).alreadyOn)
        assertTrue("급여 예상" in RecommendedPlan.forStyle(WorkStyle.DAY).alreadyOn)
    }

    @Test fun `work without shifts turns none of the shift notices on and says so`() {
        listOf(WorkStyle.FIELD, WorkStyle.BUSINESS, WorkStyle.INVEST).forEach { s ->
            val plan = RecommendedPlan.forStyle(s)
            assertTrue("$s", plan.start.isEmpty())
            assertNotNull(plan.leftOff)
            assertFalse("근무 알람" in plan.reassure || "퇴근길" in plan.reassure)
        }
        assertTrue("미수" in RecommendedPlan.forStyle(WorkStyle.FIELD).alreadyOn)
        assertTrue("하루 마감" in RecommendedPlan.forStyle(WorkStyle.BUSINESS).alreadyOn)
        assertTrue("수익률" in RecommendedPlan.forStyle(WorkStyle.INVEST).alreadyOn)
    }
}
