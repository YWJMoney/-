package com.bamnat.app.pay

import android.content.Context
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The pay profile, kept as one JSON file in app storage (included in phone backup) [PAY-10].
 * Company pay values never leave the phone.
 */
class PayStore(context: Context) {
    private val file = File(context.filesDir, "pay/profile.json")
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<PayProfile?> = _profile.asStateFlow()

    /** The profile before the last change, for one-step undo of a bulk edit [PAYX-03]. */
    private val _undo = MutableStateFlow<PayProfile?>(null)
    val canUndo: StateFlow<PayProfile?> = _undo.asStateFlow()

    suspend fun save(profile: PayProfile?, keepUndo: Boolean = true) = withContext(Dispatchers.IO) {
        synchronized(this@PayStore) {
            if (keepUndo) _undo.value = _profile.value
            if (profile == null) file.delete() else {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "profile.json.tmp")
                tmp.writeText(PayCodec.encode(profile).toString())
                tmp.renameTo(file)
            }
            _profile.value = profile
        }
    }

    suspend fun undo() {
        val previous = _undo.value ?: return
        save(previous, keepUndo = false)
        _undo.value = null
    }

    private fun read(): PayProfile? =
        runCatching { if (file.exists()) PayCodec.decode(JSONObject(file.readText())) else null }.getOrNull()
}

/** JSON for [PayProfile]; also used by the full backup file. */
object PayCodec {
    fun encode(p: PayProfile): JSONObject = JSONObject()
        .put("v", 1)
        .put("occupation", p.occupation.name)
        .put("wageType", p.wageType.name)
        .put("base", timeline(p.base))
        .put("items", JSONArray(p.items.map(::item)))
        .put("hireDate", p.hireDate?.toString())
        .put("fiveOrMore", p.fiveOrMoreEmployees)
        .put("monthlyHours", p.monthlyHours)
        .put("holidayMode", p.holidayPayMode.name)
        .put("dailyLimit", p.dailyLimitMinutes ?: -1)
        .put("weeklyLimit", p.weeklyLimitMinutes ?: -1)
        .put("weekStart", p.weekStart.name)
        .put("estimated", JSONArray(p.estimatedFields.toList()))

    fun decode(o: JSONObject): PayProfile = PayProfile(
        occupation = Occupation.valueOf(o.getString("occupation")),
        wageType = WageType.valueOf(o.getString("wageType")),
        base = timeline(o.getJSONObject("base")),
        items = o.optJSONArray("items").objects().map(::item),
        hireDate = o.optString("hireDate").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse),
        fiveOrMoreEmployees = o.optBoolean("fiveOrMore", true),
        monthlyHours = o.optInt("monthlyHours", 209),
        holidayPayMode = runCatching { HolidayPayMode.valueOf(o.getString("holidayMode")) }.getOrDefault(HolidayPayMode.FULL),
        dailyLimitMinutes = o.optInt("dailyLimit", 480).takeIf { it >= 0 },
        weeklyLimitMinutes = o.optInt("weeklyLimit", 2400).takeIf { it >= 0 },
        weekStart = runCatching { DayOfWeek.valueOf(o.getString("weekStart")) }.getOrDefault(DayOfWeek.MONDAY),
        estimatedFields = o.optJSONArray("estimated")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
    )

    private fun timeline(t: ValueTimeline): JSONObject = JSONObject()
        .put("entries", JSONArray(t.entries.map { JSONObject().put("from", it.from.toString()).put("value", it.value) }))
        .put("raise", t.raise?.let { r ->
            JSONObject().put("month", r.month).put("percent", r.percent ?: JSONObject.NULL)
                .put("amount", r.amount ?: JSONObject.NULL).put("unit", r.unit).put("rounding", r.rounding.name)
        } ?: JSONObject.NULL)

    private fun timeline(o: JSONObject): ValueTimeline = ValueTimeline(
        entries = o.optJSONArray("entries").objects().map { DatedValue(LocalDate.parse(it.getString("from")), it.getLong("value")) },
        raise = o.optJSONObject("raise")?.let { r ->
            RaiseRule(
                month = r.getInt("month"),
                percent = if (r.isNull("percent")) null else r.getDouble("percent"),
                amount = if (r.isNull("amount")) null else r.getLong("amount"),
                unit = r.optLong("unit", 10),
                rounding = runCatching { Rounding.valueOf(r.getString("rounding")) }.getOrDefault(Rounding.UP),
            )
        },
    )

    private fun item(i: PayItem): JSONObject = JSONObject()
        .put("id", i.id).put("name", i.name).put("amount", timeline(i.amount)).put("cadence", i.cadence.name)
        .put("types", JSONArray(i.shiftTypeIds.toList())).put("ordinary", i.ordinary).put("taxable", i.taxable)

    private fun item(o: JSONObject): PayItem = PayItem(
        id = o.getString("id"),
        name = o.getString("name"),
        amount = timeline(o.getJSONObject("amount")),
        cadence = runCatching { Cadence.valueOf(o.getString("cadence")) }.getOrDefault(Cadence.MONTHLY),
        shiftTypeIds = o.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet(),
        ordinary = o.optBoolean("ordinary", false),
        taxable = o.optBoolean("taxable", true),
    )

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
