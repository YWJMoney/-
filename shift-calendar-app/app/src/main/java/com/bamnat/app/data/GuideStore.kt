package com.bamnat.app.data

import android.content.Context
import android.content.SharedPreferences
import com.bamnat.core.guide.AppFeature
import com.bamnat.core.guide.ChecklistState
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.GuideChecklist
import com.bamnat.core.guide.GuideState
import com.bamnat.core.guide.Off
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.LocalDate

/**
 * [GUIDE-02][GUIDE-03][GUIDE-04][GUIDE-05] The guide's state, only on this phone: the app's own feature
 * switches that are off, which features were used (nothing is sent anywhere), tips put away, today's tip
 * and each calendar's checklist. The switches go in a backup; the rest is about this phone's use.
 */
class GuideStore private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("guide", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<GuideState> = _state.asStateFlow()
    val current: GuideState get() = _state.value

    /** [GUIDE-05] The app's own switch of [feature] for [calendar] ([Off.APP] is one for every calendar). */
    fun setOn(feature: AppFeature, calendar: String, on: Boolean) {
        val key = GuideState.offKey(if (feature.off == Off.APP) GuideState.ALL else calendar, feature.id)
        edit { putStringSet(K_OFF, if (on) current.off - key else current.off + key) }
    }

    fun isOff(id: String, calendar: String): Boolean = FeatureRegistry.get(id)?.let { current.isOff(it, calendar) } ?: false

    /** [GUIDE-04] A feature was used (opened, or its button pressed): its line in the checklist ticks and its tip stops. */
    fun used(id: String) {
        if (id in current.used || FeatureRegistry.get(id) == null) return
        edit { putStringSet(K_USED, current.used + id) }
    }

    /** [GUIDE-03] "다시 보지 않기". */
    fun never(id: String) = edit { putStringSet(K_NEVER, current.never + id) }

    /** [GUIDE-03] Today's tip, recorded once it shows, so no other comes today. */
    fun tipShown(day: LocalDate, id: String) = edit {
        putString(K_TIP_DAY, day.toString()); putString(K_TIP_ID, id); putBoolean(K_TIP_CLOSED, false)
    }

    fun closeTip(day: LocalDate) = edit { putString(K_TIP_DAY, day.toString()); putBoolean(K_TIP_CLOSED, true) }

    /** [GUIDE-02] A calendar opened: its checklist starts, or starts again after its kind of work changed. */
    fun startChecklist(calendar: String, style: WorkStyle?, today: LocalDate) {
        val now = current.checklists[calendar]
        val next = GuideChecklist.start(now, style, today)
        if (next != now) writeChecklist(calendar, next)
    }

    /** [GUIDE-05] '안내 카드 다시 보기': the checklist again from today, for its days. */
    fun restartChecklist(calendar: String, style: WorkStyle?, today: LocalDate) = writeChecklist(calendar, ChecklistState(style, today))

    fun later(calendar: String, today: LocalDate) {
        current.checklists[calendar]?.let { writeChecklist(calendar, it.copy(laterUntil = today.plusDays(GuideChecklist.LATER_DAYS))) }
    }

    fun dismiss(calendar: String) {
        current.checklists[calendar]?.let { writeChecklist(calendar, it.copy(dismissed = true)) }
    }

    private fun writeChecklist(calendar: String, s: ChecklistState) = edit {
        putString(K_LIST + calendar, listOf(s.style?.name ?: "", s.startedOn, if (s.dismissed) "1" else "0", s.laterUntil ?: "").joinToString("|"))
    }

    /** [GUIDE-05] The switches turned off, for a backup. */
    fun exportOff(): JSONArray = JSONArray(current.off.sorted())

    fun restoreOff(a: JSONArray?) {
        if (a == null) return
        val off = (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.contains('/') } }.toSet()
        edit { putStringSet(K_OFF, off) }
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
            prefs.edit().apply(block).apply()
            _state.value = read()
        }
    }

    private fun read(): GuideState {
        fun set(key: String) = prefs.getStringSet(key, null)?.toSet().orEmpty()
        val lists = prefs.all.keys.filter { it.startsWith(K_LIST) }.mapNotNull { k ->
            val parts = prefs.getString(k, null)?.split('|') ?: return@mapNotNull null
            val started = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            k.removePrefix(K_LIST) to ChecklistState(
                style = parts[0].takeIf { it.isNotEmpty() }?.let { n -> WorkStyle.entries.firstOrNull { it.name == n } },
                startedOn = started,
                dismissed = parts.getOrNull(2) == "1",
                laterUntil = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        }.toMap()
        return GuideState(
            off = set(K_OFF),
            used = set(K_USED),
            never = set(K_NEVER),
            tipDay = prefs.getString(K_TIP_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            tipId = prefs.getString(K_TIP_ID, null),
            tipClosed = prefs.getBoolean(K_TIP_CLOSED, false),
            checklists = lists,
        )
    }

    companion object {
        private const val K_OFF = "off"
        private const val K_USED = "used"
        private const val K_NEVER = "never"
        private const val K_TIP_DAY = "tip_day"
        private const val K_TIP_ID = "tip_id"
        private const val K_TIP_CLOSED = "tip_closed"
        private const val K_LIST = "list_"

        @Volatile private var instance: GuideStore? = null

        fun get(context: Context): GuideStore = instance ?: synchronized(this) {
            instance ?: GuideStore(context).also { instance = it }
        }
    }
}
