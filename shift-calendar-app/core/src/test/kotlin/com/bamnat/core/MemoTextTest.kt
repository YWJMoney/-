package com.bamnat.core

import com.bamnat.core.memo.ListContinuation.Edit
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoRepeat
import com.bamnat.core.memo.MemoText
import com.bamnat.core.memo.MemoText.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [NOTE-05] Repeating memos and [NOTE-07] check boxes and the bar above the keyboard. */
class MemoTextTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun weeklyMonthlyAndYearlyRepeats() {
        val tuesday = Memo(date = d("2026-10-20"), repeat = MemoRepeat.code(MemoRepeat.Kind.WEEK, d("2026-10-20")))
        assertEquals("W2", tuesday.repeat)
        assertTrue(MemoRepeat.occursOn(tuesday, d("2026-10-27")))
        assertFalse(MemoRepeat.occursOn(tuesday, d("2026-10-28")))
        // Never before the memo's own date.
        assertFalse(MemoRepeat.occursOn(tuesday, d("2026-10-13")))

        val rent = Memo(date = d("2026-10-25"), repeat = "M25")
        assertTrue(MemoRepeat.occursOn(rent, d("2027-02-25")))
        assertFalse(MemoRepeat.occursOn(rent, d("2027-02-24")))

        val birthday = Memo(date = d("2026-01-01"), repeat = "Y0101")
        assertTrue(MemoRepeat.occursOn(birthday, d("2030-01-01")))
        assertFalse(MemoRepeat.occursOn(birthday, d("2030-02-01")))

        assertEquals("매주 화요일", MemoRepeat.label("W2"))
        assertEquals("매달 25일", MemoRepeat.label("M25"))
        assertEquals("매년 1월 1일", MemoRepeat.label("Y0101"))
        assertEquals("", MemoRepeat.label("Q9"))
    }

    @Test
    fun aDayShorterMonthsLackFallsOnTheirLastDay() {
        val end = Memo(date = d("2026-01-31"), repeat = "M31")
        assertTrue(MemoRepeat.occursOn(end, d("2026-02-28")))
        assertTrue(MemoRepeat.occursOn(end, d("2026-04-30")))
        assertFalse(MemoRepeat.occursOn(end, d("2026-04-29")))
        assertTrue(MemoRepeat.occursOn(end, d("2026-05-31")))
        assertFalse(MemoRepeat.occursOn(end, d("2026-05-30")))
        val leap = Memo(date = d("2028-02-29"), repeat = "Y0229")
        assertTrue(MemoRepeat.occursOn(leap, d("2029-02-28")))
        assertTrue(MemoRepeat.occursOn(leap, d("2032-02-29")))
        assertFalse(MemoRepeat.occursOn(leap, d("2032-02-28")))
        assertEquals(listOf(d("2026-02-28"), d("2026-03-31")), MemoRepeat.datesIn(end, d("2026-02-01"), d("2026-03-31")))
        val once = Memo(id = 2, date = d("2026-02-10"))
        assertEquals(
            listOf(d("2026-02-28"), d("2026-03-31"), d("2026-02-10")),
            MemoRepeat.expand(listOf(end, once), d("2026-02-01"), d("2026-03-31")).map { it.date },
        )
        // A memo that does not repeat is on its date only.
        assertFalse(MemoRepeat.occursOn(Memo(date = d("2026-10-20")), d("2026-10-27")))
        assertFalse(MemoRepeat.occursOn(Memo(repeat = "W2"), d("2026-10-27")))
    }

    @Test
    fun tappingABoxTicksIt() {
        assertEquals(Edit("장보기\n☑ 우유", 6), MemoText.toggleCheck("장보기\n☐ 우유", 4))
        assertEquals(Edit("장보기\n☐ 우유", 6), MemoText.toggleCheck("장보기\n☑ 우유", 5))
        assertEquals("  - [x] 빵", MemoText.toggleCheck("  - [ ] 빵", 3)?.text)
        assertEquals("- [ ] 빵", MemoText.toggleCheck("- [X] 빵", 0)?.text)
        // A tap on the words, or on a line without a box, is only a caret move.
        assertNull(MemoText.toggleCheck("☐ 우유", 3))
        assertNull(MemoText.toggleCheck("우유", 0))
    }

    @Test
    fun theBarPutsMarksOnAndOff() {
        assertEquals(Edit("• 우유", 4), MemoText.toggleMark(Edit("우유", 2), Mark.BULLET))
        assertEquals(Edit("우유", 2), MemoText.toggleMark(Edit("• 우유", 4), Mark.BULLET))
        assertEquals("1. 우유\n2. 빵", MemoText.toggleMark(Edit("1. 우유\n빵", 7), Mark.NUMBER).text)
        assertEquals("1) a\n2) b", MemoText.toggleMark(Edit("1) a\nb", 6), Mark.NUMBER).text)
        assertEquals("  ☐ 빵", MemoText.toggleMark(Edit("  - 빵", 0), Mark.CHECK).text)
        assertEquals(Edit("  ☐ 빵", 4), MemoText.toggleMark(Edit("  - 빵", 0), Mark.CHECK))
        assertEquals("빵", MemoText.toggleMark(Edit("☑ 빵", 3), Mark.CHECK).text)
        assertEquals(Edit("오늘 10월 20일(화)", 13), MemoText.insert("오늘 ", 3, 3, MemoText.dateStamp(d("2026-10-20"))))
    }
}
