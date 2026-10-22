package com.bamnat.app.calendar

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.core.schedule.ShiftEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * [FEAT-07] Writing the shifts to a local calendar of the app's own: made once, events kept in step by
 * key, written as the sync adapter, deleted when turned off, and left out of 캘린더에서 가져오기.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneCalendarTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private lateinit var store: FakeCalendarStore

    @Before fun setUp() {
        store = Robolectric.setupContentProvider(FakeCalendarStore::class.java, CalendarContract.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private fun event(day: Int, title: String = "주간") = LocalDate.of(2026, 10, day).let {
        ShiftEvent("$it", title, it.atTime(9, 0), it.atTime(18, 0), it, null)
    }

    @Test fun writesALocalCalendarAndKeepsItInStep() {
        val phone = PhoneCalendar(app)
        assertEquals(PhoneCalendar.Result(3, 0, 0), phone.write("main", "밤낮 근무", 0xFFC2A36B.toInt(), listOf(event(1), event(2), event(3))))
        val cal = store.calendars.values.single()
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, cal.getAsString(CalendarContract.Calendars.ACCOUNT_TYPE))
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, cal.getAsInteger(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL))
        assertTrue("동기화 어댑터로 써요", store.adapterCalls.all { it })
        // The same again changes nothing; a changed day is rewritten, a day gone is removed.
        assertEquals(PhoneCalendar.Result(0, 0, 0), phone.write("main", "밤낮 근무", 0, listOf(event(1), event(2), event(3))))
        assertEquals(PhoneCalendar.Result(1, 1, 1), phone.write("main", "밤낮 근무", 0, listOf(event(1), event(2, "야간"), event(4))))
        assertEquals(listOf("주간", "야간", "주간"), store.events.values.sortedBy { it.getAsLong(CalendarContract.Events.DTSTART) }.map { it.getAsString(CalendarContract.Events.TITLE) })
        assertEquals(1, store.calendars.size)
        assertEquals(setOf("main"), phone.written())
        assertTrue(phone.remove("main"))
        assertTrue(store.calendars.isEmpty())
        assertFalse(phone.remove("main"))
    }

    @Test fun theImportDoesNotOfferTheAppsOwnCalendar() {
        PhoneCalendar(app).write("main", "밤낮 근무", 0, listOf(event(1)))
        store.calendars[99] = ContentValues().apply {
            put(CalendarContract.Calendars._ID, 99L); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "내 캘린더")
            put(CalendarContract.Calendars.ACCOUNT_NAME, "me@example.com"); put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
        }
        assertEquals(listOf("내 캘린더"), com.bamnat.app.imports.DeviceCalendars(app).calendars().map { it.name })
    }

    @Test fun withoutThePermissionNothingIsWritten() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertFalse(PhoneCalendar(app).allowed())
    }
}

/** Calendars and events in memory, enough for [PhoneCalendar] and the import's calendar list. */
class FakeCalendarStore : ContentProvider() {
    val calendars = linkedMapOf<Long, ContentValues>()
    val events = linkedMapOf<Long, ContentValues>()
    val adapterCalls = mutableListOf<Boolean>()
    private var next = 1L

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    private fun table(uri: Uri) = if (uri.path.orEmpty().startsWith("/calendars")) calendars else events
    private fun id(uri: Uri) = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        adapterCalls += uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"
        val id = next++
        table(uri)[id] = ContentValues(values).apply { put("_id", id) }
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        adapterCalls += uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"
        val row = table(uri)[id(uri)] ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        adapterCalls += uri.getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"
        val t = table(uri)
        val id = id(uri)
        if (t.remove(id) == null) return 0
        // Deleting a calendar deletes its events, as the real store does.
        if (t === calendars) events.values.removeAll { it.getAsLong(CalendarContract.Events.CALENDAR_ID) == id }
        return 1
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf("_id")
        // "a = ? AND b = ?": every column named before a ? must equal its argument.
        val wanted = Regex("""(\w+) = \?""").findAll(selection.orEmpty()).map { it.groupValues[1] }.toList().zip(selectionArgs.orEmpty().toList())
        val out = MatrixCursor(cols)
        table(uri).values.filter { row -> wanted.all { (k, v) -> row.getAsString(k) == v } }.forEach { row ->
            out.addRow(cols.map { row.get(it) })
        }
        return out
    }
}
