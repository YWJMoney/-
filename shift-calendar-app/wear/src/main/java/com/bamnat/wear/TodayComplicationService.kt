package com.bamnat.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.bamnat.core.work.WatchDay
import com.bamnat.core.work.WatchDays
import java.time.LocalDate

/**
 * [SHFT-08] 오늘 근무 on a watch face: the short text is today's short name ("주", "야", "휴") with "근무" over it,
 * the long text the whole line ("주간 09:00–18:00") with tomorrow's in the title. Tapping opens the app.
 */
class TodayComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, WatchDay(LocalDate.now(), "주간", "주", 0xFF2F80ED, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0)), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val p = WatchStore.load(this)
        val today = LocalDate.now()
        return data(request.complicationType, p?.let { WatchDays.on(it.days, today) }, p?.let { WatchDays.on(it.days, today.plusDays(1)) })
    }

    private fun data(type: ComplicationType, d: WatchDay?, tomorrow: WatchDay?): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun t(s: String) = PlainComplicationText.Builder(s).build()
        val description = t(d?.let { "오늘 ${it.line}" } ?: WatchText.NO_DATA)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(t(d?.label ?: "-"), description)
                .setTitle(t(if (d == null) "밤낮" else "오늘")).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(t(d?.line ?: WatchText.NO_DATA), description)
                .setTitle(t(tomorrow?.let { "내일 ${it.line}" } ?: "오늘 근무")).setTapAction(tap).build()
            else -> null
        }
    }
}
