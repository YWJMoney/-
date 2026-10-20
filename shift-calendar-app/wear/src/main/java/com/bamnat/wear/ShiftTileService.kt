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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SHFT-08] 오늘 근무 tile: today's shift big in its colour with its times, tomorrow's under it, and the next
 * alarm. Drawn from the watch's own copy, so it works away from the phone; it moves on at midnight.
 */
class ShiftTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { done ->
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            val untilMidnight = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - System.currentTimeMillis() + 5_000
            done.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES)
                    .setFreshnessIntervalMillis(untilMidnight.coerceAtLeast(60_000))
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

    companion object {
        private const val RESOURCES = "1"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LIGHT = 0xFFD6DAE3.toInt()
        const val GREY = 0xFF9AA3B5.toInt()
        const val AMBER = 0xFFFFC266.toInt()

        /** The shift's colour lifted so it reads on the watch's black. */
        fun readable(argbLong: Long): Int {
            val c = argbLong.toInt()
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            if (lum >= 110) return c or (0xFF shl 24)
            fun lift(v: Int) = (v + (255 - v) * 0.45).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
        }
    }
}
