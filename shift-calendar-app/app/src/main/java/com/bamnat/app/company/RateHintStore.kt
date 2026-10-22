package com.bamnat.app.company

import android.content.Context
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.RateHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [DAILY-16] 결정 D36: the 광장 시세 groups the user looked at on 임금표, kept on the phone so the pay screen can
 * compare without asking the server again, and where the user stands: the region they picked in 설정 › 급여,
 * or the job, region and trade of a rate they shared themselves. Only what the server already showed is kept;
 * the user's pay never goes into it or out of the phone.
 */
class RateHintStore(context: Context) {
    /** The user's own shared rate (job, region, trade) and the region picked by hand; blank when not known. */
    data class Own(val job: String = "", val region: String = "", val trade: String = "", val picked: String = "")

    private val prefs = context.getSharedPreferences("plaza_rate_hint", Context.MODE_PRIVATE)
    private val _groups = MutableStateFlow(read())
    val groups: StateFlow<List<RateHint.Group>> = _groups.asStateFlow()
    private val _own = MutableStateFlow(readOwn())
    val own: StateFlow<Own> = _own.asStateFlow()

    /** Adds the groups of one 임금표 look; the same job, region, trade, unit and year is replaced by the newer. */
    fun keep(region: String, groups: List<RateGroup>) {
        if (groups.isEmpty()) return
        val fresh = groups.map { RateHint.Group(it.job, it.region.ifBlank { region }, it.trade, it.unit, it.year, it.n, it.low, it.mid, it.high) }
            .filter { it.job.isNotBlank() && it.region.isNotBlank() }
        val list = (fresh + _groups.value).distinctBy { listOf(it.job, it.region, it.trade, it.unit, it.year) }.take(MAX)
        prefs.edit().putString(KEY, JSONArray().apply {
            list.forEach { g ->
                put(JSONObject().put("job", g.job).put("region", g.region).put("trade", g.trade).put("unit", g.unit).put("year", g.year)
                    .put("n", g.n).put("low", g.low).put("mid", g.mid).put("high", g.high))
            }
        }.toString()).apply()
        _groups.value = list
    }

    /** A rate the user shared on 임금표 says their job, region and trade better than anything else. */
    fun shared(job: String, region: String, trade: String) {
        if (job.isBlank() || region.isBlank()) return
        save(_own.value.copy(job = job, region = region, trade = trade.trim()))
    }

    /** The 시·도 picked in 설정 › 급여; blank takes it back. */
    fun pick(region: String) = save(_own.value.copy(picked = region))

    /** Where the user stands for a pay setup of [occupation], or null when the job or region is not known. */
    fun who(occupation: Occupation, own: Own = _own.value): RateHint.Mine? {
        val job = RateHint.jobOf(occupation) ?: own.job.ifBlank { return null }
        // A shared rate counts only for its own job; a nurse's old 일당 share as 알바 does not say where they nurse.
        val sharedHere = own.job == job && own.region.isNotBlank()
        val region = own.picked.ifBlank { if (sharedHere) own.region else "" }.ifBlank { return null }
        return RateHint.Mine(job, region, if (sharedHere && own.region == region) own.trade else "")
    }

    private fun save(o: Own) {
        prefs.edit().putString(OWN, JSONObject().put("job", o.job).put("region", o.region).put("trade", o.trade).put("picked", o.picked).toString()).apply()
        _own.value = o
    }

    private fun readOwn(): Own = runCatching {
        val o = JSONObject(prefs.getString(OWN, null) ?: return Own())
        Own(o.optString("job"), o.optString("region"), o.optString("trade"), o.optString("picked"))
    }.getOrDefault(Own())

    private fun read(): List<RateHint.Group> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RateHint.Group(o.optString("job"), o.optString("region"), o.optString("trade"), o.optString("unit"), o.optInt("year"), o.optInt("n"),
                o.optLong("low"), o.optLong("mid"), o.optLong("high"))
        }
    }.getOrDefault(emptyList()).filter { it.job.isNotBlank() && it.region in Plaza.REGIONS }

    private companion object {
        const val KEY = "groups"
        const val OWN = "own"
        const val MAX = 60
    }
}
