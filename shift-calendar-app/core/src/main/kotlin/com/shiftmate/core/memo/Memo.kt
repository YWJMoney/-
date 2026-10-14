package com.shiftmate.core.memo

import java.time.LocalDate

data class ChecklistItem(val text: String, val checked: Boolean = false)

/**
 * A note. It can belong to a calendar date or stand alone, and can be plain text or a checklist.
 */
data class Memo(
    val id: Long = 0,
    val date: LocalDate? = null,
    val title: String = "",
    val body: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val isChecklist: Boolean = false,
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    /** Set while the memo is in the trash. */
    val deletedAtMillis: Long? = null,
) {
    val inTrash: Boolean get() = deletedAtMillis != null

    /** Calendar memos belong to a date; general memos do not. */
    val isCalendarMemo: Boolean get() = date != null

    /** #tags written anywhere in the memo, without the '#', in order of first use. */
    val tags: List<String>
        get() = (sequenceOf(title, body) + checklist.asSequence().map { it.text })
            .flatMap { TAG.findAll(it).map { m -> m.groupValues[1] } }
            .distinct()
            .toList()

    /** Letters in the text, used for the word count in the editor. */
    val characterCount: Int
        get() = title.length + body.length + checklist.sumOf { it.text.length }

    val isBlank: Boolean
        get() = title.isBlank() && body.isBlank() && checklist.none { it.text.isNotBlank() }

    /** First meaningful line, used for previews and as a fallback title. */
    val preview: String
        get() = when {
            isChecklist -> checklist.filter { it.text.isNotBlank() }
                .joinToString(" · ") { (if (it.checked) "✓ " else "") + it.text }
            else -> body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }

    val checkedCount: Int get() = checklist.count { it.checked && it.text.isNotBlank() }
    val itemCount: Int get() = checklist.count { it.text.isNotBlank() }

    /** Every word of [query] must appear somewhere (title, body, items or tags). */
    fun matches(query: String): Boolean {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.all { w ->
            title.contains(w, ignoreCase = true) ||
                body.contains(w, ignoreCase = true) ||
                checklist.any { it.text.contains(w, ignoreCase = true) }
        }
    }

    companion object {
        private val TAG = Regex("#([\\p{L}\\p{N}_]{1,30})")
    }
}

/**
 * Stores a checklist in one text column. One item per line: the first character is
 * '1' (checked) or '0', and backslash, newline and carriage return are escaped,
 * so any text round-trips exactly.
 */
object ChecklistCodec {
    fun encode(items: List<ChecklistItem>): String =
        items.joinToString("\n") { (if (it.checked) "1" else "0") + escape(it.text) }

    fun decode(raw: String?): List<ChecklistItem> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            ChecklistItem(text = unescape(line.substring(1)), checked = line[0] == '1')
        }
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

/** Memo ordering used everywhere: pinned first, then most recently edited. */
val MemoOrdering: Comparator<Memo> =
    compareByDescending<Memo> { it.pinned }.thenByDescending { it.updatedAtMillis }.thenByDescending { it.id }
