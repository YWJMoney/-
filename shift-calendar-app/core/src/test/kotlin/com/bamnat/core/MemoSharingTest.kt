package com.bamnat.core

import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoCalendars
import com.bamnat.core.memo.MemoNotebook
import com.bamnat.core.memo.MemoSharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MEMO-01] Memos stay with their own calendar unless the user lets calendars share; [MEMO-02] the groups. */
class MemoSharingTest {
    private val main = MemoCalendars.MAIN
    private val alba = "alba"
    private val shop = "shop"

    private fun memo(vararg calendars: String) = Memo(id = 1, body = "메모", calendars = calendars.toSet())

    @Test
    fun aNewMemoShowsOnlyOnTheCalendarItWasWrittenOn() {
        val m = memo(alba)
        assertTrue(m.showsOn(alba))
        assertFalse(m.showsOn(main))
        assertFalse(m.showsOn(shop))
        assertFalse(m.isShared())
    }

    @Test
    fun anOldMemoWithNoCalendarIsTheMainCalendarsAndNoLongerLeaks() {
        val old = memo()
        assertEquals(setOf(main), old.owners)
        assertTrue(old.showsOn(main))
        assertFalse(old.showsOn(alba))
        assertFalse(old.isShared())
    }

    @Test
    fun aMemoForEveryCalendarShowsEverywhere() {
        val all = memo(MemoCalendars.EVERY)
        assertTrue(all.onEveryCalendar)
        assertTrue(all.showsOn(main) && all.showsOn(alba) && all.showsOn("added-later"))
        assertTrue(all.isShared())
        assertEquals(",*,", MemoCalendars.encode(all.calendars))
        assertEquals(setOf(MemoCalendars.EVERY), MemoCalendars.decode(",*,"))
    }

    @Test
    fun calendarsInOneGroupSeeEachOthersMemosAndOthersDoNot() {
        val sharing = MemoSharing(listOf(setOf(main, alba)))
        assertFalse(sharing.separate)
        assertTrue(memo(alba).showsOn(main, sharing))
        assertTrue(memo().showsOn(alba, sharing))
        assertFalse(memo(alba).showsOn(shop, sharing))
        assertFalse(memo(shop).showsOn(main, sharing))
        assertTrue(memo(alba).isShared(sharing))
        assertFalse(memo(shop).isShared(sharing))
    }

    @Test
    fun theSharedNotebookIsWhatThisCalendarSharesNotAnotherGroups() {
        val sharing = MemoSharing(listOf(setOf(main, alba)))
        assertTrue(MemoNotebook.SHARED.shows(memo(alba), main, sharing))
        assertFalse(MemoNotebook.SHARED.shows(memo(alba), shop, sharing))
        assertTrue(MemoNotebook.SHARED.shows(memo(MemoCalendars.EVERY), shop, sharing))
        assertFalse(MemoNotebook.CALENDAR.shows(memo(alba), shop, sharing))
        assertTrue(MemoNotebook.ALL.shows(memo(alba), shop, sharing))
        assertEquals(setOf(MemoCalendars.EVERY), MemoNotebook.SHARED.newMemoCalendars(alba))
        assertEquals(setOf(alba), MemoNotebook.CALENDAR.newMemoCalendars(alba))
        assertEquals(setOf(alba), MemoNotebook.ALL.newMemoCalendars(alba))
    }

    @Test
    fun groupsAreCleanedAndCalendarsPlaced() {
        val messy = MemoSharing(listOf(setOf(main, alba, "gone"), setOf(alba, shop), setOf("x")))
        assertEquals(MemoSharing(listOf(setOf(main, alba))), messy.cleaned(listOf(main, alba, shop)))
        assertTrue(MemoSharing(listOf(setOf(main))).separate)

        val one = MemoSharing.SEPARATE.place(main, 0).place(alba, 0)
        assertEquals(listOf(setOf(main, alba)), one.groups)
        val two = one.place(shop, 5)
        assertEquals(listOf(setOf(main, alba), setOf(shop)), two.groups)
        assertEquals(listOf(setOf(main), setOf(shop, alba)), two.place(alba, 1).groups)
        assertEquals(listOf(setOf(main, alba)), two.place(shop, -1).groups)
    }
}
