package com.bamnat.app.imports

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.bamnat.core.imports.DeviceCalendar
import com.bamnat.core.imports.DeviceEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [IMPORT-01] Reads Samsung Calendar (and every other calendar on the phone) through Android's
 * calendar store. Read only: nothing is ever written back, and nothing read leaves the phone.
 */
interface CalendarSource {
    fun allowed(): Boolean
    fun calendars(): List<DeviceCalendar>
    fun events(calendarIds: Set<Long>, from: LocalDate, toInclusive: LocalDate): List<DeviceEvent>
}

class DeviceCalendars(private val context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) : CalendarSource {
    override fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun calendars(): List<DeviceCalendar> {
        if (!allowed()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.VISIBLE,
        )
        return runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(DeviceCalendar(
                            id = c.getLong(0), name = c.getString(1).orEmpty(), account = c.getString(2).orEmpty(),
                            accountType = c.getString(3).orEmpty(), colorArgb = if (c.isNull(4)) null else c.getInt(4).toLong() and 0xFFFFFFFFL,
                        ))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
            // Samsung's own first, then by account and name, so the list reads the same each time.
            .sortedWith(compareBy<DeviceCalendar>({ !it.samsung }, { it.generated }, { it.account }, { it.name }))
    }

    override fun events(calendarIds: Set<Long>, from: LocalDate, toInclusive: LocalDate): List<DeviceEvent> {
        if (!allowed() || calendarIds.isEmpty()) return emptyList()
        val z = zone()
        val begin = from.atStartOfDay(z).toInstant().toEpochMilli()
        val end = toInclusive.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
        )
        val ids = calendarIds.joinToString(",")
        val where = "${CalendarContract.Instances.CALENDAR_ID} IN ($ids) AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}"
        return runCatching {
            context.contentResolver.query(uri, projection, where, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val allDay = c.getInt(5) != 0
                        // All-day events are stored at UTC midnight; timed ones in the phone's zone.
                        val at = { millis: Long -> LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), if (allDay) ZoneOffset.UTC else z) }
                        val start = at(c.getLong(3))
                        add(DeviceEvent(
                            eventId = c.getLong(0), calendarId = c.getLong(1), title = c.getString(2).orEmpty(),
                            begin = start, end = if (c.isNull(4)) start else at(c.getLong(4)), allDay = allDay,
                            location = c.getString(6).orEmpty(), description = c.getString(7).orEmpty(),
                        ))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList()).filter { !it.date.isBefore(from) && !it.date.isAfter(toInclusive) }
    }
}
