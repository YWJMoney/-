package com.bamnat.app.share

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import com.bamnat.core.time.Today

/**
 * [PROV-04][FEAT-15] The links the user made go in a backup with their keys, so after a move to a new
 * phone the same address, and the calendar feed others subscribed to, keeps following the calendar.
 * A link already on this phone is never dropped for one from the file: the file's takes a free place
 * among its calendar's links, or is left out. Links received from others stay out (SHR-02).
 */
class ShareLinkBackup(
    /** The user's calendars as they are now, read after a restore brought them back. */
    private val calendarIds: () -> List<String>,
    private val storeOf: (String) -> ShareLinkStore,
    private val clock: () -> Instant = Today::instant,
) {
    fun export(): JSONArray = JSONArray().apply {
        for (key in calendarIds().flatMap(ShareLinkKey::all)) {
            val store = storeOf(key)
            store.expire()
            val s = store.link.value ?: continue
            put(JSONObject().put("key", key).put("id", s.id).put("url", s.url).put("token", s.deleteToken)
                .put("expires", s.expiresAt.toString()).put("title", s.title).put("ttl", s.ttlDays).putOpt("from", s.from?.toString())
                .put("auto", store.auto).put("feed", store.feed).put("shows", JSONArray(store.options().shows)))
        }
    }

    /** Takes the file's links in; returns how many. Each is sent again at the next update. */
    fun restore(links: JSONArray): Int {
        val calendars = calendarIds().toSet()
        var taken = 0
        for (i in 0 until links.length()) {
            val o = links.optJSONObject(i) ?: continue
            val key = o.optString("key").takeIf { it.isNotEmpty() } ?: continue
            val calendar = ShareLinkKey.calendar(key)
            if (calendar !in calendars) continue
            val s = read(o) ?: continue
            if (!s.expiresAt.isAfter(clock())) continue
            val slots = ShareLinkKey.all(calendar).map(storeOf)
            val place = slots.firstOrNull { it.link.value?.id == s.id }
                ?: storeOf(key).takeIf { it.link.value == null }
                ?: slots.firstOrNull { it.link.value == null }
                ?: continue
            place.restore(s, o.optBoolean("auto", true), o.optBoolean("feed"),
                ShareOptions.of(o.optJSONArray("shows")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()))
            taken++
        }
        return taken
    }

    /** Null for a part that is not a link this app made: a bad id, or an address that is not that link's page. */
    private fun read(o: JSONObject): ShareLinkState? = runCatching {
        val id = o.getString("id").takeIf { it.matches(ID) } ?: return null
        ShareLinkState(
            id = id,
            url = o.getString("url").takeIf { it.startsWith("https://") && it.substringBefore('?').endsWith("/s/$id") } ?: return null,
            deleteToken = o.getString("token").takeIf { it.isNotEmpty() } ?: return null,
            expiresAt = Instant.parse(o.getString("expires")),
            title = o.optString("title"),
            ttlDays = o.optInt("ttl", 30),
            from = o.optString("from").takeIf { it.isNotEmpty() }?.let(YearMonth::parse),
        )
    }.getOrNull()

    private companion object {
        val ID = Regex("[A-Za-z0-9]{16,32}")
    }
}
