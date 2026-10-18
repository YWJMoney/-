package com.bamnat.core.pay

/** [PAYS-05] One line of text recognised in a photo, with its box in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * [PAYS-05] Text recognition returns a payslip table in blocks, often a column at a time, so a name and its
 * amount end up far apart. This puts lines whose middles sit at the same height back on one row, left to
 * right, which is how the payslip reads: "기본급 2,508,000 연장근로수당 288,000".
 */
object OcrRows {
    fun join(lines: List<OcrLine>): String {
        if (lines.isEmpty()) return ""
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            // Same row when the middle is within half a line of the row's middle.
            if (row != null && kotlin.math.abs(line.centerY - row.map { it.centerY }.average()) <= minOf(line.height, row.minOf { it.height }) / 2.0) {
                row += line
            } else rows += mutableListOf(line)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }
}
