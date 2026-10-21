package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.TeamRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TeamRosterTest {
    private val d = 1L
    private val e = 2L
    private val n = 3L
    private val start = LocalDate.of(2026, 10, 1)

    @Test fun threeTeamsTwoShifts() {
        // 주주야야비휴
        assertEquals(3, TeamRoster.teamCount(listOf(d, d, n, n, null, null)))
    }

    @Test fun fourTeamsTwoShifts() {
        // 주야비휴
        assertEquals(4, TeamRoster.teamCount(listOf(d, n, null, null)))
        // 주주야야휴휴휴휴
        assertEquals(4, TeamRoster.teamCount(listOf(d, d, n, n, null, null, null, null)))
    }

    @Test fun fourTeamsThreeShifts() {
        // 주주오오야야휴휴
        assertEquals(4, TeamRoster.teamCount(listOf(d, d, e, e, n, n, null, null)))
    }

    @Test fun noEvenSplit() {
        // 당번 비번 당번 비번 휴무×4 has no split that puts the 당번 on one team a day.
        assertNull(TeamRoster.teamCount(listOf(d, null, d, null, null, null, null, null)))
    }

    @Test fun everyShiftCoveredOnceADay() {
        val rotation = WorkPattern.Rotation(start, listOf(d, d, n, n, null, null))
        val roster = TeamRoster.suggest(rotation)
        for (i in 0 until 12) {
            val day = start.plusDays(i.toLong())
            val shifts = roster.teams.mapNotNull { roster.shiftOn(rotation, it, day) }
            assertEquals(setOf(d, n), shifts.toSet())
            assertEquals(2, shifts.size)
            assertEquals(1, roster.offOn(rotation, day).size)
        }
    }

    @Test fun myTeamFollowsMyRotation() {
        val rotation = WorkPattern.Rotation(start, listOf(d, n, null, null))
        val roster = TeamRoster.suggest(rotation)
        for (i in 0 until 8) {
            val day = start.plusDays(i.toLong())
            assertEquals(rotation.shiftTypeIdOn(day), roster.shiftOn(rotation, roster.mine, day))
        }
    }

    @Test fun setTodayByWhatTheTeamWorks() {
        val rotation = WorkPattern.Rotation(start, listOf(d, n, null, null))
        val today = start.plusDays(5)
        val roster = TeamRoster.suggest(rotation).withToday(rotation, "C조", stepIndex = 1, today = today)
        val c = roster.teams.first { it.name == "C조" }
        assertEquals(n, roster.shiftOn(rotation, c, today))
        assertEquals(null, roster.shiftOn(rotation, c, today.plusDays(1)))
    }

    @Test fun roundTrip() {
        val roster = TeamRoster(listOf(TeamRoster.Team("우리조", 0, true), TeamRoster.Team("B조", 2)))
        assertEquals(roster, TeamRoster.decode(roster.encode()))
        assertNull(TeamRoster.decode(""))
        assertNull(TeamRoster.decode("garbage"))
    }
}
