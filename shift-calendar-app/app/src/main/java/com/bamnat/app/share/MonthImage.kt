package com.bamnat.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.CalendarGrid
import java.io.File
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * A month of shifts drawn as one picture for family and coworkers [FEAT-06, MKT-09]. No server and
 * no account: the image goes through the phone's share sheet. Memos are never included.
 */
object MonthImage {
    private const val WIDTH = 1080
    private const val PAD = 48f
    private const val HEADER = 190f
    private const val WEEKDAYS = 70f
    private const val CELL_H = 170f

    fun render(snapshot: ScheduleSnapshot, month: YearMonth, weekStart: DayOfWeek): Bitmap {
        val weeks = CalendarGrid.monthWeeks(month, weekStart)
        val used = weeks.flatten().filter { YearMonth.from(it) == month }
            .flatMap { d -> snapshot.resolver.resolve(d).let { listOfNotNull(it.shiftType, it.second?.type) } }.distinctBy { it.id }
        val legendRows = (used.size + 1) / 2
        val height = (HEADER + WEEKDAYS + weeks.size * CELL_H + 40f + legendRows * 64f + 80f).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1F2A.toInt() }
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        text.textSize = 64f; text.typeface = bold
        c.drawText("${month.year}년 ${month.monthValue}월 근무표", PAD, 110f, text)
        text.textSize = 32f; text.typeface = Typeface.DEFAULT; text.color = 0xFF6B7280.toInt()
        c.drawText("밤낮 교대 달력", PAD, 160f, text)

        val cellW = (WIDTH - 2 * PAD) / 7
        val days = (0 until 7).map { weekStart.plus(it.toLong()) }
        text.textSize = 32f; text.textAlign = Paint.Align.CENTER
        days.forEachIndexed { i, d ->
            text.color = colorFor(d, holiday = false)
            c.drawText(d.getDisplayName(TextStyle.SHORT, Locale.KOREAN), PAD + cellW * i + cellW / 2, HEADER + 45f, text)
        }

        val line = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 2f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        weeks.forEachIndexed { w, week ->
            val top = HEADER + WEEKDAYS + w * CELL_H
            c.drawLine(PAD, top, WIDTH - PAD, top, line)
            week.forEachIndexed { i, date ->
                if (YearMonth.from(date) != month) return@forEachIndexed
                val left = PAD + cellW * i
                val day = snapshot.resolver.resolve(date)
                val holiday = KoreanHolidays.nameOf(date) != null
                text.textSize = 34f; text.typeface = bold; text.textAlign = Paint.Align.LEFT
                text.color = colorFor(date.dayOfWeek, holiday)
                c.drawText(date.dayOfMonth.toString(), left + 12f, top + 44f, text)
                val leave = day.leave
                val mark = day.extraWork?.short ?: leave?.takeIf { !it.isFullDay }?.short
                if (mark != null) {
                    text.textSize = 26f; text.textAlign = Paint.Align.RIGHT
                    text.color = if (day.extraWork != null) 0xFFD6336C.toInt() else 0xFF7048E8.toInt()
                    c.drawText(mark, left + cellW - 12f, top + 42f, text)
                }
                val box = RectF(left + 8f, top + 62f, left + cellW - 8f, top + CELL_H - 14f)
                val second = day.second
                if (second != null && leave?.isFullDay != true) {
                    // [FEAT-02] Two shifts split the box.
                    val first = day.shiftType
                    val halves = listOfNotNull(first, second.type)
                    val w = (box.width() - 4f * (halves.size - 1)) / halves.size
                    halves.forEachIndexed { k, t ->
                        val b = RectF(box.left + k * (w + 4f), box.top, box.left + k * (w + 4f) + w, box.bottom)
                        fill.color = if (t.isOff) 0xFFF1F3F5.toInt() else (t.colorArgb or 0xFF000000).toInt()
                        c.drawRoundRect(b, 14f, 14f, fill)
                        text.textSize = if (t.label.length > 1 || halves.size > 1) 28f else 44f
                        text.typeface = bold; text.textAlign = Paint.Align.CENTER
                        text.color = if (t.isOff) 0xFF6B7280.toInt() else 0xFFFFFFFF.toInt()
                        c.drawText(t.label, b.centerX(), b.centerY() + text.textSize / 3, text)
                    }
                    return@forEachIndexed
                }
                val type = day.shiftType ?: return@forEachIndexed
                if (leave?.isFullDay == true) {
                    // [FEAT-10] A whole day of leave shows 연차 instead of the shift.
                    fill.color = 0xFF7048E8.toInt()
                    c.drawRoundRect(box, 18f, 18f, fill)
                    text.textSize = 34f; text.typeface = bold; text.textAlign = Paint.Align.CENTER; text.color = 0xFFFFFFFF.toInt()
                    c.drawText(when { leave.kind.annual -> "연차"; leave.kind.paid -> "휴가"; else -> "무급" }, box.centerX(), box.centerY() + text.textSize / 3, text)
                    return@forEachIndexed
                }
                fill.color = if (type.isOff) 0xFFF1F3F5.toInt() else (type.colorArgb or 0xFF000000).toInt()
                c.drawRoundRect(box, 18f, 18f, fill)
                text.textSize = if (type.label.length > 1) 34f else 44f
                text.typeface = bold; text.textAlign = Paint.Align.CENTER
                text.color = if (type.isOff) 0xFF6B7280.toInt() else 0xFFFFFFFF.toInt()
                c.drawText(type.label, box.centerX(), box.centerY() + text.textSize / 3, text)
            }
        }

        var y = HEADER + WEEKDAYS + weeks.size * CELL_H + 70f
        text.textAlign = Paint.Align.LEFT
        used.forEachIndexed { i, type ->
            val x = PAD + (i % 2) * ((WIDTH - 2 * PAD) / 2)
            if (i > 0 && i % 2 == 0) y += 64f
            fill.color = if (type.isOff) 0xFFDDE1E6.toInt() else (type.colorArgb or 0xFF000000).toInt()
            c.drawRoundRect(RectF(x, y - 34f, x + 40f, y + 6f), 8f, 8f, fill)
            text.textSize = 32f; text.typeface = Typeface.DEFAULT; text.color = 0xFF1B1F2A.toInt()
            val s = type.start
            val e = type.end
            val times = if (s != null && e != null) " %02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute) else ""
            c.drawText("${type.name}$times", x + 56f, y, text)
        }
        return bmp
    }

    private fun colorFor(d: DayOfWeek, holiday: Boolean): Int = when {
        holiday || d == DayOfWeek.SUNDAY -> 0xFFE5484D.toInt()
        d == DayOfWeek.SATURDAY -> 0xFF2F80ED.toInt()
        else -> 0xFF1B1F2A.toInt()
    }

    /** Writes the image to the share cache and opens the share sheet. */
    suspend fun share(context: Context, snapshot: ScheduleSnapshot, month: YearMonth, weekStart: DayOfWeek) {
        // Drawing and compressing a full-size image off the main thread keeps the tap from stuttering.
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "bamnat-$month.png")
            val bmp = render(snapshot, month, weekStart)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${month.year}년 ${month.monthValue}월 근무표")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "근무표 공유"))
    }
}
