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
}
