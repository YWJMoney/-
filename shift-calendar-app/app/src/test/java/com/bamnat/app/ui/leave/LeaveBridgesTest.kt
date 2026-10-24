package com.bamnat.app.ui.leave

import com.bamnat.core.work.DayWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [LIFE-02] 연차로 길게 쉬기 reads the ledger: a suggestion that needs more 연차 than is left says so. */
class LeaveBridgesTest {
    private val fri = LocalDate.of(2026, 11, 6)
    private val one = DayWork.Bridge(listOf(fri), fri.minusDays(3), fri.plusDays(2), emptyList())
    private val two = DayWork.Bridge(listOf(fri, fri.plusDays(3)), fri, fri.plusDays(4), emptyList())

    @Test fun whatIsLeftDecidesWhichFit() {
        assertTrue(LeaveBridges.fits(two, 2.0))
        assertTrue(LeaveBridges.fits(one, 1.5))
        assertFalse(LeaveBridges.fits(two, 1.5))
        assertNull(LeaveBridges.shortNote(one, 1.0))
        assertEquals("남은 연차 1.5일로는 모자라요", LeaveBridges.shortNote(two, 1.5))
        // Used more than had arisen: nothing is left, not a negative number.
        assertEquals("남은 연차 0일로는 모자라요", LeaveBridges.shortNote(one, -1.0))
    }

    @Test fun withoutALedgerEverySuggestionStands() {
        assertTrue(LeaveBridges.fits(two, null))
        assertNull(LeaveBridges.shortNote(two, null))
    }
}
