package com.bamnat.app.company

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** A report this phone sent, kept so its status can be looked up and it can be taken back [COMM-02]. */
data class SentReport(val id: String, val company: String, val dept: String, val ask: Boolean, val at: Instant)

/** The shared setup in use on this phone, for 맞았어요·달라요 [COMM-03]. */
data class AppliedSetup(
    val companyId: String,
    val company: String,
    val dept: String,
    val setup: String,
    val version: Int?,
    val adopted: Boolean,
    val at: Instant,
    /** true 맞았어요, false 달라요, null not said yet. */
    val vote: Boolean? = null,
)

/**
 * A company the user typed and set up by hand, waiting to be shared with coworkers [COMM-02].
 * [fix]: the user said a shared setup was wrong and is correcting it [COMM-03].
 */
data class CompanyDraft(val company: String, val dept: String = "", val fix: Boolean = false)

/**
 * [COMM-02] What this phone keeps about sharing company setups: a random install id, what it sent,
 * the shared setup it uses and a company typed during setup. Never an amount.
 */
class CompanyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("company", Context.MODE_PRIVATE)

    /** Random and made here, so the server can count one phone once without knowing whose it is. */
    val device: String
        get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }

    private val _sent = MutableStateFlow(readSent())
    val sent: StateFlow<List<SentReport>> = _sent.asStateFlow()

    private val _applied = MutableStateFlow(readApplied())
    val applied: StateFlow<AppliedSetup?> = _applied.asStateFlow()

    private val _draft = MutableStateFlow(prefs.getString("draft", null)?.let { s ->
        runCatching { JSONObject(s).let { CompanyDraft(it.getString("company"), it.optString("dept"), it.optBoolean("fix")) } }.getOrNull()
    })
    val draft: StateFlow<CompanyDraft?> = _draft.asStateFlow()

    private val _pendingPay = MutableStateFlow(prefs.getString("pending_pay", null))
    /** The applied setup's file until pay is set up from it with the user's own amounts. */
    val pendingPayFlow: StateFlow<String?> = _pendingPay.asStateFlow()
    var pendingPay: String?
        get() = _pendingPay.value
        set(v) {
            _pendingPay.value = v
            prefs.edit().putString("pending_pay", v).apply()
        }

    private val _voteLater = MutableStateFlow(prefs.getLong("vote_later", 0))
    /** 맞았어요·달라요 is not asked again before this time (epoch ms) after "나중에". */
    val voteLater: StateFlow<Long> = _voteLater.asStateFlow()

    fun voteLaterUntil(at: Long) {
        _voteLater.value = at
        prefs.edit().putLong("vote_later", at).apply()
    }

    private val _plazaEntryClosed = MutableStateFlow(prefs.getString("plaza_entry_closed", null))
    /** [COMM-12] The company name whose "같은 회사 분들 규칙 보기" card was put away; a new name shows it again. */
    val plazaEntryClosed: StateFlow<String?> = _plazaEntryClosed.asStateFlow()

    fun closePlazaEntry(company: String) {
        _plazaEntryClosed.value = company
        prefs.edit().putString("plaza_entry_closed", company).apply()
    }

    private val _inviteClosed = MutableStateFlow(prefs.getBoolean("invite_closed", false))
    /** The invitation to share a hand-made setup was closed. */
    val inviteClosed: StateFlow<Boolean> = _inviteClosed.asStateFlow()

    fun closeInvite(closed: Boolean = true) {
        _inviteClosed.value = closed
        prefs.edit().putBoolean("invite_closed", closed).apply()
    }

    fun addSent(r: SentReport) = writeSent(listOf(r) + _sent.value.filter { it.id != r.id })

    fun removeSent(id: String) = writeSent(_sent.value.filter { it.id != id })

    /** After everything this phone sent was deleted on the server. */
    fun forgetSent() {
        writeSent(emptyList())
        _applied.value?.let { setApplied(it.copy(vote = null)) }
    }

    fun setApplied(a: AppliedSetup?) {
        _applied.value = a
        prefs.edit().putString("applied", a?.let {
            JSONObject().put("companyId", it.companyId).put("company", it.company).put("dept", it.dept).put("setup", it.setup)
                .put("version", it.version ?: -1).put("adopted", it.adopted).put("at", it.at.toString())
                .put("vote", it.vote ?: JSONObject.NULL).toString()
        }).apply()
    }

    fun voted(ok: Boolean) {
        _applied.value?.let { setApplied(it.copy(vote = ok)) }
    }

    fun setDraft(d: CompanyDraft?) {
        _draft.value = d
        prefs.edit().putString("draft", d?.let { JSONObject().put("company", it.company).put("dept", it.dept).put("fix", it.fix).toString() }).apply()
        if (d != null) closeInvite(false)
    }

    private fun writeSent(list: List<SentReport>) {
        // The newest 50 are plenty to follow; older ones stay on the server until taken back or deleted.
        val kept = list.take(50)
        _sent.value = kept
        prefs.edit().putString("sent", JSONArray(kept.map {
            JSONObject().put("id", it.id).put("company", it.company).put("dept", it.dept).put("ask", it.ask).put("at", it.at.toString())
        }).toString()).apply()
    }

    private fun readSent(): List<SentReport> = runCatching {
        JSONArray(prefs.getString("sent", "[]")).objects().map {
            SentReport(it.getString("id"), it.getString("company"), it.optString("dept"), it.optBoolean("ask"), Instant.parse(it.getString("at")))
        }
    }.getOrDefault(emptyList())

    private fun readApplied(): AppliedSetup? = runCatching {
        val o = JSONObject(prefs.getString("applied", null) ?: return null)
        AppliedSetup(
            companyId = o.getString("companyId"), company = o.getString("company"), dept = o.optString("dept"), setup = o.getString("setup"),
            version = o.optInt("version", -1).takeIf { it > 0 }, adopted = o.optBoolean("adopted"), at = Instant.parse(o.getString("at")),
            vote = if (o.isNull("vote")) null else o.getBoolean("vote"),
        )
    }.getOrNull()
}
