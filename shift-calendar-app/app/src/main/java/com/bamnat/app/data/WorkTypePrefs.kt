package com.bamnat.app.data

import android.content.Context
import com.bamnat.core.pay.TaxMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [DAYW-02][DAYW-05] One calendar's choices for its work type's cards in 통계. */
data class WorkTypeChoices(
    /** [DAYW-05] 시간외 상한 in hours a month; null when not set. */
    val overtimeCapHours: Int? = null,
    /** [DAYW-02][SHFT-04] How tax comes off the pay for the take-home estimate. */
    val taxMode: TaxMode = TaxMode.INSURANCE_4,
)

/**
 * [DAYW-02][DAYW-05] Small per-calendar choices for 통계's work-type cards, by calendar id, kept apart
 * from the pay setup so a calendar without one still has them. A value that cannot be read is the default.
 */
class WorkTypePrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("work_type_prefs", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val all: StateFlow<Map<String, WorkTypeChoices>> = state.asStateFlow()

    fun of(calendarId: String): WorkTypeChoices = state.value[calendarId] ?: WorkTypeChoices()

    fun setOvertimeCap(calendarId: String, hours: Int?) = write(calendarId) { it.copy(overtimeCapHours = hours?.coerceIn(1, 300)) }

    fun setTaxMode(calendarId: String, mode: TaxMode) = write(calendarId) { it.copy(taxMode = mode) }

    private fun write(calendarId: String, change: (WorkTypeChoices) -> WorkTypeChoices) {
        val next = change(of(calendarId))
        prefs.edit()
            .putInt(K_CAP + calendarId, next.overtimeCapHours ?: 0)
            .putString(K_TAX + calendarId, next.taxMode.name)
            .apply()
        state.value = state.value + (calendarId to next)
    }

    private fun read(): Map<String, WorkTypeChoices> {
        val ids = prefs.all.keys.mapNotNull { k -> listOf(K_CAP, K_TAX).firstOrNull { k.startsWith(it) }?.let { k.removePrefix(it) } }.toSet()
        return ids.associateWith { id ->
            WorkTypeChoices(
                overtimeCapHours = prefs.getInt(K_CAP + id, 0).takeIf { it > 0 },
                taxMode = runCatching { TaxMode.valueOf(prefs.getString(K_TAX + id, null) ?: "") }.getOrDefault(TaxMode.INSURANCE_4),
            )
        }
    }

    companion object {
        private const val K_CAP = "cap_"
        private const val K_TAX = "tax_"

        @Volatile private var instance: WorkTypePrefs? = null

        fun get(context: Context): WorkTypePrefs = instance ?: synchronized(this) {
            instance ?: WorkTypePrefs(context).also { instance = it }
        }
    }
}
