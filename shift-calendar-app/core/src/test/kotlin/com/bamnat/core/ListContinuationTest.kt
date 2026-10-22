package com.bamnat.core

import com.bamnat.core.memo.ListContinuation
import com.bamnat.core.memo.ListContinuation.Edit
import org.junit.Assert.assertEquals
import org.junit.Test

/** [NOTE-04] Numbered and bulleted lists go on by themselves when Enter ends an item. */
class ListContinuationTest {
    /** Types Enter at the end of [text]. */
    private fun enter(text: String): Edit = ListContinuation.apply(Edit(text, text.length), Edit(text + "\n", text.length + 1))

    @Test
    fun numbersCountUp() {
        assertEquals(Edit("1. 우유\n2. ", 9), enter("1. 우유"))
        assertEquals("장보기\n1) 계란\n2) ", enter("장보기\n1) 계란").text)
        assertEquals("1 사과\n2 ", enter("1 사과").text)
        assertEquals("8 배\n9 사과\n10 ", enter("8 배\n9 사과").text)
    }

    @Test
    fun bulletsRepeat() {
        assertEquals("- 빨래\n- ", enter("- 빨래").text)
        assertEquals("  • 안쪽\n  • ", enter("  • 안쪽").text)
        assertEquals("✓ 끝\n☐ ", enter("✓ 끝").text)
    }

    @Test
    fun anEmptyItemEndsTheList() {
        assertEquals(Edit("1. 우유\n", 6), enter("1. 우유\n2. "))
        assertEquals(Edit("- 빨래\n", 5), enter("- 빨래\n- "))
        assertEquals("1 사과\n", enter("1 사과\n2 ").text)
    }

    @Test
    fun plainLinesAndOtherEditsAreLeftAlone() {
        assertEquals("오늘 할 일\n", enter("오늘 할 일").text)
        assertEquals("3\n", enter("3").text)
        assertEquals("2026년\n", enter("2026년").text)
        // A bare number goes on only as a list: phone numbers, amounts and times do not.
        assertEquals("010 1234 5678\n", enter("010 1234 5678").text)
        assertEquals("500 원 커피\n", enter("500 원 커피").text)
        assertEquals("10 30 회의\n", enter("10 30 회의").text)
        // Enter in the middle of an item splits it as usual.
        val before = Edit("1. 우유 사기", 5)
        val after = Edit("1. 우유\n 사기", 6)
        assertEquals(after, ListContinuation.apply(before, after))
        // Pasting several characters is not an Enter.
        val pasted = Edit("1. 우유\n2. 빵", 11)
        assertEquals(pasted, ListContinuation.apply(Edit("1. 우유", 5), pasted))
    }

    /** [NOTE-08] Changes [before] to [after] with the caret at [cursor] and renumbers. */
    private fun renumber(before: String, after: String, cursor: Int): Edit =
        ListContinuation.renumber(Edit(before, before.length), Edit(after, cursor))

    @Test
    fun numbersStayInSequence() {
        // An item put in after "1." (Enter) pushes the ones under it down.
        val typed = enter("1. 우유").let { "1. 우유\n2. \n2. 빵\n3. 잼" to it.cursor }
        assertEquals("1. 우유\n2. \n3. 빵\n4. 잼", renumber("1. 우유\n2. 빵\n3. 잼", typed.first, typed.second).text)
        // Deleting an item pulls the ones under it up, even when the caret lands on the next one.
        assertEquals("1. 우유\n2. 잼", renumber("1. 우유\n2. 빵\n3. 잼", "1. 우유\n3. 잼", 6).text)
        assertEquals("1) 우유\n2) 잼", renumber("1) 우유\n2) 빵\n3) 잼", "1) 우유\n3) 잼", 9).text)
        // Deeper items are passed over and keep their own numbers; a plain line ends the list.
        assertEquals(
            "1. a\n2. 새\n  1. 안\n3. b\n끝\n7. 다른 목록",
            renumber("1. a\n2. b", "1. a\n2. 새\n  1. 안\n2. b\n끝\n7. 다른 목록", 9).text,
        )
        // From 9 to 10 the line grows by one, and the caret on it moves with it.
        val grown = renumber("8. a\n9. b\n10. c", "8. a\n9. x\n9. b\n10. c", 9)
        assertEquals("8. a\n9. x\n10. b\n11. c", grown.text)
        assertEquals(9, grown.cursor)
        val pulled = ListContinuation.renumber(Edit("8. a\n9. b\n10. c", 0), Edit("8. a\n10. c", 8))
        assertEquals(Edit("8. a\n9. c", 7), pulled)
    }

    @Test
    fun renumberingLeavesOtherTextAlone() {
        // Bare numbers, a list that starts at its own number and a caret-only move change nothing.
        assertEquals("1 사과\n5 배", renumber("1 사과", "1 사과\n5 배", 8).text)
        assertEquals("3. 셋\n7. 일곱", renumber("3. 셋", "3. 셋\n7. 일곱", 9).text)
        assertEquals(Edit("1. a\n5. b", 2), ListContinuation.renumber(Edit("1. a\n5. b", 9), Edit("1. a\n5. b", 2)))
        // Typing a number on the caret's own line is left as typed.
        assertEquals("1. a\n5. ", renumber("1. a\n5.", "1. a\n5. ", 8).text)
    }
}
