package com.bamnat.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.bamnat.core.money.JobStatement
import java.io.File
import java.text.DecimalFormat

/**
 * [FLD-05] 거래내역서: one client's jobs in a month drawn as one picture — dates, work, amounts, the
 * 3.3% held back, what was paid and what is left — to send when asking to be paid. Made on the
 * phone like [MonthImage] and sent through the share sheet; nothing goes to a server.
 */
object StatementImage {
    private const val WIDTH = 1080
    private const val PAD = 56f
    private const val ROW = 84f

    private val won = DecimalFormat("#,###")
    private fun w(v: Long) = won.format(v) + "원"

    fun render(s: JobStatement.Statement, sender: String): Bitmap {
        val withholding = s.withheld > 0
        val height = (360f + (s.lines.size + 1) * ROW + 6 * 64f + 120f).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        val line = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 2f }

        text.textSize = 60f; text.typeface = bold
        c.drawText("거래내역서", PAD, 120f, text)
        text.textSize = 36f; text.typeface = Typeface.DEFAULT; text.color = MUTED
        c.drawText("${s.month.year}년 ${s.month.monthValue}월 · ${s.client} 귀하", PAD, 180f, text)
        if (sender.isNotBlank()) c.drawText("보낸 사람 $sender", PAD, 228f, text)

        // Columns: date, work, amount, paid.
        val xDate = PAD
        val xWork = PAD + 190f
        val xAmount = WIDTH - PAD - 220f
        val xPaid = WIDTH - PAD
        var y = 300f
        text.textSize = 30f; text.color = MUTED; text.typeface = bold
        c.drawText("날짜", xDate, y, text)
        c.drawText("내용", xWork, y, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("금액", xAmount, y, text)
        c.drawText("입금", xPaid, y, text)
        text.textAlign = Paint.Align.LEFT
        y += 24f
        c.drawLine(PAD, y, WIDTH - PAD, y, line)
        for (l in s.lines) {
            y += ROW
            text.typeface = Typeface.DEFAULT; text.textSize = 32f; text.color = INK
            val date = if (l.lastDate != l.date) "${l.date.monthValue}/${l.date.dayOfMonth}~${l.lastDate.dayOfMonth}" else "${l.date.monthValue}/${l.date.dayOfMonth}"
            c.drawText(date, xDate, y - 24f, text)
            c.drawText(ellipsize(l.title, text, xAmount - 230f - xWork), xWork, y - 24f, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(w(l.amount), xAmount, y - 24f, text)
            text.color = if (l.unpaid == 0L) PAID else OWED
            c.drawText(if (l.unpaid == 0L) "완료" else "미입금", xPaid, y - 24f, text)
            text.textAlign = Paint.Align.LEFT
            c.drawLine(PAD, y, WIDTH - PAD, y, line)
        }
        y += 72f
        val rows = buildList {
            add("합계" to w(s.amount))
            if (withholding) {
                add("3.3% 원천징수" to "−" + w(s.withheld))
                add("실지급액" to w(s.payable))
            }
            add("입금된 돈" to w(s.received))
            add("남은 돈" to w(s.unpaid))
        }
        for ((i, row) in rows.withIndex()) {
            val last = i == rows.lastIndex
            text.textSize = if (last) 40f else 34f
            text.typeface = if (last) bold else Typeface.DEFAULT
            text.color = if (last && s.unpaid > 0) OWED else INK
            c.drawText(row.first, xWork, y, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(row.second, xPaid, y, text)
            text.textAlign = Paint.Align.LEFT
            y += 64f
        }
        text.textSize = 26f; text.typeface = Typeface.DEFAULT; text.color = MUTED
        c.drawText("밤낮 달력에서 만든 내역이에요.", PAD, height - 48f, text)
        return bmp
    }

    private fun ellipsize(s: String, paint: Paint, width: Float): String {
        if (paint.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    suspend fun share(context: Context, s: JobStatement.Statement, sender: String) {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, "share/statement").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "statement-${s.month}.png")
            val bmp = render(s, sender)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${s.month.year}년 ${s.month.monthValue}월 거래내역서 (${s.client})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "거래내역서 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private const val INK = 0xFF1B1F2A.toInt()
    private const val MUTED = 0xFF6B7280.toInt()
    private const val PAID = 0xFF12805C.toInt()
    private const val OWED = 0xFFD14343.toInt()
}
