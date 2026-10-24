package com.bamnat.app.share

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * [SHR-14] 우리 조 모아 보기: a 6-letter code on the share server that holds only a list of link ids.
 * Each coworker makes their own link as before and adds it with the code, so nobody's schedule goes
 * anywhere it did not already go; the code ends with its longest link.
 */
data class ShareGroup(val code: String, val expiresAt: Instant?, val links: List<String>) {
    companion object {
        /** The letters the server makes codes from: no 0/O or 1/I, which read alike. */
        private val CODE = Regex("^[A-HJ-NP-Z2-9]{6}$")

        /** The code in what the user typed or pasted ("abc 234" too), or null. */
        fun code(text: String): String? = text.uppercase().filter { it.isLetterOrDigit() }.takeIf { CODE.matches(it) }

        fun parse(o: JSONObject): ShareGroup {
            val links = o.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val ends = o.optString("expiresAt").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return ShareGroup(o.getString("code"), ends, links)
        }

        /** The words the user gets with the code, to send to the 조. */
        fun invite(code: String) =
            "밤낮 ‘우리 조 모아 보기’ 코드: $code\n근무표 공유 링크를 만든 뒤 설정 › 데이터 › 우리 조 모아 보기에서 이 코드를 넣어 주세요."
    }
}

/** [SHR-14] The group this phone is in, and which of its own links it put in (null: only looking). */
class ShareGroupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("share_group", Context.MODE_PRIVATE)
    private val _code = MutableStateFlow(prefs.getString("code", null))
    val code: StateFlow<String?> = _code.asStateFlow()
    val ownKey: String? get() = prefs.getString("own", null)

    fun save(code: String, ownKey: String?) {
        prefs.edit().putString("code", code).putString("own", ownKey).apply()
        _code.value = code
    }

    fun clear() {
        prefs.edit().clear().apply()
        _code.value = null
    }
}

/** [SHR-14] The group side by side: one row per link, a column per day. */
object ShareGroupBoard {
    data class Member(val id: String, val name: String, val mine: Boolean, val schedule: SharedSchedule?)
    data class Row(val id: String, val name: String, val mine: Boolean, val cells: List<String?>)

    /** Each member's next [days] days from [from]: the shift's name, 쉬는 날, or null outside what the link shows. */
    fun rows(members: List<Member>, from: LocalDate, days: Int = 7): List<Row> = members.map { m ->
        Row(m.id, m.name, m.mine, (0 until days).map { i ->
            val date = from.plusDays(i.toLong())
            m.schedule?.takeIf { it.covers(date) }?.let { ReceivedChanges.label(it, date) }
        })
    }

    /** The days in the next [days] when everyone whose link covers the day is off, at least two of them. */
    fun restTogether(members: List<Member>, from: LocalDate, days: Int = 7): List<LocalDate> = (0 until days).mapNotNull { i ->
        val date = from.plusDays(i.toLong())
        val rests = members.mapNotNull { it.schedule?.isRest(date) }
        date.takeIf { rests.size >= 2 && rests.all { it } }
    }

    /** A name for a link nobody has named: the sharer's title, else 동료 1, 2, …. */
    fun name(title: String?, index: Int): String = title?.trim()?.takeIf { it.isNotEmpty() } ?: "동료 ${index + 1}"
}
