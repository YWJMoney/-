package com.bamnat.app.company

import android.content.Context
import com.bamnat.core.pay.RateHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [DAILY-16] 결정 D36: the 광장 시세 groups of the last region the user looked at on 임금표, kept on the
 * phone so the pay screen can compare without asking the server again. Only what the server already
 * showed is kept; the user's pay never goes into it or out of the phone.
 */
class RateHintStore(context: Context) {
    private val prefs = context.getSharedPreferences("plaza_rate_hint", Context.MODE_PRIVATE)
    private val _groups = MutableStateFlow(read())
    val groups: StateFlow<List<RateHint.Group>> = _groups.asStateFlow()

    /** Keeps [groups] of one region; a board of every region together says nothing about the user's own. */
    fun keep(region: String, groups: List<RateGroup>) {
        if (region.isBlank() || groups.isEmpty()) return
        val list = groups.take(MAX).map { RateHint.Group(it.region.ifBlank { region }.let { r -> Plaza.REGIONS[r] ?: r }, it.trade, it.unit, it.year, it.n, it.low, it.mid, it.high) }
        prefs.edit().putString(KEY, JSONArray().apply {
            list.forEach { g ->
                put(JSONObject().put("region", g.region).put("trade", g.trade).put("unit", g.unit).put("year", g.year).put("n", g.n)
                    .put("low", g.low).put("mid", g.mid).put("high", g.high))
            }
        }.toString()).apply()
        _groups.value = list
    }

    private fun read(): List<RateHint.Group> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RateHint.Group(o.optString("region"), o.optString("trade"), o.optString("unit"), o.optInt("year"), o.optInt("n"),
                o.optLong("low"), o.optLong("mid"), o.optLong("high"))
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "groups"
        const val MAX = 30
    }
}
