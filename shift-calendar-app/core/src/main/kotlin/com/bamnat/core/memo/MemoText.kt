package com.bamnat.core.memo

import com.bamnat.core.memo.ListContinuation.Edit
import java.time.LocalDate

/**
 * [NOTE-07] Small changes to a memo's text from a tap or the bar above the keyboard: a check box
 * "☐ " that turns into "☑ " and back when tapped ("- [ ] " and "- [x] " too), and a list mark put on
 * or taken off the caret's line.
 */
object MemoText {
    /** The marks the bar above the keyboard puts on a line. */
    enum class Mark { BULLET, NUMBER, CHECK }

    private val CHECK = Regex("^(\\s*)(☐ |☑ |- \\[ ] |- \\[[xX]] )")
    private val ANY_MARK = Regex("^(\\s*)(\\d{1,3}[.)] |[-•*·※▶☐☑✓○●→] |- \\[[ xX]] )")
    private val NUMBER = Regex("^\\s*(\\d{1,3})([.)]) ")

    /** Where the line holding [offset] starts and ends (the end before its newline). */
    private fun lineAt(text: String, offset: Int): IntRange {
        val at = offset.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', at - 1) + 1
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        return start until end
    }

    /**
     * The text after a tap at [offset], when the tap was on a check box at the start of its line
     * (before or just after the box), else null. The caret goes after the box, so the same tap is not
     * read twice.
     */
    fun toggleCheck(text: String, offset: Int): Edit? {
        val line = lineAt(text, offset)
        val m = CHECK.find(text.substring(line.first, line.last + 1)) ?: return null
        val box = line.first + m.groupValues[1].length
        val mark = m.groupValues[2]
        // A box is one letter ("☐") or three ("[ ]" after "- "); a tap lands before or inside it.
        if (offset < box || offset > box + mark.trimEnd().length) return null
        val swapped = when (mark) {
            "☐ " -> "☑ "
            "☑ " -> "☐ "
            "- [ ] " -> "- [x] "
            else -> "- [ ] "
        }
        val result = text.substring(0, box) + swapped + text.substring(box + mark.length)
        return Edit(result, box + swapped.length)
    }

    /**
     * Puts [mark] on the caret's line, after its leading spaces, or takes it off when the line already
     * has it; a line with another list mark has that mark swapped. A number follows the item above it.
     */
    fun toggleMark(edit: Edit, mark: Mark): Edit {
        val text = edit.text
        val line = lineAt(text, edit.cursor)
        val content = text.substring(line.first, line.last + 1)
        val found = ANY_MARK.find(content)
        val indent = found?.groupValues?.get(1) ?: content.takeWhile { it == ' ' || it == '\t' }
        val old = found?.groupValues?.get(2).orEmpty()
        val has = when (mark) {
            Mark.BULLET -> old == "• "
            Mark.NUMBER -> NUMBER.matches(old)
            Mark.CHECK -> CHECK.matches(old)
        }
        val new = when {
            has -> ""
            mark == Mark.BULLET -> "• "
            mark == Mark.CHECK -> "☐ "
            else -> {
                val above = if (line.first == 0) "" else text.substring(lineAt(text, line.first - 1).first, line.first - 1)
                val m = NUMBER.find(above)
                if (m != null) "${m.groupValues[1].toInt() + 1}${m.groupValues[2]} " else "1. "
            }
        }
        val at = line.first + indent.length
        val result = text.substring(0, at) + new + text.substring(at + old.length)
        val delta = new.length - old.length
        val cursor = when {
            edit.cursor >= at + old.length -> edit.cursor + delta
            else -> at + new.length
        }
        return Edit(result, cursor.coerceIn(0, result.length))
    }

    /** [text] with [inserted] put in place of the selection [start]..[end], the caret after it. */
    fun insert(text: String, start: Int, end: Int, inserted: String): Edit {
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        return Edit(text.substring(0, a) + inserted + text.substring(b), a + inserted.length)
    }

    /** Today's date as the bar writes it: "10월 20일(화)". */
    fun dateStamp(date: LocalDate): String =
        "${date.monthValue}월 ${date.dayOfMonth}일(${"월화수목금토일"[date.dayOfWeek.value - 1]})"
}
