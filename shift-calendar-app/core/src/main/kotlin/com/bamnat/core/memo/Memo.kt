package com.bamnat.core.memo

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
    /** [SCHED-01] Set when the memo is a schedule that rings or notifies. */
    val reminder: MemoReminder? = null,
    /** [SCHED-01] The [MemoTime.Found.key] the user last answered about, so it is asked once. */
    val reminderAsked: String? = null,
    /**
     * [NOTE-01][MEMO-01] The calendars this memo belongs to, by id. [MemoCalendars.EVERY] puts it on
     * every calendar (공용 메모장). Empty is a memo written before memos had calendars: it belongs to
     * 내 달력 ([MemoCalendars.MAIN]) alone, and other calendars see it only through [MemoSharing].
     */
    val calendars: Set<String> = emptySet(),
    /** [NOTE-05] How a dated memo comes back ("M25" every month on the 25th, "W2" every Tuesday); empty for once. */
    val repeat: String = "",
) {
    /** [MEMO-01] Written for every calendar, now and later ones too. */
    val onEveryCalendar: Boolean get() = MemoCalendars.EVERY in calendars

    /** [MEMO-01] The calendars it was written on: an old memo with none is 내 달력's. */
    val owners: Set<String> get() = (calendars - MemoCalendars.EVERY).ifEmpty { setOf(MemoCalendars.MAIN) }

    /** [NOTE-01][MEMO-01] Shown on calendar [id], with calendars sharing memos as [sharing] says (none by default). */
    fun showsOn(id: String, sharing: MemoSharing = MemoSharing.SEPARATE): Boolean = sharing.shows(this, id)

    /** [NOTE-02][MEMO-01] In the shared notebook: on every calendar, or seen on more than one. */
    fun isShared(sharing: MemoSharing = MemoSharing.SEPARATE): Boolean = sharing.isShared(this)

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
 * [NOTE-02] Which memos the memo tab lists once there is more than one calendar: the calendar on
 * screen's own and shared ones, the shared notebook (공용 메모장), or every memo.
 */
enum class MemoNotebook(val label: String) {
    CALENDAR("이 달력"),
    SHARED("공용 메모장"),
    ALL("모든 메모");

    /** [MEMO-01] 공용 메모장 is the shared memos this calendar sees, not another group's. */
    fun shows(memo: Memo, calendar: String, sharing: MemoSharing = MemoSharing.SEPARATE): Boolean = when (this) {
        CALENDAR -> memo.showsOn(calendar, sharing)
        SHARED -> memo.isShared(sharing) && memo.showsOn(calendar, sharing)
        ALL -> true
    }

    /** [MEMO-01] The calendars a memo written in this notebook starts on: the calendar it is written on, or every one. */
    fun newMemoCalendars(calendar: String): Set<String> = if (this == SHARED) setOf(MemoCalendars.EVERY) else setOf(calendar)
}

/**
 * [MEMO-01][MEMO-02] Which calendars see each other's memos (설정 › 메모 공유). By default none do
 * (달력마다 따로): a memo shows on the calendars it was written on, or on every calendar when it was
 * written for all. Each group is a set of calendars that read as one memo book; a calendar is in one
 * group at most.
 */
data class MemoSharing(val groups: List<Set<String>> = emptyList()) {
    /** True for 달력마다 따로. */
    val separate: Boolean get() = groups.none { it.size > 1 }

    /** The calendars whose memos calendar [id] sees, itself included. */
    fun circleOf(id: String): Set<String> = groups.firstOrNull { id in it && it.size > 1 }?.plus(id) ?: setOf(id)

    fun shows(memo: Memo, calendar: String): Boolean {
        if (memo.onEveryCalendar) return true
        val circle = circleOf(calendar)
        return memo.owners.any { it in circle }
    }

    /** Seen on more than one calendar: written for all or for several, or by a calendar in a group. */
    fun isShared(memo: Memo): Boolean =
        memo.onEveryCalendar || memo.owners.size > 1 || memo.owners.any { circleOf(it).size > 1 }

    /**
     * The groups that still mean something with calendars [known]: gone calendars dropped, each
     * calendar kept in its first group only, and groups of fewer than two left out.
     */
    fun cleaned(known: Collection<String>): MemoSharing {
        val taken = HashSet<String>()
        val next = groups.map { g -> g.filter { it in known && taken.add(it) }.toSet() }.filter { it.size > 1 }
        return MemoSharing(next)
    }

    /** Calendar [id] moved into group [index] (a new group past the end), or out of every group with -1. */
    fun place(id: String, index: Int): MemoSharing {
        val without = groups.map { it - id }
        val next = when {
            index < 0 -> without
            index < without.size -> without.mapIndexed { i, g -> if (i == index) g + id else g }
            else -> without + listOf(setOf(id))
        }
        return MemoSharing(next.filter { it.isNotEmpty() })
    }

    companion object {
        val SEPARATE = MemoSharing()
    }
}

/**
 * [NOTE-01] A memo's calendars in one text column: ",id1,id2," so a LIKE finds one. [MEMO-01] Empty
 * is an old memo of 내 달력's; [EVERY] is a memo for every calendar.
 */
object MemoCalendars {
    /** [MEMO-01] The main calendar's id (PersonalCalendar.MAIN in the app). */
    const val MAIN = "main"

    /** [MEMO-01] Written for every calendar (공용 메모장), stored as ",*,". */
    const val EVERY = "*"

    fun encode(ids: Set<String>): String = if (ids.isEmpty()) "" else ids.sorted().joinToString(",", prefix = ",", postfix = ",")

    fun decode(raw: String?): Set<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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
