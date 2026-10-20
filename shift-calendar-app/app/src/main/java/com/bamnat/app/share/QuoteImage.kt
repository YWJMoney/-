package com.bamnat.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.Quote
import com.bamnat.core.money.QuoteVat
import java.io.File
import java.text.DecimalFormat

/**
 * [FLD-11] 견적서 as one picture — items with quantity, unit price and amount, 공급가액, 부가세 and the
 * total, valid-until and a note — drawn like the 거래내역서 ([StatementImage]) and sent through the share
 * sheet. Made on the phone; nothing goes to a server.
 */
object QuoteImage {
    private const val WIDTH = 1080
    private const val PAD = 56f
    private const val ROW = 84f

    private val won = DecimalFormat("#,###")
    private fun w(v: Long) = won.format(v) + "원"

    fun render(q: Quote, sender: String): Bitmap {
        val items = q.items.filter { it.name.isNotBlank() || it.unitPrice > 0 }
        val noteLines = q.note.trim().lines().filter { it.isNotBlank() }.take(4)
        val height = (380f + (items.size + 1) * ROW + 4 * 64f + 80f + noteLines.size * 44f + 140f).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFFFFF.toInt())
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        val line = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 2f }

        text.textSize = 60f; text.typeface = bold
        c.drawText("견적서", PAD, 120f, text)
        text.textSize = 36f; text.typeface = Typeface.DEFAULT; text.color = MUTED
        c.drawText("${q.date.year}.${q.date.monthValue}.${q.date.dayOfMonth}" + (q.client.takeIf { it.isNotBlank() }?.let { " · $it 귀하" } ?: ""), PAD, 180f, text)
        val sub = listOfNotNull(q.title.takeIf { it.isNotBlank() }, sender.takeIf { it.isNotBlank() }?.let { "보낸 사람 $it" }).joinToString(" · ")
        if (sub.isNotEmpty()) c.drawText(ellipsize(sub, text, WIDTH - 2 * PAD), PAD, 228f, text)

        val xName = PAD
        val xQty = WIDTH - PAD - 520f
        val xPrice = WIDTH - PAD - 260f
        val xAmount = WIDTH - PAD
        var y = 310f
        text.textSize = 30f; text.color = MUTED; text.typeface = bold
        c.drawText("품목", xName, y, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("수량", xQty, y, text)
        c.drawText("단가", xPrice, y, text)
        c.drawText("금액", xAmount, y, text)
        text.textAlign = Paint.Align.LEFT
        y += 24f
        c.drawLine(PAD, y, WIDTH - PAD, y, line)
        for (it in items) {
            y += ROW
            text.typeface = Typeface.DEFAULT; text.textSize = 32f; text.color = INK
            c.drawText(ellipsize(it.name.ifBlank { "품목" }, text, xQty - 150f - xName), xName, y - 24f, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(DayRate.trim(it.quantity) + it.unit, xQty, y - 24f, text)
            c.drawText(won.format(it.unitPrice), xPrice, y - 24f, text)
            c.drawText(won.format(it.amount), xAmount, y - 24f, text)
            text.textAlign = Paint.Align.LEFT
            c.drawLine(PAD, y, WIDTH - PAD, y, line)
        }
        y += 72f
        val rows = buildList {
            if (q.vat != QuoteVat.NONE) {
                add("공급가액" to w(q.supply))
                add("부가세 (10%)" to w(q.vatAmount))
            }
            add((if (q.vat == QuoteVat.NONE) "합계" else "합계 (부가세 포함)") to w(q.total))
        }
        for ((i, row) in rows.withIndex()) {
            val last = i == rows.lastIndex
            text.textSize = if (last) 42f else 34f
            text.typeface = if (last) bold else Typeface.DEFAULT
            text.color = INK
            c.drawText(row.first, xQty - 260f, y, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(row.second, xAmount, y, text)
            text.textAlign = Paint.Align.LEFT
            y += 64f
        }
        y += 16f
        text.textSize = 30f; text.typeface = Typeface.DEFAULT; text.color = MUTED
        c.drawText("유효기간 ${q.validUntil.year}.${q.validUntil.monthValue}.${q.validUntil.dayOfMonth}까지" +
            if (q.vat == QuoteVat.NONE) " · 부가세 없음" else "", PAD, y, text)
        for (n in noteLines) {
            y += 44f
            c.drawText(ellipsize(n, text, WIDTH - 2 * PAD), PAD, y, text)
        }
        text.textSize = 26f
        c.drawText("밤낮 달력에서 만든 견적서예요.", PAD, height - 48f, text)
        return bmp
    }

    private fun ellipsize(s: String, paint: Paint, width: Float): String {
        if (paint.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    suspend fun share(context: Context, q: Quote, sender: String) {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, "share/quote").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "quote-${q.date}.png")
            val bmp = render(q, sender)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            file
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "견적서" + (q.client.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "견적서 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private const val INK = 0xFF1B1F2A.toInt()
    private const val MUTED = 0xFF6B7280.toInt()
}
