package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ExtraReview
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** [EXTRA-05] Old records that need a look once after the upgrade. */
class ExtraReviewTest {
    private val day = ShiftType(1, "주간", "주", 0xFF000000, LocalTime.of(7, 0), LocalTime.of(19, 0), breakMinutes = 60, breakStart = LocalTime.of(12, 0))
    private val night = ShiftType(2, "야간", "야", 0xFF000000, LocalTime.of(19, 0), LocalTime.of(7, 0), breakMinutes = 60, breakStart = LocalTime.of(2, 0))
    private val off = ShiftType(3, "휴무", "휴", 0xFF000000, null, null, isOff = true)
    private val types = listOf(day, night, off)
    private val pattern = WorkPattern.Rotation(LocalDate.of(2026, 11, 1), listOf(day.id, day.id, off.id, off.id, night.id, night.id, off.id, off.id))
    private val chip = ExtraEntry(ExtraKind.COVER_ID, minutes = 120)

    @Test
    fun `a day off changed into a shift with a cover chip is found and becomes a cover of that shift`() {
        val swapped = DayEntry(LocalDate.of(2026, 11, 3), override = ShiftOverride.Shift(night.id), extras = listOf(chip))
        // On a working day the chip is real extra work; a swap without a chip is a roster change.
        val workDay = DayEntry(LocalDate.of(2026, 11, 1), override = ShiftOverride.Shift(night.id), extras = listOf(chip))
        val plainSwap = DayEntry(LocalDate.of(2026, 11, 4), override = ShiftOverride.Shift(day.id))
        val found = ExtraReview.shiftSwapCovers(types, listOf(pattern), listOf(plainSwap, workDay, swapped))
        assertEquals(listOf(swapped), found)

        val fixed = ExtraReview.convert(swapped, night)
        assertEquals(ShiftOverride.None, fixed.override)
        assertEquals(listOf(ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(19, 0), LocalTime.of(7, 0), 60, LocalTime.of(2, 0), coveredShiftId = night.id)), fixed.extras)
        val resolved = ScheduleResolver(types, pattern, listOf(fixed)).resolve(fixed.date)
        assertTrue(resolved.isRestDay)
        assertEquals(0, resolved.scheduledMinutes)
        assertEquals(11 * 60, resolved.extraMinutes)
    }

    @Test
    fun `the day's own times win over the shift's when converting`() {
        val swapped = DayEntry(LocalDate.of(2026, 11, 3), override = ShiftOverride.Shift(night.id),
            customStart = LocalTime.of(19, 0), customEnd = LocalTime.of(3, 0), customBreakMinutes = 0, extras = listOf(chip))
        val fixed = ExtraReview.convert(swapped, night)
        assertEquals(ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(19, 0), LocalTime.of(3, 0), 0, null, coveredShiftId = night.id), fixed.extras.single())
        assertEquals(null, fixed.customStart)
    }
}
