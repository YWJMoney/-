package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.imports.DutyCell
import com.bamnat.core.imports.DutyCodes
import com.bamnat.core.model.LeaveKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [ROST-06] Each calendar's code book for 한 달 근무 넣기: what the user said a roster's code means once (병가 → 유급 휴가,
 * M → 미들), so the next month's roster needs no question (결정 D63). On this phone and in the backup, like 모든 조.
 * [ROST-10] The fingerprint of clipboard text the user said no to sits beside it, so the same text is not offered again;
 * never the text itself.
 */
class DutyCodeBook(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _books = MutableStateFlow(read())
    /** Calendar id → (code as [DutyCodes.norm] gives it → what it means). */
    val books: StateFlow<Map<String, Map<String, DutyCell>>> = _books.asStateFlow()

    fun of(calendarId: String): Map<String, DutyCell> = _books.value[calendarId].orEmpty()

    /** Remembers that [code] means [cell] on [calendarId]'s rosters; an unknown cell is never kept. */
    fun learn(calendarId: String, code: String, cell: DutyCell) {
        val key = DutyCodes.norm(code)
        if (key.isEmpty() || cell is DutyCell.Unknown) return
        val next = of(calendarId) + (key to cell)
        prefs.edit().putString(calendarId, encode(next)).apply()
        _books.value = _books.value + (calendarId to next)
    }

    fun forget(calendarId: String, code: String) {
        val next = of(calendarId) - DutyCodes.norm(code)
        prefs.edit().apply { if (next.isEmpty()) remove(calendarId) else putString(calendarId, encode(next)) }.apply()
        _books.value = _books.value + (calendarId to next)
    }

    /** [ROST-10] True when the clipboard text with this fingerprint was offered and turned down. */
    fun declined(fingerprint: Int): Boolean = prefs.getInt(CLIP, 0) == fingerprint

    fun decline(fingerprint: Int) = prefs.edit().putInt(CLIP, fingerprint).apply()

    /** For the backup: calendar id → encoded book. */
    fun export(): Map<String, String> = _books.value.filterValues { it.isNotEmpty() }.mapValues { encode(it.value) }

    fun import(all: Map<String, String>) {
        val clip = prefs.getInt(CLIP, 0)
        prefs.edit().clear().apply {
            all.forEach { (k, v) -> if (decode(v).isNotEmpty()) putString(k, v) }
            if (clip != 0) putInt(CLIP, clip)
        }.apply()
        _books.value = read()
    }

    private fun read(): Map<String, Map<String, DutyCell>> =
        prefs.all.filterKeys { it != CLIP }.mapNotNull { (k, v) -> (v as? String)?.let { k to decode(it) } }.toMap()

    companion object {
        private const val PREFS = "duty_codes"
        private const val CLIP = "#clip"

        /** One cell as a short string: "S12" a kind of work, "O" a day off, "L1.2.0" leave (kind code, quarters, from start). */
        fun encodeCell(c: DutyCell): String? = when (c) {
            is DutyCell.Shift -> "S${c.shiftTypeId}"
            DutyCell.Off -> "O"
            is DutyCell.Leave -> "L${c.kind.code}.${c.quarters}.${if (c.fromStart) 1 else 0}"
            is DutyCell.Unknown -> null
        }

        fun decodeCell(s: String): DutyCell? = when {
            s == "O" -> DutyCell.Off
            s.startsWith("S") -> s.drop(1).toLongOrNull()?.let { DutyCell.Shift(it) }
            s.startsWith("L") -> s.drop(1).split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { (k, q, f) ->
                LeaveKind.fromCode(k)?.takeIf { q in 1..4 }?.let { DutyCell.Leave(it, q, f == 1) }
            }
            else -> null
        }

        fun encode(book: Map<String, DutyCell>): String =
            JSONObject().apply { book.forEach { (k, c) -> encodeCell(c)?.let { put(k, it) } } }.toString()

        fun decode(s: String): Map<String, DutyCell> = runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().mapNotNull { k -> decodeCell(o.optString(k))?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }
}
