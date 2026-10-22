package com.bamnat.app.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.bamnat.core.report.ReportDoc
import java.io.OutputStream
import java.time.DayOfWeek

/**
 * [PROV-01][PROV-05] Draws a [ReportDoc] on A4 pages with Android's own PDF writer (no library): sizes are
 * PDF points, 1/72 inch, so A4 is 595×842. Korean prints in the phone's system font. Tables carry their
 * header over to the next page; a year's roster fits one landscape page.
 */
object ReportPdf {
    const val A4_SHORT = 595
    const val A4_LONG = 842
    private const val MARGIN = 36f

    /** Where pages go: a PDF file, or bitmaps in the tests. */
    interface Pages {
        fun begin(width: Int, height: Int): Canvas
        fun end()
    }

    /** Writes [doc] as a PDF to [out]; the number of pages. */
    fun write(doc: ReportDoc, out: OutputStream): Int {
        val pdf = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            var number = 0
            val pages = draw(doc, object : Pages {
                override fun begin(width: Int, height: Int): Canvas {
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++number).create())
                    return page!!.canvas
                }
                override fun end() { pdf.finishPage(page) }
            })
            pdf.writeTo(out)
            return pages
        } finally {
            pdf.close()
        }
    }

    /** Draws [doc] page by page into [sink]; the number of pages. */
    fun draw(doc: ReportDoc, sink: Pages): Int = Writer(doc, sink).run()

    private class Writer(val doc: ReportDoc, val sink: Pages) {
        val width = if (doc.landscape) A4_LONG else A4_SHORT
        val height = if (doc.landscape) A4_SHORT else A4_LONG
        val inner = width - 2 * MARGIN
        val bottom = height - MARGIN - 18f
        lateinit var canvas: Canvas
        var y = 0f
        var pages = 0

        val ink = Color.rgb(0x15, 0x19, 0x2B)
        val muted = Color.rgb(0x5B, 0x5F, 0x73)
        val line = Color.rgb(0xD9, 0xD5, 0xCC)
        val shade = Color.rgb(0xF3, 0xF0, 0xEA)
        val sunday = Color.rgb(0xD1, 0x43, 0x43)
        val saturday = Color.rgb(0x3B, 0x5B, 0xDB)

        fun text(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = line }

        fun run(): Int {
            newPage()
            layout(doc.title, text(18f, bold = true), inner).let { draw(it, MARGIN, y); y += it.height + 10f }
            for (b in doc.blocks) block(b)
            endPage()
            return pages
        }

        fun newPage() {
            canvas = sink.begin(width, height)
            pages++
            y = MARGIN
        }

        fun endPage() {
            val p = text(8f, color = muted)
            canvas.drawText(doc.footer, MARGIN, height - MARGIN + 4f, p)
            val n = "$pages"
            canvas.drawText(n, width - MARGIN - p.measureText(n), height - MARGIN + 4f, p)
            sink.end()
        }

        /** Starts a new page when [need] points do not fit on this one. */
        fun room(need: Float): Boolean {
            if (y + need <= bottom) return false
            endPage(); newPage()
            return true
        }

        fun layout(s: String, p: TextPaint, w: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            StaticLayout.Builder.obtain(s, 0, s.length, p, w.toInt().coerceAtLeast(1)).setAlignment(align).setIncludePad(false).build()

        fun draw(l: StaticLayout, x: Float, top: Float) {
            canvas.save(); canvas.translate(x, top); l.draw(canvas); canvas.restore()
        }

        fun block(b: ReportDoc.Block) {
            when (b) {
                is ReportDoc.Heading -> {
                    val l = layout(b.text, text(12.5f, bold = true), inner)
                    room(l.height + 30f)
                    y += 8f
                    draw(l, MARGIN, y); y += l.height + 6f
                }
                is ReportDoc.Paragraph -> {
                    val l = layout(b.text, if (b.small) text(8.5f, color = muted) else text(10f), inner)
                    room(l.height.toFloat())
                    draw(l, MARGIN, y); y += l.height + 6f
                }
                is ReportDoc.Facts -> facts(b)
                is ReportDoc.Table -> table(b)
                is ReportDoc.MonthGrid -> monthGrid(b)
                is ReportDoc.YearGrid -> yearGrid(b)
                is ReportDoc.Legend -> legend(b)
            }
        }

        fun facts(b: ReportDoc.Facts) {
            val half = inner / 2
            b.rows.chunked(2).forEach { pair ->
                val cells = pair.map { (k, v) -> layout(k, text(8f, color = muted), half - 8f) to layout(v, text(10f), half - 8f) }
                val h = cells.maxOf { (k, v) -> k.height + v.height + 2f }
                room(h)
                cells.forEachIndexed { i, (k, v) ->
                    val x = MARGIN + i * half
                    draw(k, x, y); draw(v, x, y + k.height + 2f)
                }
                y += h + 6f
            }
            y += 2f
        }

        fun table(t: ReportDoc.Table) {
            val sum = t.weights.sum()
            val widths = t.weights.map { inner * it / sum }
            val pad = 3f
            fun rowLayouts(cells: List<String>, p: TextPaint) = cells.mapIndexed { i, c ->
                layout(c, p, widths[i] - 2 * pad, if (i in t.right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            }
            fun drawRow(ls: List<StaticLayout>, background: Int?, ruleAbove: Boolean) {
                val h = ls.maxOf { it.height } + 2 * pad
                if (background != null) { fill.color = background; canvas.drawRect(MARGIN, y, MARGIN + inner, y + h, fill) }
                if (ruleAbove) canvas.drawLine(MARGIN, y, MARGIN + inner, y, stroke.apply { strokeWidth = 1.2f; color = muted })
                var x = MARGIN
                ls.forEachIndexed { i, l -> draw(l, x + pad, y + pad); x += widths[i] }
                canvas.drawLine(MARGIN, y + h, MARGIN + inner, y + h, stroke.apply { strokeWidth = 0.6f; color = line })
                y += h
            }
            val header = rowLayouts(t.headers, text(8.5f, bold = true, color = muted))
            val headerHeight = header.maxOf { it.height } + 2 * pad
            room(headerHeight + 24f)
            drawRow(header, shade, ruleAbove = false)
            t.rows.forEach { cells ->
                val ls = rowLayouts(cells, text(9f))
                val h = ls.maxOf { it.height } + 2 * pad
                if (room(h)) drawRow(header, shade, ruleAbove = false)
                drawRow(ls, null, ruleAbove = false)
            }
            t.total?.let { cells ->
                val ls = rowLayouts(cells, text(9.5f, bold = true))
                room(ls.maxOf { it.height } + 2 * pad)
                drawRow(ls, null, ruleAbove = true)
            }
            y += 8f
        }

        fun chip(c: ReportDoc.Chip, x: Float, top: Float, w: Float, h: Float, size: Float) {
            fill.color = c.color or (0xFF shl 24)
            canvas.drawRoundRect(RectF(x, top, x + w, top + h), 3f, 3f, fill)
            val p = text(size, bold = true, color = if (light(c.color)) ink else Color.WHITE)
            val tw = p.measureText(c.text)
            canvas.drawText(c.text, x + (w - tw) / 2, top + h / 2 + size * 0.35f, p)
        }

        fun light(argb: Int): Boolean {
            val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
            return 0.299 * r + 0.587 * g + 0.114 * b > 170
        }

        fun monthGrid(g: ReportDoc.MonthGrid) {
            val first = g.month.atDay(1)
            val lead = first.dayOfWeek.value % 7 // Sunday first
            val weeks = (lead + g.month.lengthOfMonth() + 6) / 7
            val colW = inner / 7
            val headerH = 16f
            // The grid takes what is left of the page above the legend.
            val cellH = ((bottom - y - headerH - 90f) / weeks).coerceIn(56f, 110f)
            room(headerH + weeks * cellH)
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, w ->
                val p = text(9f, bold = true, color = when (i) { 0 -> sunday; 6 -> saturday; else -> muted })
                canvas.drawText(w, MARGIN + i * colW + (colW - p.measureText(w)) / 2, y + 11f, p)
            }
            y += headerH
            for (week in 0 until weeks) for (dow in 0 until 7) {
                val x = MARGIN + dow * colW
                val top = y + week * cellH
                canvas.drawRect(x, top, x + colW, top + cellH, stroke.apply { strokeWidth = 0.6f; color = line })
                val d = week * 7 + dow - lead + 1
                if (d !in 1..g.month.lengthOfMonth()) continue
                val day = g.days[d] ?: ReportDoc.MonthGrid.Day()
                val red = dow == 0 || day.holiday != null
                canvas.drawText("$d", x + 4f, top + 11f, text(9f, bold = true, color = if (red) sunday else if (dow == 6) saturday else ink))
                day.holiday?.let { h ->
                    val p = text(6.5f, color = sunday)
                    canvas.drawText(ellipsize(h, p, colW - 22f), x + 18f, top + 10.5f, p)
                }
                var cy = top + 16f
                day.chips.forEach { c ->
                    if (cy + 15f > top + cellH - 2f) return@forEach
                    chip(c, x + 4f, cy, colW - 8f, 15f, 9f); cy += 18f
                }
                day.note?.let { n ->
                    val p = text(6.5f, color = muted)
                    if (cy + 8f <= top + cellH) canvas.drawText(ellipsize(n, p, colW - 8f), x + 4f, cy + 7f, p)
                }
            }
            y += weeks * cellH + 10f
        }

        fun yearGrid(g: ReportDoc.YearGrid) {
            val labelW = 34f
            val colW = (inner - labelW) / 31
            val rowH = ((bottom - y - 70f) / 13).coerceIn(18f, 34f)
            room(rowH * 13)
            for (d in 1..31) {
                val p = text(7f, color = muted)
                canvas.drawText("$d", MARGIN + labelW + (d - 1) * colW + (colW - p.measureText("$d")) / 2, y + rowH * 0.65f, p)
            }
            for (m in 1..12) {
                val month = java.time.YearMonth.of(g.year, m)
                val top = y + m * rowH
                canvas.drawText("${m}월", MARGIN, top + rowH * 0.65f, text(9f, bold = true))
                for (d in 1..31) {
                    val x = MARGIN + labelW + (d - 1) * colW
                    if (d > month.lengthOfMonth()) {
                        fill.color = shade; canvas.drawRect(x, top, x + colW, top + rowH, fill); continue
                    }
                    val date = month.atDay(d)
                    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                    if (weekend) { fill.color = shade; canvas.drawRect(x, top, x + colW, top + rowH, fill) }
                    canvas.drawRect(x, top, x + colW, top + rowH, stroke.apply { strokeWidth = 0.4f; color = line })
                    g.days[month]?.get(d)?.let { chip(it, x + 1.5f, top + 1.5f, colW - 3f, rowH - 3f, (rowH * 0.42f).coerceAtMost(10f)) }
                    if (date in g.holidays) {
                        fill.color = sunday; canvas.drawCircle(x + colW - 3f, top + 3f, 1.8f, fill)
                    }
                }
            }
            y += rowH * 13 + 10f
        }

        fun legend(b: ReportDoc.Legend) {
            if (b.items.isEmpty()) return
            var x = MARGIN
            room(20f)
            val p = text(9f)
            b.items.forEach { (c, name) ->
                val w = 22f + 6f + p.measureText(name) + 14f
                if (x + w > MARGIN + inner) { x = MARGIN; y += 20f; room(20f) }
                chip(c, x, y, 22f, 14f, 8.5f)
                canvas.drawText(name, x + 28f, y + 10.5f, p)
                x += w
            }
            y += 24f
        }

        fun ellipsize(s: String, p: Paint, w: Float): String {
            if (p.measureText(s) <= w) return s
            var t = s
            while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
            return "$t…"
        }
    }
}
