package com.bamnat.core

import com.bamnat.core.memo.ChecklistCodec
import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.memo.MemoOrdering
import com.bamnat.core.schedule.PatternPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoAndPresetTest {
    @Test
    fun checklistRoundTripsAnyText() {
        val items = listOf(
            ChecklistItem("유니폼 챙기기", true),
            ChecklistItem("줄\n바꿈", false),
            ChecklistItem("역슬래시 \\n 그대로", true),
            ChecklistItem("", false),
            ChecklistItem("\r\n끝\\", false),
        )
        assertEquals(items, ChecklistCodec.decode(ChecklistCodec.encode(items)))
        assertEquals(emptyList<ChecklistItem>(), ChecklistCodec.decode(""))
        assertEquals(emptyList<ChecklistItem>(), ChecklistCodec.decode(null))
    }

    @Test
    fun memoSearchAndPreview() {
        val memo = Memo(title = "인수인계", body = "\n  3번 병동 확인\n둘째 줄")
        assertTrue(memo.matches("병동"))
        assertTrue(memo.matches("인수"))
        assertTrue(memo.matches("  "))
        assertFalse(memo.matches("없음"))
        assertEquals("3번 병동 확인", memo.preview)

        val list = Memo(isChecklist = true, checklist = listOf(ChecklistItem("A", true), ChecklistItem("B")))
        assertTrue(list.matches("b"))
        assertEquals("✓ A · B", list.preview)
        assertEquals(1, list.checkedCount)
        assertEquals(2, list.itemCount)
        assertTrue(Memo().isBlank)
        assertFalse(list.isBlank)
    }

    @Test
    fun pinnedMemosSortFirstThenNewest() {
        val a = Memo(id = 1, updatedAtMillis = 300)
        val b = Memo(id = 2, updatedAtMillis = 100, pinned = true)
        val c = Memo(id = 3, updatedAtMillis = 200)
        assertEquals(listOf(2L, 1L, 3L), listOf(a, b, c).sortedWith(MemoOrdering).map { it.id })
    }

    @Test
    fun presetsMapToUserShiftTypes() {
        val preset = PatternPresets.all.first { it.codes == "DDEENNOO" }
        val steps = PatternPresets.toSteps(preset, Fixtures.all)
        assertEquals(listOf(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L), steps)

        // Without an off type and without an evening type, those steps become plain days off.
        val limited = Fixtures.all.filter { !it.isOff && it.id != 2L }
        assertEquals(listOf(1L, 1L, null, null, 3L, 3L, null, null), PatternPresets.toSteps(preset, limited))
        assertTrue(PatternPresets.all.all { p -> p.codes.all { it in "DENXBO" } })
    }
}
