package com.bamnat.core

import com.bamnat.core.work.DayWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** [LEAVE-04] 휴무 연결 for shift and part-time rosters: 1~2 working days between two runs of days off. */
class BridgeAllTypesTest {
    private val from = LocalDate.of(2026, 11, 1)

    @Test
    fun `a two on three off roster bridges two days into eight off, two a month at most`() {
        // 근무 근무 휴 휴 휴, from 11/1.
        val work = { d: LocalDate -> Math.floorMod(ChronoUnit.DAYS.between(from, d), 5L) < 2 }
        val bridges = DayWork.restBridges(from.plusDays(2), months = 3, isWorkDay = work)
        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.all { it.leave.size == 2 && it.days == 8 })
        assertTrue(bridges.groupBy { YearMonth.from(it.leave.first()) }.values.all { it.size <= 2 })
        assertTrue(bridges.all { b -> b.leave.all(work) })
    }

    @Test
    fun `a four team roster of four working days in a row gives none`() {
        // 주 주 야 야 휴 휴 휴 휴: four days in a row are too many to bridge.
        val work = { d: LocalDate -> Math.floorMod(ChronoUnit.DAYS.between(from, d), 8L) < 4 }
        assertEquals(emptyList<DayWork.Bridge>(), DayWork.restBridges(from, months = 3, isWorkDay = work))
    }

    @Test
    fun `a weekend part timer bridges a weekend into twelve days off`() {
        val work = { d: LocalDate -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY }
        val b = DayWork.restBridges(from, months = 1, isWorkDay = work).first()
        assertEquals(2, b.leave.size)
        assertEquals(DayOfWeek.SATURDAY, b.leave.first().dayOfWeek)
        assertEquals(12, b.days)
        assertEquals("연차 2일로 12일 쉬어요", b.text)
    }
}
