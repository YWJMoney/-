package com.bamnat.app.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.bamnat.core.schedule.ShiftEvent
import com.bamnat.core.schedule.ShiftEvents
import java.time.ZoneId

/**
 * [FEAT-07] A calendar of the app's own in the phone's calendar store, one per calendar of the user's
 * that is written there, which Samsung and Google Calendar show next to the others. It is a local calendar
 * (Android's ACCOUNT_TYPE_LOCAL): bound to no account and never synced to a server, so nothing leaves the
 * phone. The app writes it as its sync adapter, so calendar apps show it read only; turning it off
 * deletes it with its events.
 */
class PhoneCalendar(private val context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    data class Result(val inserted: Int, val updated: Int, val deleted: Int)

    fun allowed(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Makes the phone's events for [calendarId] match [events]; creates the calendar the first time. */
    fun write(calendarId: String, displayName: String, color: Int, events: List<ShiftEvent>): Result {
        val id = find(calendarId) ?: create(calendarId, displayName, color)
        rename(id, displayName, color)
        val rows = existing(id)
        val keep = rows.groupBy { it.key }
        // Two rows with one key (an interrupted write) keep the first; the rest go.
        val duplicates = keep.values.flatMap { it.drop(1) }.map { it.rowId }
        val plan = ShiftEvents.plan(keep.mapValues { it.value.first().fingerprint }, events)
        val ops = ArrayList<ContentProviderOperation>()
        val rowOf = keep.mapValues { it.value.first().rowId }
        (plan.delete.mapNotNull { rowOf[it] } + duplicates).forEach { row ->
            ops += ContentProviderOperation.newDelete(adapter(ContentUris.withAppendedId(Events.CONTENT_URI, row))).build()
        }
        plan.update.forEach { e ->
            ops += ContentProviderOperation.newUpdate(adapter(ContentUris.withAppendedId(Events.CONTENT_URI, rowOf.getValue(e.key)))).withValues(values(id, e)).build()
        }
        plan.insert.forEach { e -> ops += ContentProviderOperation.newInsert(adapter(Events.CONTENT_URI)).withValues(values(id, e)).build() }
        // The calendar store takes a few hundred at a time on some phones.
        ops.chunked(BATCH).forEach { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(it)) }
        return Result(plan.insert.size, plan.update.size, plan.delete.size + duplicates.size)
    }

    /** Deletes [calendarId]'s calendar and its events; false when there was none. */
    fun remove(calendarId: String): Boolean {
        val id = find(calendarId) ?: return false
        return context.contentResolver.delete(adapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null) > 0
    }

    /** The app's calendars on the phone, by the user's calendar id each one is for. */
    fun written(): Set<String> = context.contentResolver.query(
        adapter(Calendars.CONTENT_URI), arrayOf(Calendars.NAME),
        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
    )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.removePrefix(PREFIX)?.let(::add) } }.orEmpty()

    private fun find(calendarId: String): Long? = context.contentResolver.query(
        adapter(Calendars.CONTENT_URI), arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?",
        arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL, PREFIX + calendarId), null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun create(calendarId: String, displayName: String, color: Int): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, PREFIX + calendarId)
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, color)
            // Read only in calendar apps: the app rewrites these events whenever the shifts change.
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, zone().id)
        }
        val uri = context.contentResolver.insert(adapter(Calendars.CONTENT_URI), values) ?: error("calendar not created")
        return ContentUris.parseId(uri)
    }

    private fun rename(id: Long, displayName: String, color: Int) {
        context.contentResolver.update(adapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, color)
        }, null, null)
    }

    private data class Row(val rowId: Long, val key: String, val fingerprint: String)

    private fun existing(calendar: Long): List<Row> = context.contentResolver.query(
        adapter(Events.CONTENT_URI), arrayOf(Events._ID, Events.SYNC_DATA1, Events.SYNC_DATA2),
        "${Events.CALENDAR_ID} = ?", arrayOf(calendar.toString()), null,
    )?.use { c -> buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) } }.orEmpty()

    private fun values(calendar: Long, e: ShiftEvent) = ContentValues().apply {
        val z = zone()
        put(Events.CALENDAR_ID, calendar)
        put(Events.TITLE, e.title)
        put(Events.DESCRIPTION, e.description)
        if (e.allDay) {
            // All-day events are stored at UTC midnight, as the calendar store asks.
            put(Events.DTSTART, e.date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
            put(Events.DTEND, e.date.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
        } else {
            put(Events.DTSTART, e.start!!.atZone(z).toInstant().toEpochMilli())
            put(Events.DTEND, e.end!!.atZone(z).toInstant().toEpochMilli())
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, z.id)
        }
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        put(Events.HAS_ALARM, 0)
        put(Events.SYNC_DATA1, e.key)
        put(Events.SYNC_DATA2, e.fingerprint)
    }

    private fun adapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    companion object {
        /** The account the calendars sit under in calendar apps' lists. */
        const val ACCOUNT = "밤낮"
        private const val PREFIX = "bamnat:"
        private const val BATCH = 200

        /** [IMPORT-01] The app's own calendars, which 캘린더에서 가져오기 leaves out. */
        fun isOwn(account: String, accountType: String): Boolean = account == ACCOUNT && accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
    }
}
