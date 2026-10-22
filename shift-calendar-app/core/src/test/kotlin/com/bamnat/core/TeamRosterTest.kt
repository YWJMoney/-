package com.bamnat.core

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.CoverFinder
import com.bamnat.core.schedule.TeamRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

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

    // [CREW-02] Marking the third team mine makes it my calendar and keeps the order of the others.
    @Test fun thirdTeamAsMineRunsMyCalendar() {
        val rotation = WorkPattern.Rotation(start, listOf(d, d, n, n, null, null, null, null))
        val roster = TeamRoster.suggest(rotation).withMine(2, rotation.steps.size)
        assertEquals("C조", roster.mine.name)
        assertEquals(0, roster.mine.lag)
        for (i in 0 until 16) {
            val day = start.plusDays(i.toLong())
            assertEquals(rotation.shiftTypeIdOn(day), roster.shiftOn(rotation, roster.mine, day))
            // Every shift still on exactly one team a day.
            assertEquals(listOf(d, n), roster.teams.mapNotNull { roster.shiftOn(rotation, it, day) }.sorted())
        }
        // The gaps between teams are what they were: B two days after A, C two after B…
        val before = TeamRoster.suggest(rotation)
        val gaps = { r: TeamRoster -> r.teams.zipWithNext { a, b -> Math.floorMod(b.lag - a.lag, 8) } }
        assertEquals(gaps(before), gaps(roster))
    }

    // [CREW-02] A roster saved by 1.18~1.20 with a later team marked mine but never moved is fixed on reading.
    @Test fun oldRosterWithLaterTeamMineIsAnchored() {
        val old = TeamRoster(listOf(TeamRoster.Team("A조", 0), TeamRoster.Team("B조", 2), TeamRoster.Team("C조", 4, mine = true), TeamRoster.Team("D조", 6)))
        val fixed = old.anchored(8)
        assertEquals(0, fixed.mine.lag)
        assertEquals(listOf(4, 6, 0, 2), fixed.teams.map { it.lag })
    }

    // [CREW-03] "우리 조는 3번째" spreads the teams in the order shown with the third one mine.
    @Test fun inOrderPutsMineAtTheChosenPlace() {
        val rotation = WorkPattern.Rotation(start, listOf(d, d, n, n, null, null, null, null))
        val roster = TeamRoster.suggest(rotation).inOrder(rotation, 2)
        assertEquals(2, roster.mineIndex)
        assertEquals(listOf(4, 6, 0, 2), roster.teams.map { it.lag })
        assertEquals(listOf("B조", "A조", "C조", "D조"), roster.moved(1, 0).teams.map { it.name })
    }

    @Test fun myTeamsTodayComesFromTheCalendar() {
        val rotation = WorkPattern.Rotation(start, listOf(d, n, null, null))
        val roster = TeamRoster.suggest(rotation)
        assertEquals(roster, roster.withToday(rotation, "A조", 1, start))
    }

    // [CREW-04] The user's example: taking the first 주간(모닝) of a run off in 4조 2교대 주주야야휴휴휴휴.
    // The team that came off 야간 that morning cannot start at 07:00, the team on 주간 yesterday goes on
    // 야간 that evening, and the team off both days is the one to ask.
    @Test fun coverForFirstMorningSkipsLastNightsTeam() {
        val types = mapOf(
            d to ShiftType(d, "주간", "주", 0, LocalTime.of(7, 0), LocalTime.of(19, 0)),
            n to ShiftType(n, "야간", "야", 0, LocalTime.of(19, 0), LocalTime.of(7, 0)),
        )
        val rotation = WorkPattern.Rotation(start, listOf(d, d, n, n, null, null, null, null))
        val roster = TeamRoster.suggest(rotation)
        val day = start.plusDays(8)
        val result = CoverFinder.find(roster, rotation, types, day, myShiftId = d)
        assertEquals(3, result.options.size)
        val night = result.options.first { it.yesterday?.id == n }
        assertEquals(CoverFinder.Verdict.SHORT_REST, night.verdict)
        assertTrue(night.reason, night.reason.contains("어제 야간(07:00 끝) 뒤 0시간"))
        val sameShift = result.options.first { it.sameShiftYesterday }
        assertEquals(d, sameShift.yesterday?.id)
        assertTrue(sameShift.reason, sameShift.reason.startsWith("어제 같은 근무(주간)를 한 조"))
        assertFalse(sameShift.canCover)
        val first = result.options.first()
        assertEquals(CoverFinder.Verdict.CAN, first.verdict)
        assertEquals(null, first.yesterday)
        assertEquals(null, first.today)
        assertEquals(listOf(first.team.name), result.candidates.map { it.team.name })
        assertTrue(result.request("10/9(금)").endsWith("들어올 수 있는 조: ${first.team.name}"))
    }

    @Test fun coverRestRuleCanBeLoosened() {
        val types = mapOf(
            d to ShiftType(d, "주간", "주", 0, LocalTime.of(9, 0), LocalTime.of(18, 0)),
            n to ShiftType(n, "야간", "야", 0, LocalTime.of(18, 0), LocalTime.of(0, 0)),
        )
        val rotation = WorkPattern.Rotation(start, listOf(d, n, null, null))
        val roster = TeamRoster.suggest(rotation)
        // A team ending 야간 at midnight has 9 hours before my 09:00 주간: short at 11, fine at 8.
        val day = start.plusDays(4)
        val strict = CoverFinder.find(roster, rotation, types, day, d)
        val loose = CoverFinder.find(roster, rotation, types, day, d, minRestMinutes = 8 * 60)
        val team = strict.options.first { it.yesterday?.id == n }.team
        assertEquals(CoverFinder.Verdict.SHORT_REST, strict.options.first { it.team == team }.verdict)
        assertTrue(loose.options.first { it.team == team }.canCover)
    }
}
