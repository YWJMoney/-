package com.bamnat.app.ui.shifts

import com.bamnat.core.model.WorkPattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [SET-04] When saving a rotation asks "언제부터 바꿀까요?" (ux-review 1 P9). */
class PatternSaveTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val steps = listOf(1L, 1L, 2L, 2L, null, null)

    @Test fun aTeamMoveOnARotationWithPastDaysIsAsked() {
        // 4조 2교대 since last month; the same order from another step is another team.
        val existing = WorkPattern.Rotation(LocalDate.of(2026, 10, 1), steps)
        val moved = WorkPattern.Rotation(today, steps, anchorDate = today.minusDays(2))
        assertFalse(savesWithoutAsking(existing, moved, today))
    }

    @Test fun aFixOnARotationWithNoPastDaysIsSaved() {
        assertTrue(savesWithoutAsking(WorkPattern.Rotation(today, steps), WorkPattern.Rotation(today.plusDays(1), steps), today))
        assertTrue(savesWithoutAsking(WorkPattern.Rotation(today.plusDays(3), steps), WorkPattern.Rotation(today, steps), today))
        // Another order is always asked.
        assertFalse(savesWithoutAsking(WorkPattern.Rotation(today, steps), WorkPattern.Rotation(today, steps.reversed()), today))
    }
}
