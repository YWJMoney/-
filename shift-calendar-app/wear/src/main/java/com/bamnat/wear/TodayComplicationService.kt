package com.bamnat.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import com.bamnat.core.work.WatchDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [SHFT-08] 오늘 근무 on a watch face: the short text is today's short name ("주", "야", "휴") with "근무" over it,
 * the long text the whole line ("주간 09:00–18:00") with tomorrow's in the title. Tapping opens the app.
 * Sent as a timeline of the next days, so it turns over at midnight without the midnight alarm (kept for the tile).
 */
class TodayComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val today = LocalDate.now()
        val day = WatchDay(today, "주간", "주", 0xFF2F80ED, LocalTime.of(9, 0), LocalTime.of(18, 0))
        return data(type, ComplicationDays.today(listOf(day), today, ZoneId.systemDefault()))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val days = WatchStore.load(this)?.days
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = data(request.complicationType, ComplicationDays.today(days, today, zone)) ?: return null
        val ahead = days?.let { ComplicationDays.ahead(it, today, zone) }.orEmpty().mapNotNull { d ->
            data(request.complicationType, d)?.let { TimelineEntry(TimeInterval(d.start, d.end), it) }
        }
        return ComplicationDataTimeline(now, ahead)
    }

    private fun data(type: ComplicationType, d: ComplicationDay): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun t(s: String) = PlainComplicationText.Builder(s).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(t(d.shortText), t(d.description))
                .setTitle(t(d.shortTitle)).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(t(d.longText), t(d.description))
                .setTitle(t(d.longTitle)).setTapAction(tap).build()
            else -> null
        }
    }
}
