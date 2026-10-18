package com.bamnat.core

import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.ConsentPlan
import com.bamnat.core.consent.SleepDevice
import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoReminder
import com.bamnat.core.memo.MemoSchedules
import com.bamnat.core.memo.MemoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [ONBOARD-01][ONBOARD-02][SCHED-01] First-start consent pages and memos that are schedules. */
class OnboardingScheduleTest {
    /** Sunday 18 October 2026, noon. */
    private val now = LocalDateTime.of(2026, 10, 18, 12, 0)
    private val all = ConsentItem.entries.toSet()

    @Test
    fun firstStartShowsEveryItemThePhoneCanUseInOrder() {
        assertEquals(ConsentItem.entries, ConsentPlan.firstStart(all))
        // A build without the calendar permission does not ask about it.
        assertEquals(
            listOf(ConsentItem.NOTIFICATIONS, ConsentItem.ALARMS, ConsentItem.SLEEP),
            ConsentPlan.firstStart(all - ConsentItem.CALENDAR),
        )
    }

    @Test
    fun aPhoneSetUpBeforeIsAskedOnlyAboutWhatIsNewAndNotAllowed() {
        // Never through the pages: what the app asked before counts as told.
        assertEquals(listOf(ConsentItem.CALENDAR), ConsentPlan.newSince(null, all, granted = setOf(ConsentItem.NOTIFICATIONS)))
        assertEquals(emptyList<ConsentItem>(), ConsentPlan.newSince(null, all, granted = setOf(ConsentItem.CALENDAR)))
        assertEquals(emptyList<ConsentItem>(), ConsentPlan.newSince(null, all - ConsentItem.CALENDAR, granted = emptySet()))
        // Through the pages already: nothing again.
        assertEquals(emptyList<ConsentItem>(), ConsentPlan.newSince(all, all, granted = emptySet()))
    }

    @Test
    fun storedKeysRoundTripAndSkipUnknownOnes() {
        val stored = ConsentPlan.encode(listOf(ConsentItem.SLEEP, ConsentItem.NOTIFICATIONS))
        assertEquals(setOf("sleep", "notifications"), stored)
        assertEquals(setOf(ConsentItem.SLEEP, ConsentItem.NOTIFICATIONS), ConsentPlan.decode(stored + "from_a_newer_app"))
        assertNull(ConsentPlan.decode(null))
    }

    @Test
    fun onlyAWatchWearerIsAskedForMeasuredSleep() {
        assertTrue(SleepDevice.WEARS.asksHealth)
        assertFalse(SleepDevice.NONE.asksHealth)
    }

    private fun at(text: String, memoDate: LocalDate? = null) = MemoTime.find(text, now, memoDate)?.at

    @Test
    fun findsTheTimesPeopleWrite() {
        assertEquals(LocalDateTime.of(2026, 10, 19, 15, 0), at("내일 오후 3시 치과"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 30), at("10월 20일 14:30 회의"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), at("모레 오전 9시 반 은행"))
        assertEquals(LocalDateTime.of(2026, 10, 23, 19, 30), at("금요일 저녁 7시 30분 약속"))
        assertEquals(LocalDateTime.of(2026, 10, 26, 10, 0), at("다음주 월요일 10시 면접"))
        assertEquals(LocalDateTime.of(2026, 10, 25, 20, 0), at("25일 저녁 8시 동창회"))
        assertEquals(LocalDateTime.of(2026, 11, 2, 13, 0), at("11/2 1시 건강검진"))
        // "밤 12시" is the midnight that ends the day.
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), at("내일 밤 12시 택배"))
        // No date: the next such time, today if it is still ahead.
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0), at("4시 미용실"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 11, 0), at("11시 병원"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 6, 0), at("새벽 6시 출발"))
    }

    @Test
    fun aCalendarMemoUsesItsOwnDate() {
        assertEquals(LocalDateTime.of(2026, 10, 30, 9, 0), at("오전 9시 교육", memoDate = LocalDate.of(2026, 10, 30)))
        // A day already past has nothing left to ring for.
        assertNull(at("오전 9시 교육", memoDate = LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun hoursAndDatesThatAreNotATimeAreLeftAlone() {
        assertNull(at("2시간 일찍 출근"))
        assertNull(at("내일 장보기"))
        assertNull(at("3일간 교육"))
        assertNull(at("오늘 오전 9시 회의")) // already past today
        assertNull(at("오전 15시"))
        assertNull(at("12:75 이상한 시각"))
    }

    @Test
    fun thePhraseAndTheKeyNameWhatWasFound() {
        val found = MemoTime.find(Memo(title = "치과", checklist = listOf(ChecklistItem("내일 오후 3시"))), now)!!
        assertEquals("내일 오후 3시", found.phrase)
        assertEquals("2026-10-19T15:00", found.key)
    }

    @Test
    fun theNextScheduleAndWhatIsDueAtIt() {
        val first = Memo(id = 1, reminder = MemoReminder(LocalDateTime.of(2026, 10, 19, 15, 0), ring = true, notify = false))
        val same = Memo(id = 2, reminder = MemoReminder(LocalDateTime.of(2026, 10, 19, 15, 0), ring = false, notify = true))
        val later = Memo(id = 3, reminder = MemoReminder(LocalDateTime.of(2026, 10, 20, 9, 0), ring = false, notify = true))
        val trashed = Memo(id = 4, deletedAtMillis = 1, reminder = MemoReminder(LocalDateTime.of(2026, 10, 18, 13, 0), true, true))
        val memos = listOf(later, first, same, trashed, Memo(id = 5))
        assertEquals(LocalDateTime.of(2026, 10, 19, 15, 0), MemoSchedules.next(memos, now))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 0), MemoSchedules.next(memos, LocalDateTime.of(2026, 10, 19, 15, 0)))
        assertNull(MemoSchedules.next(memos, LocalDateTime.of(2026, 10, 20, 9, 0)))
        assertEquals(listOf(1L, 2L), MemoSchedules.dueAt(memos, LocalDateTime.of(2026, 10, 19, 15, 0, 20)).map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aScheduleRingsOrNotifies() {
        MemoReminder(now, ring = false, notify = false)
    }
}
