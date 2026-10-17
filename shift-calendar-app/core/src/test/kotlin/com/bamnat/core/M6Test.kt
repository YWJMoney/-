package com.bamnat.core

import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [SLP-08] Health Connect sleep merged with the two-tap log. */
class M6Test {
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m)
    private fun hc(s: LocalDateTime, e: LocalDateTime) = SleepEntry(start = s, end = e, imported = true)

    @Test
    fun manualWinsOverOverlappingMeasuredSleep() {
        val manual = listOf(SleepEntry(1, at(10, 9), at(10, 15)))
        val merged = SleepMerge.merge(manual, listOf(hc(at(10, 8, 40), at(10, 16)), hc(at(11, 9), at(11, 16))))
        assertEquals(2, merged.size)
        assertEquals(at(11, 9), merged[0].start)
        assertTrue(merged[0].imported)
        assertEquals(1L, merged[1].id)
        assertFalse(merged[1].imported)
    }

    @Test
    fun overlappingMeasuredSleepsFromTwoAppsCountOnce() {
        val merged = SleepMerge.merge(emptyList(), listOf(hc(at(10, 23), at(11, 6)), hc(at(10, 23, 10), at(11, 6, 30)), hc(at(11, 13), at(11, 14))))
        assertEquals(listOf(at(11, 13), at(10, 23, 10)), merged.map { it.start })
        val today = SleepDebt.of(merged, LocalDate.of(2026, 10, 11), 7 * 60)
        assertEquals(1, today.days)
        assertEquals(7 * 60 + 20 + 60L, today.sleptMinutes)
    }

    @Test
    fun openTapAndBrokenRecordsDoNotHideOrBreakAnything() {
        val open = SleepEntry(2, at(12, 22), null)
        val merged = SleepMerge.merge(listOf(open), listOf(hc(at(12, 21), at(13, 5)), SleepEntry(start = at(13, 9), end = at(13, 9), imported = true)))
        assertEquals(2, merged.size)
        assertEquals(open, merged[0])
        assertTrue(merged[1].imported)
    }
}
