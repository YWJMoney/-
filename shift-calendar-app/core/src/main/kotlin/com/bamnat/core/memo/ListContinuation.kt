package com.bamnat.core.memo

/**
 * [NOTE-04] Lists that go on by themselves while typing a memo. When Enter ends a line that starts
 * like a list item, the new line starts with the next mark: "1. " → "2. ", "1) " → "2) ", "1 " → "2 ",
 * and "- ", "• ", "* ", "· ", "※ ", "▶ ", "☐ " repeat as they are. Enter on an item with nothing written
 * after its mark ends the list: the mark goes and the line is left empty, as note apps do.
 */
object ListContinuation {
    /** A memo's text and where the caret is in it. */
    data class Edit(val text: String, val cursor: Int)

    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})([.)]?)(\\s+)")
    private val BULLET = Regex("^(\\s*)([-•*·※▶☐✓○●→])(\\s+)")

    /**
     * What the text becomes after the user's change from [before] to [after]: [after] itself, unless
     * the change was a single Enter typed at the end of a list item.
     */
    fun apply(before: Edit, after: Edit): Edit {
        val typed = after.text.length - before.text.length
        if (typed != 1) return after
        val at = after.cursor - 1
        if (at < 0 || at >= after.text.length || after.text[at] != '\n') return after
        // Only a plain insertion of the newline: everything else stayed where it was.
        if (after.text.removeRange(at, at + 1) != before.text) return after
        val lineStart = after.text.lastIndexOf('\n', at - 1) + 1
        val line = after.text.substring(lineStart, at)
        // Enter in the middle of a line splits it as usual; only the line's end goes on with the list.
        val rest = after.text.substring(at + 1).substringBefore('\n')
        if (rest.isNotBlank()) return after
        if (endsList(after.text, lineStart, line)) {
            // An empty item: the list ends here, the mark is taken away and no new item is added.
            val text = after.text.removeRange(lineStart, at + 1)
            return Edit(text, lineStart)
        }
        markOf(line) ?: return after
        if (!bareNumberIsList(after.text, lineStart, line)) return after
        val next = nextMark(line) ?: return after
        val text = after.text.substring(0, at + 1) + next + after.text.substring(at + 1)
        return Edit(text, at + 1 + next.length)
    }

    /**
     * An item with only its mark: "2. " or "- ", or "2 " right under "1 …" (a lone number on its own
     * is a line like any other).
     */
    private fun endsList(text: String, lineStart: Int, line: String): Boolean {
        NUMBERED.find(line)?.let { m ->
            if (line.substring(m.value.length).isNotBlank()) return false
            if (m.groupValues[3].isNotEmpty()) return true
            if (lineStart == 0) return false
            val above = text.substring(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)
            return nextMark(above)?.trimEnd() == m.value.trimEnd()
        }
        if (line.isBlank()) return false
        val b = BULLET.find(line) ?: return false
        return line.substring(b.value.length).isBlank()
    }

    /**
     * A number with no "." or ")" is a list only as "1 …" or right under the number before it, so a
     * phone number ("010 1234 5678"), an amount ("500 원") or a time ("10 30 회의") stays a plain line.
     */
    private fun bareNumberIsList(text: String, lineStart: Int, line: String): Boolean {
        val m = NUMBERED.find(line) ?: return true
        if (m.groupValues[3].isNotEmpty()) return true
        val digits = m.groupValues[2]
        if (digits.startsWith("0")) return false
        if (digits == "1") return true
        if (lineStart == 0) return false
        val above = text.substring(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)
        val prev = NUMBERED.find(above) ?: return false
        return prev.groupValues[3].isEmpty() && prev.groupValues[2].toIntOrNull() == digits.toInt() - 1
    }

    /** The list mark a line starts with, spaces included ("  2. "), or null for a plain line. */
    fun markOf(line: String): String? {
        NUMBERED.find(line)?.let { m ->
            // "1 " counts only with text after it ("3 apples" goes on, a lone "3" does not start a list).
            if (m.groupValues[3].isEmpty() && line.length <= m.value.length) return null
            return m.value
        }
        return BULLET.find(line)?.value
    }

    /** The mark the next line starts with: the number one up, the bullet the same. */
    fun nextMark(line: String): String? {
        NUMBERED.find(line)?.let { m ->
            if (m.groupValues[3].isEmpty() && line.length <= m.value.length) return null
            val n = m.groupValues[2].toInt() + 1
            return m.groupValues[1] + n + m.groupValues[3] + m.groupValues[4]
        }
        return BULLET.find(line)?.value?.replace("✓", "☐")
    }
}
