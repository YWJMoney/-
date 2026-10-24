package com.bamnat.app.screenshots

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.bamnat.app.ui.Fmt
import java.io.File
import java.time.YearMonth

/**
 * [CAL-51][VIS-04] 묶음 R's checks on a screen as drawn (1.23 점검 보고서 방식): every piece of text cut at the bottom or side,
 * cut short with …, or broken into a column of single letters, and how many whole weeks of the month show above the
 * bottom bar.
 */
internal object LayoutCheck {
    var DEBUG = false
    /** One text that does not show whole, with what happened to it. */
    data class Cut(val text: String, val how: String) {
        override fun toString() = "‘$text’ $how"
    }

    fun cuts(nodes: List<SemanticsNode>, windowWidth: Int, windowHeight: Int): List<Cut> = buildList {
        for (n in nodes) {
            val b = n.boundsInWindow
            if (b.width < 1f || b.height < 1f || b.bottom <= 0f || b.top >= windowHeight || b.right <= 0f || b.left >= windowWidth) continue
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
            val results = mutableListOf<TextLayoutResult>()
            if (action(results) != true) continue
            for (r in results) {
                val text = r.layoutInput.text.text
                if (text.isBlank()) continue
                val last = r.lineCount - 1
                when {
                    // Two pixels or less is the line's air above and below the letters (a chip's label at 200%, drawn whole).
                    r.didOverflowHeight && (r.multiParagraph.didExceedMaxLines || r.multiParagraph.height - r.size.height > 2f) -> add(Cut(text, "아래가 잘림" + (if (DEBUG) " ${r.size} ${r.multiParagraph.height} ${r.layoutInput.constraints}" else "")))
                    r.isLineEllipsized(last) -> add(Cut(text, "말줄임"))
                    // Not didOverflowWidth: the layout semantics hands back is as wide as its constraints.
                    // A line's width, not its right edge: centred text (a tab's label) is laid out wider than it is drawn.
                    (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > r.size.width + 1f } -> add(Cut(text, "옆이 잘림"))
                    split(r) -> add(Cut(text, "한 글자씩 쪼개짐"))
                }
            }
        }
    }

    /** A word of three or more letters laid out with lines of one letter, like 1.23's "10 / 월 / 근 / 무". */
    private fun split(r: TextLayoutResult): Boolean {
        val text = r.layoutInput.text.text
        if (r.lineCount < 3 || text.count { !it.isWhitespace() } < 3) return false
        val singles = (0 until r.lineCount).count { i -> text.substring(r.getLineStart(i), r.getLineEnd(i)).trim().length <= 1 }
        return singles >= 2
    }

    /** The top of the bottom navigation bar (its tabs), or the window's bottom with a rail or none. */
    fun contentBottom(nodes: List<SemanticsNode>, windowHeight: Int): Float {
        val tabs = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        // A rail's tabs stand one under another at the left edge: the content goes down to the window's bottom.
        if (tabs.map { it.boundsInWindow.left.toInt() }.distinct().size <= 1) return windowHeight.toFloat()
        return tabs.minOfOrNull { it.boundsInWindow.top } ?: windowHeight.toFloat()
    }

    /** Whole weeks of [month] fully on screen above [bottom]: day cells counted by their row. */
    fun weeksShown(nodes: List<SemanticsNode>, month: YearMonth, bottom: Float, windowWidth: Int): Int {
        // The grid's first and last rows carry the days around the month too.
        val titles = (-1..1).flatMap { m -> month.plusMonths(m.toLong()).let { ym -> (1..ym.lengthOfMonth()).map { Fmt.dayTitle(ym.atDay(it)) } } }.toSet()
        val cells = nodes.filter { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { d -> titles.any { d.startsWith(it) } }
        }
        return cells.filter { val b = it.boundsInWindow; b.top >= 0f && b.bottom <= bottom + 1f && b.left >= 0f && b.right <= windowWidth + 1f }
            .groupBy { it.boundsInWindow.top.toInt() / 4 }.count { it.value.size == 7 }
    }

    /** Writes what [cuts] found next to the shots, one line each, for the plan's list. */
    fun report(name: String, cuts: List<Cut>) {
        val out = File("build/screenshots/r-audit/$name.txt").apply { parentFile!!.mkdirs() }
        out.writeText(cuts.joinToString("\n") + "\n")
    }
}
