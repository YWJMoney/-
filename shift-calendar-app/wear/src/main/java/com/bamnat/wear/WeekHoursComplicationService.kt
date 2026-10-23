package com.bamnat.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload

/**
 * [WEAR-03] 이번 주 근무시간 on a watch face (I 리뷰 N7 a): a ring filling toward 주 52시간 with the hours in it
 * ("38h"), from the week's planned and worked hours the phone sends with the days. Shown as text where the face
 * has no ring.
 */
class WeekHoursComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, 38 * 60, 52 * 60)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val p: WatchPayload = WatchStore.load(this) ?: return null
        if (p.weekMinutes < 0) return null
        return data(request.complicationType, p.weekMinutes, p.weekLimitMinutes)
    }

    private fun data(type: ComplicationType, minutes: Int, limit: Int): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun t(s: String) = PlainComplicationText.Builder(s).build()
        val hours = "${minutes / 60}h"
        val said = t(WatchDays.weekText(WatchPayload(emptyList(), java.time.LocalDateTime.MIN, weekMinutes = minutes, weekLimitMinutes = limit)) ?: hours)
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = minutes.coerceIn(0, limit).toFloat(), min = 0f, max = limit.toFloat(), contentDescription = said,
            ).setText(t(hours)).setTitle(t("이번 주")).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(t(hours), said).setTitle(t("이번 주")).setTapAction(tap).build()
            else -> null
        }
    }
}
