package com.bamnat.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.bamnat.core.work.WatchDays
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.bamnat.core.time.Today

/**
 * [SHFT-08] 오늘 근무 tile: today's shift big in its colour with its times, tomorrow's under it, and the next
 * alarm. Drawn from the watch's own copy, so it works away from the phone; it moves on at midnight.
 */
class ShiftTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { done ->
            // [WEAR-03] 오늘 알람 끄기 tapped on the tile: the phone turns it off and sends the days back.
            if (requestParams.currentState.lastClickableId == ALARM_OFF) {
                val today = Today.date().toString()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    WatchStore.send(applicationContext, WatchDays.ALARM_OFF_PATH, today)
                }
            }
            val zone = Today.zone()
            val nowMs = Today.millis()
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val untilMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - nowMs + 5_000
            // [SHFT-08] Once the alarm shown has gone off, the tile moves on to the next one.
            val untilAlarm = WatchStore.load(this)?.let { WatchDays.alarmAhead(it, now) }
                ?.let { it.atZone(zone).toInstant().toEpochMilli() - nowMs + 5_000 } ?: Long.MAX_VALUE
            done.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES)
                    .setFreshnessIntervalMillis(minOf(untilMidnight, untilAlarm).coerceAtLeast(60_000))
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(now)))
                    .build(),
            )
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { done ->
            done.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())
            "resources"
        }

    private fun layout(now: LocalDateTime): LayoutElementBuilders.LayoutElement {
        val p = WatchStore.load(this)
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        val today = now.toLocalDate()
        col.addContent(text("오늘 · ${WatchText.date(today)}", 13f, GREY))
        val d = p?.let { WatchDays.on(it.days, today) }
        if (p == null || d == null) {
            col.addContent(space(6f))
            col.addContent(text(if (p == null) WatchText.NO_DATA else WatchText.NOT_SENT, 15f, WHITE, lines = 3))
        } else {
            col.addContent(space(4f))
            col.addContent(text(d.name, 24f, readable(d.colorArgb), bold = true))
            if (d.times.isNotEmpty()) col.addContent(text(d.times, 16f, WHITE))
            WatchDays.on(p.days, today.plusDays(1))?.let { t ->
                col.addContent(space(6f))
                col.addContent(text("내일 ${t.line}", 13f, LIGHT))
            }
            WatchText.alarm(p, now)?.let {
                col.addContent(space(4f))
                col.addContent(text(it, 12f, AMBER))
            }
            WatchDays.weekText(p)?.let {
                col.addContent(space(2f))
                col.addContent(text(it, 11f, GREY))
            }
            // [WEAR-03] Turn today's alarm off from the wrist, before bed after a night (I 리뷰 N7 c).
            if (p.alarmToday) {
                col.addContent(space(6f))
                col.addContent(button("오늘 알람 끄기", ALARM_OFF))
            }
        }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build())
                .build())
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(col.build())
            .build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 2) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(lines)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build())
            .build()

    private fun space(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    /** A pill the size of a finger that asks the tile again with [id] as the tapped clickable. */
    private fun button(label: String, id: String) = LayoutElementBuilders.Box.Builder()
        .setHeight(dp(40f))
        .setWidth(androidx.wear.protolayout.DimensionBuilders.wrap())
        .setModifiers(ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
            .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(BUTTON))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build()).build())
            .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).build())
            .build())
        .addContent(text(label, 13f, WHITE, lines = 1))
        .build()

    companion object {
        private const val RESOURCES = "1"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LIGHT = 0xFFD6DAE3.toInt()
        const val GREY = 0xFF9AA3B5.toInt()
        const val AMBER = 0xFFFFC266.toInt()

        const val BUTTON = 0xFF2B3140.toInt()
        private const val ALARM_OFF = "alarm_off"

        /** [WEAR-02] The shift's colour lifted so it reads on the watch's black: the shared rule in core, tested there. */
        fun readable(argbLong: Long): Int = com.bamnat.core.layout.Contrast.onBlack(argbLong).toInt()
    }
}
