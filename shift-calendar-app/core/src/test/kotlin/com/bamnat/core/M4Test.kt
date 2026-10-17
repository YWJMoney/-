package com.bamnat.core

import com.bamnat.core.backup.BackupCrypto
import com.bamnat.core.model.DayChanges
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** M4: rest checks, day change history, leave carry-over and expiry, encrypted backups. */
class M4Test {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun days(steps: List<Long>, vararg entries: DayEntry): List<com.bamnat.core.schedule.ResolvedDay> {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(monday, steps), entries.toList())
        return (0L until 14L).map { r.resolve(monday.plusDays(it)) }
    }

    @Test
    fun `night then day within a day, short rest, backward rotation and night streaks`() {
        val n = Fixtures.night.id; val d = Fixtures.day.id; val e = Fixtures.evening.id; val o = Fixtures.off.id
        // 야 야 야 야 주 오 주 휴 …: 4 nights, a day shift 2 hours after the last night, then 오후 → 주간.
        val issues = RestCheck.check(days(listOf(n, n, n, n, d, e, d, o, o, o, o, o, o, o)))
        val kinds = issues.map { it.date.dayOfMonth to it.kind }
        assertTrue(kinds.toString(), (5 to RestCheck.Kind.NIGHT_STREAK) in kinds)
        assertTrue(kinds.toString(), (6 to RestCheck.Kind.AFTER_NIGHT) in kinds)
        // 오후 14–22 then 주간 09:00 the next day: 11 hours off, but the start moves earlier.
        assertTrue(kinds.toString(), (8 to RestCheck.Kind.BACKWARD) in kinds)
        assertTrue(issues.first { it.kind == RestCheck.Kind.AFTER_NIGHT }.text.contains("2시간"))
        // A plain 주주주주주휴휴 week is fine.
        assertTrue(RestCheck.check(days(List(5) { d } + List(2) { o })).isEmpty())
    }

    @Test
    fun `overtime shortens the rest before the next shift`() {
        val d = Fixtures.day.id; val o = Fixtures.off.id
        // 주간 09–18 + 잔업 6시간 → 24:00, next 주간 09:00: 9 hours off.
        val issues = RestCheck.check(days(List(5) { d } + List(2) { o }, DayEntry(monday, overtimeMinutes = 360, extraWork = ExtraWork.OVERTIME)))
        assertEquals(listOf(RestCheck.Kind.SHORT_REST), issues.map { it.kind })
        assertEquals(monday.plusDays(1), issues.single().date)
    }

    @Test
    fun `day changes read as words`() {
        val names = mapOf(Fixtures.day.id to "주간", Fixtures.night.id to "야간")
        val before = DayEntry(monday, override = ShiftOverride.Shift(Fixtures.day.id))
        val after = before.copy(override = ShiftOverride.Shift(Fixtures.night.id), overtimeMinutes = 120, extraWork = ExtraWork.OVERTIME)
        assertEquals("근무 → 야간, 잔업 2시간", DayChanges.describe(before, after, { names.getValue(it) }))
        assertEquals("조출 2시간", DayChanges.describe(null, DayEntry(monday, overtimeMinutes = 120, extraWork = ExtraWork.OVERTIME), { "" }, { "조출" }))
        assertEquals("연차", DayChanges.describe(null, DayEntry(monday, leave = Leave()), { "" }))
        assertEquals("바꾼 내용 모두 지움", DayChanges.describe(after, DayEntry(monday), { "" }))
        assertEquals("시간 08:00–17:00", DayChanges.describe(null, DayEntry(monday, customStart = LocalTime.of(8, 0), customEnd = LocalTime.of(17, 0)), { "" }))
        assertNull(DayChanges.describe(before, before, { "" }))
    }

    @Test
    fun `carried leave adds to the year and lapsing days show from November`() {
        val r = ScheduleResolver(Fixtures.all, WorkPattern.Rotation(LocalDate.of(2026, 1, 5), List(5) { Fixtures.day.id } + List(2) { Fixtures.off.id }),
            listOf(DayEntry(LocalDate.of(2026, 3, 2), leave = Leave()), DayEntry(LocalDate.of(2026, 3, 3), leave = Leave(quarters = 2))))
        val b = LeaveBook.balance(2026, 15.0, LocalDate.of(2026, 11, 2), r::resolve, carried = 3.0)
        assertEquals(18.0, b.granted!!, 0.0)
        assertEquals(1.5, b.used, 0.0)
        assertEquals(16.5, b.remaining!!, 0.0)
        assertEquals(16.5, b.expiring(LocalDate.of(2026, 11, 2))!!, 0.0)
        assertNull(b.expiring(LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDate.of(2026, 12, 31), b.expiresOn)
    }

    @Test
    fun `encrypted backup opens only with the password`() {
        val plain = "{\"format\":\"bamnat-backup\"}".toByteArray()
        val sealed = BackupCrypto.seal(plain, "밤낮1234".toCharArray(), iterations = 1_000)
        assertTrue(!sealed.data.contentEquals(plain))
        assertArrayEquals(plain, BackupCrypto.open(sealed, "밤낮1234".toCharArray()))
        val wrong = runCatching { BackupCrypto.open(sealed, "틀림".toCharArray()) }.exceptionOrNull()
        assertTrue(wrong is BackupCrypto.WrongPassword)
    }

    @Test
    fun leaveNoticeFallsOnNovemberAndDecemberFirst() {
        val at = { y: Int, m: Int, d: Int, h: Int -> java.time.LocalDateTime.of(y, m, d, h, 0) }
        assertEquals(at(2026, 11, 1, 9), LeaveBook.nextNotice(at(2026, 10, 17, 12)))
        assertEquals(at(2026, 12, 1, 9), LeaveBook.nextNotice(at(2026, 11, 1, 9)))
        assertEquals(at(2027, 11, 1, 9), LeaveBook.nextNotice(at(2026, 12, 1, 10)))
    }
}
