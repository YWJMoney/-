package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.guide.WriteGate
import com.bamnat.core.example.BrowseChoice
import com.bamnat.core.example.WriteKind
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [EASY-01][EASY-02] 우선 둘러볼게요 and its two ways out: 내 정보로 시작 takes the example away and opens the first
 * setup with its kind of work chosen; 예시는 두고 keeps it as a calendar that no longer asks, except to share.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowseFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container

    @Before fun defaults() {
        val end = System.currentTimeMillis() + 60_000
        while (runBlocking { c.scheduleRepository.shiftTypes.first().isEmpty() }) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the first shifts" }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun `off the example a write goes ahead at once`() {
        var ran = false
        WriteGate.request(c, WriteKind.MEMO) { ran = true }
        assertTrue(ran)
        assertNull(WriteGate.pending.value)
    }

    @Test fun `on the example the first write waits, and 계속 둘러보기 lets it and the rest of the day through`() = runBlocking {
        c.example.browse(WorkStyle.SHIFT)
        var ran = 0
        WriteGate.request(c, WriteKind.SHIFT) { ran++ }
        assertEquals(0, ran)
        assertNotNull(WriteGate.pending.value)
        WriteGate.answer(c, BrowseChoice.KEEP_BROWSING, start = {})
        assertEquals(1, ran)
        WriteGate.request(c, WriteKind.MEMO) { ran++ }
        assertEquals("not asked again today", 2, ran)
        // What leaves the phone always asks.
        WriteGate.request(c, WriteKind.SHARE) { ran++ }
        assertEquals(2, ran)
        WriteGate.answer(c, null, start = {})
        c.example.startMine(false)
    }

    @Test fun `내 정보로 시작 takes the example away and starts the setup with its kind of work`() = runBlocking {
        c.example.browse(WorkStyle.DAY)
        c.example.startMine(keepExample = false)
        assertFalse(c.browse.current.browsing)
        assertNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
        assertEquals(WorkStyle.DAY, c.setupDrafts.draft.value?.style)
        c.setupDrafts.clear()
    }

    @Test fun `예시는 두고 keeps it as a calendar that asks only to share`() = runBlocking {
        c.example.browse(WorkStyle.HOURLY)
        c.example.startMine(keepExample = true)
        assertTrue(c.browse.kept)
        assertNotNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        assertEquals(PersonalCalendar.MAIN, c.calendars.active.value)
        c.calendars.setActive(PersonalCalendar.EXAMPLE)
        var ran = false
        WriteGate.request(c, WriteKind.MEMO) { ran = true }
        assertTrue(ran)
        WriteGate.request(c, WriteKind.SHARE) {}
        assertNotNull(WriteGate.pending.value)
        WriteGate.answer(c, null, start = {})
        // Swept on a start only once it is neither shown nor kept.
        c.example.sweep()
        assertNotNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        c.browse.kept = false
        c.example.sweep()
        assertNull(c.calendars.get(PersonalCalendar.EXAMPLE))
        c.setupDrafts.clear()
    }

    @Test fun `editors opened as screens are caught by their route`() {
        assertEquals(WriteKind.PATTERN, WriteGate.kindOf("pattern?weekdays={weekdays}&setup={setup}&calendar={calendar}"))
        assertEquals(WriteKind.MEMO, WriteGate.kindOf("memo/{id}?date={date}&checklist={checklist}&notebook={notebook}"))
        assertEquals(WriteKind.PAY, WriteGate.kindOf("pay?calendar={calendar}"))
        assertEquals(WriteKind.SHARE, WriteGate.kindOf("share_group"))
        assertNull("the trash only shows", WriteGate.kindOf("memo_trash"))
        assertNull(WriteGate.kindOf("calendar"))
        assertNull(WriteGate.kindOf("settings"))
    }
}
