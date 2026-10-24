package com.bamnat.core

import com.bamnat.core.alarm.AlarmMission
import com.bamnat.core.alarm.MathProblem
import com.bamnat.core.alarm.Missions
import com.bamnat.core.alarm.ShakeCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** [PROV-06] 끄기 미션: which one an alarm asks for, sums with sensible answers, shakes, and the way out. */
class AlarmMissionTest {
    @Test fun keysAndUnknownsFallBackToNone() {
        assertEquals(AlarmMission.MATH, AlarmMission.of("math"))
        assertEquals(AlarmMission.SHAKE, AlarmMission.of("shake"))
        assertEquals(AlarmMission.NONE, AlarmMission.of("none"))
        assertEquals(AlarmMission.PHOTO, AlarmMission.of("photo"))
        assertEquals("an old or broken value rings as before", AlarmMission.NONE, AlarmMission.of("selfie"))
        assertEquals(AlarmMission.NONE, AlarmMission.of(null))
        assertEquals(AlarmMission.entries.map { it.key }.toSet().size, AlarmMission.entries.size)
    }

    @Test fun aShiftsOwnMissionWinsOverTheDefault() {
        val byShift = mapOf(3L to AlarmMission.SHAKE, 5L to AlarmMission.NONE)
        assertEquals(AlarmMission.SHAKE, AlarmMission.forAlarm(AlarmMission.MATH, byShift, 3))
        assertEquals("a shift can ask for none while the rest ask", AlarmMission.NONE, AlarmMission.forAlarm(AlarmMission.MATH, byShift, 5))
        assertEquals(AlarmMission.MATH, AlarmMission.forAlarm(AlarmMission.MATH, byShift, 4))
        assertEquals("a memo schedule takes the default", AlarmMission.MATH, AlarmMission.forAlarm(AlarmMission.MATH, byShift, 0))
    }

    @Test fun sumsHaveTwoDigitNumbersAndAPositiveAnswer() {
        val random = Random(7)
        repeat(2_000) {
            val p = Missions.problem(random)
            assertTrue(p.a in 10..99 && p.b in 10..99)
            assertTrue("${p.text} = ${p.answer}", p.answer in 0..198)
        }
        assertEquals(80, MathProblem(47, 33, true).answer)
        assertEquals("47 + 33", MathProblem(47, 33, true).text)
        assertEquals(14, MathProblem(47, 33, false).answer)
        assertEquals("47 − 33", MathProblem(47, 33, false).text)
    }

    @Test fun afterFiveMinutesItCanBeTurnedOffWithoutTheMission() {
        val since = Instant.parse("2026-10-23T21:30:00Z")
        assertFalse(Missions.canSkip(since, since))
        assertFalse(Missions.canSkip(since, since.plus(Duration.ofMinutes(5)).minusSeconds(1)))
        assertTrue(Missions.canSkip(since, since.plus(Duration.ofMinutes(5))))
        assertTrue(Missions.canSkip(since, since.plus(Duration.ofHours(1))))
    }

    @Test fun aShakeCountsOncePerSwing() {
        val counter = ShakeCounter()
        assertFalse("holding the phone is no shake", counter.add(0.3f, 0.2f, 0.4f, 0))
        assertTrue(counter.add(10f, 8f, 0f, 100))
        assertFalse("the same swing", counter.add(12f, 0f, 0f, 400))
        assertTrue(counter.add(0f, 0f, -13f, 650))
        assertEquals(2, counter.count)
        var t = 1_000L
        while (counter.count < Missions.SHAKES) { counter.add(15f, 0f, 0f, t); t += 600 }
        assertEquals(Missions.SHAKES, counter.count)
    }
}
