package com.bamnat.core

import com.bamnat.core.leave.EntryKind
import com.bamnat.core.leave.LeaveAccrual
import com.bamnat.core.leave.LeaveLedger
import com.bamnat.core.leave.LeaveLot
import com.bamnat.core.leave.LeavePolicy
import com.bamnat.core.leave.LeavePool
import com.bamnat.core.leave.LeaveUse
import com.bamnat.core.leave.LedgerEntry
import com.bamnat.core.leave.LineKind
import com.bamnat.core.leave.LotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [LEAVE-01] The 연차 원장: lots, uses from the soonest to lapse, 미리 쓴 연차, grants, 정산 and 보상휴가. */
class LeaveLedgerTest {
    private val hire = LocalDate.of(2025, 3, 2)
    private val today = LocalDate.of(2026, 6, 1)
    private val auto = LeaveAccrual.lots(hire, LeavePolicy(), today.plusYears(1))

    @Test
    fun `uses come off the soonest to lapse and the rest of the first year lapses`() {
        val s = LeaveLedger.build(today, auto, emptyList(), listOf(LeaveUse(LocalDate.of(2025, 5, 10), 1.0), LeaveUse(LocalDate.of(2026, 4, 10), 0.5)))
        assertEquals(14.5, s.available, 1e-9)
        assertEquals(1.5, s.used, 1e-9)
        // Eleven monthly days, one used: ten lapse on 2026-03-01.
        val lapsed = s.lines.filter { it.kind == LineKind.LAPSE && it.date == LocalDate.of(2026, 3, 1) }
        assertEquals(-10.0, lapsed.sumOf { it.days }, 1e-9)
        assertEquals(LocalDate.of(2027, 3, 1) to 14.5, s.nextLapse)
        assertEquals(LocalDate.of(2027, 3, 1), s.open.single().lot.expires)
    }

    @Test
    fun `leave taken before any arose is paid back by the next lot`() {
        val s = LeaveLedger.build(LocalDate.of(2025, 3, 20), auto, emptyList(), listOf(LeaveUse(LocalDate.of(2025, 3, 10), 1.0)))
        assertEquals(-1.0, s.available, 1e-9)
        val later = LeaveLedger.build(LocalDate.of(2025, 4, 5), auto, emptyList(), listOf(LeaveUse(LocalDate.of(2025, 3, 10), 1.0)))
        assertEquals(0.0, later.available, 1e-9)
        assertTrue(later.lines.first { it.kind == LineKind.ARISE }.note.contains("미리 쓴"))
    }

    @Test
    fun `a grant for a leave year takes the place of the app's own lot`() {
        val grant = LedgerEntry(1, EntryKind.GRANT, LocalDate.of(2026, 3, 2), 17.0, LocalDate.of(2027, 3, 1), yearStart = LocalDate.of(2026, 3, 2))
        val s = LeaveLedger.build(today, auto, listOf(grant), listOf(LeaveUse(LocalDate.of(2026, 4, 10), 0.5)))
        assertEquals(16.5, s.available, 1e-9)
        assertTrue(s.lots.none { it.lot.kind == LotKind.ANNUAL && it.lot.yearStart == LocalDate.of(2026, 3, 2) })
    }

    @Test
    fun `planned days count after today and lots lapsing today are still usable`() {
        val carry = LedgerEntry(2, EntryKind.CARRY, LocalDate.of(2026, 1, 1), 3.0, expires = today)
        val s = LeaveLedger.build(today, emptyList(), listOf(carry), listOf(LeaveUse(today.plusDays(10), 1.0)))
        assertEquals(3.0, s.available, 1e-9)
        assertEquals(1.0, s.planned, 1e-9)
        assertEquals(LineKind.PLANNED, s.lines.single { it.days == -1.0 }.kind)
    }

    @Test
    fun `a payout and a negative adjustment take days, comp leave keeps its own balance`() {
        val entries = listOf(
            LedgerEntry(3, EntryKind.ADJUST, LocalDate.of(2026, 4, 1), -2.0),
            LedgerEntry(4, EntryKind.PAYOUT, LocalDate.of(2026, 5, 1), 3.0),
        )
        val comp = listOf(LeaveLot(LotKind.COMP, 1.5, LocalDate.of(2026, 5, 3), null))
        val s = LeaveLedger.build(today, auto, entries, listOf(LeaveUse(LocalDate.of(2026, 5, 20), 1.0, LeavePool.COMP)), comp)
        assertEquals(10.0, s.available, 1e-9)
        assertEquals(0.5, s.comp, 1e-9)
        assertEquals(-3.0, s.lines.single { it.kind == LineKind.PAYOUT }.days, 1e-9)
    }
}
