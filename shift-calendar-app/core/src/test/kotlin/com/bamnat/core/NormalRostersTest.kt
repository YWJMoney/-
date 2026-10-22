package com.bamnat.core

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.DutyCheck
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.RotationSpeed
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * [REST-01][REST-03] A check left on must not nag: the usual lawful rosters say nothing under 기본, on the
 * calendar ([RestCheck]) and in the month typed at once ([DutyCheck]). A sign here means the rule or the
 * default is wrong, not the roster.
 */
class NormalRostersTest {
    private fun t(id: Long, name: String, label: String, from: Int?, to: Int?, off: Boolean = false) =
        ShiftType(id, name, label, 0xFF000000, from?.let { LocalTime.of(it, 0) }, to?.let { LocalTime.of(it, 0) }, isOff = off)

    private val rest = t(9, "휴무", "휴", null, null, off = true)
    private val after = t(8, "비번", "비", null, null, off = true)
    // 12시간 2교대
    private val day12 = t(1, "주간", "주", 7, 19)
    private val night12 = t(2, "야간", "야", 19, 7)
    // 병원 3교대
    private val d = t(3, "데이", "D", 7, 15)
    private val e = t(4, "이브닝", "E", 15, 23)
    private val n = t(5, "나이트", "N", 23, 7)
    // 소방 3조 2교대: 주간 09–18, 야간 18–09
    private val fireDay = t(6, "주간", "주", 9, 18)
    private val fireNight = t(7, "야간", "야", 18, 9)
    private val types = listOf(rest, after, day12, night12, d, e, n, fireDay, fireNight)

    private fun steps(codes: String, map: Map<Char, ShiftType>): List<Long?> = codes.map { map.getValue(it).id }

    /** Every sign over [cycles] turns of [codes] from 2026-11-02, read the way the calendar reads it. */
    private fun signs(codes: String, map: Map<Char, ShiftType>, cycles: Int = 3): Pair<List<RestCheck.Issue>, List<DutyCheck.Warning>> {
        val start = LocalDate.of(2026, 11, 2)
        val pattern = WorkPattern.Rotation(start, steps(codes, map))
        val rules = RotationSpeed.of(pattern, types).adjust(RestRules())
        val end = start.plusDays(codes.length * cycles - 1L)
        val resolver = ScheduleResolver(types, listOf(pattern.withRange(start, end)), emptyList())
        val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val calendar = RestCheck.check(dates.map(resolver::resolve), rules)
        val typed = DutyCheck.check(dates.map { it to resolver.resolve(it).shiftType }, rules)
        return calendar to typed
    }

    private fun assertQuiet(name: String, codes: String, map: Map<Char, ShiftType>, cycles: Int = 3) {
        val (calendar, typed) = signs(codes, map, cycles)
        assertEquals("$name 달력: ${calendar.map { it.text }}", emptyList<String>(), calendar.map { it.text })
        assertEquals("$name 연달아 넣기: ${typed.map { it.text }}", emptyList<String>(), typed.map { it.text })
    }

    private val twelve = mapOf('D' to day12, 'N' to night12, 'O' to rest, 'B' to after)

    @Test fun fourTeamsTwoShifts() {
        assertQuiet("4조 2교대 주주야야비휴휴휴", "DDNNBOOO", twelve)
        assertQuiet("4조 2교대 주야비휴", "DNBO", twelve)
    }

    @Test fun threeTeamsTwoShifts() = assertQuiet("3조 2교대 주주야야휴휴", "DDNNOO", twelve)

    @Test fun fireThreeTeams21Days() =
        assertQuiet("소방 3조 2교대 21일", "DDDDDOONBNBNBNBNBNBNB", mapOf('D' to fireDay, 'N' to fireNight, 'O' to rest, 'B' to after))

    @Test fun aNurseMonth() =
        // Forward D→E→N, at most 3 nights, 2 days off after nights, 7 nights in the month: read once, not round.
        assertQuiet("간호 3교대 한 달", "DDEEONNNOODDEEOONNOODEEOODDENN", mapOf('D' to d, 'E' to e, 'N' to n, 'O' to rest), cycles = 1)

    @Test fun theStrictPresetStillSpeaksUpOnALongNightRun() {
        val (calendar, _) = signs("DDNNNNOO", twelve)
        assertEquals(true, calendar.any { it.kind == RestCheck.Kind.NIGHT_STREAK })
        assertEquals(true, calendar.first { it.kind == RestCheck.Kind.NIGHT_STREAK }.text.contains("4일 연속부터 알려요 · 기본"))
    }

    /** [REST-02] The same sign over a month is one line with its days; a sign seen once keeps its own words. */
    @Test fun aRepeatedSignIsOneLine() {
        val (calendar, _) = signs("DDNNNNOO", twelve)
        val groups = RestCheck.group(calendar)
        val streak = groups.single { it.kind == RestCheck.Kind.NIGHT_STREAK }
        assertEquals(3, streak.issues.size)
        assertEquals("연속 야간 3번", streak.title)
        assertEquals("7·15·23일", streak.text)
        val once = RestCheck.group(calendar.take(1)).single()
        assertEquals(calendar.first().text, once.text)
    }
}
