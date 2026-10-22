package com.bamnat.app.tier

import android.content.Context
import com.bamnat.core.tier.AdminLink
import com.bamnat.core.tier.AdRules
import com.bamnat.core.tier.PlaySub
import com.bamnat.core.tier.ProSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [MON-03] The 프로 subscription as this phone last knew it: from Play on the phone, and from the
 * server when it holds the Play key ([verified]). Play on the phone gives no end date, so a phone-only
 * record lasts [PHONE_HOLD] after Play last confirmed it, to ride out a day without Play.
 */
data class SubRecord(
    val product: String,
    val basePlan: String?,
    val state: PlaySub.State,
    val expiry: Instant?,
    val trial: Boolean,
    val autoRenew: Boolean,
    val purchasedAt: Instant,
    val verified: Boolean,
    val checkedAt: Instant,
    val consentAt: Instant? = null,
    val stoppedAt: Instant? = null,
) {
    fun play(): PlaySub = PlaySub(state, expiry, trial, autoRenew, purchasedAt, verified)

    companion object {
        val PHONE_HOLD: Duration = Duration.ofDays(3)
    }
}

/**
 * [MON-06] When the trial ends: Play's end date when the server read it, otherwise a calendar month
 * after it began, as Play counts P1M.
 */
fun SubRecord.trialEnd(zone: ZoneId): Instant =
    expiry?.takeIf { verified } ?: com.bamnat.core.tier.TrialConsent.trialEnd(purchasedAt, zone)

/** Everything on this phone that can give it 프로, and what the user was told about it. */
data class ProState(
    val sub: SubRecord? = null,
    val passes: Passes? = null,
    /** [MON-07] The day a rewarded ad was watched; it opens the pro features until midnight. */
    val dayPass: LocalDate? = null,
    /** [MON-06] The user's own answer before the trial turns paid, kept here and on the server. */
    val trialAnswer: String? = null,
    val trialAnswerAt: Instant? = null,
    /** [MON-08] The recovery code made on this phone, kept for the backup file. */
    val code: String? = null,
    /** A recovery code from a backup, waiting to move its passes here. */
    val pendingClaim: String? = null,
    /** [MON-10] The 정식 출시 thank-you was shown. */
    val thanksSeen: Boolean = false,
    /** [MON-06] The trial whose day-23 notice went out, by its start. */
    val trialNoticeFor: Instant? = null,
) {
    fun sources(config: LaunchConfig, installedAt: Instant?, zone: ZoneId): ProSources = ProSources(
        billingLive = config.billingLive,
        subscription = sub?.play(),
        passUntil = passes?.until,
        admin = passes?.admin?.let { AdminLink(it.approved, passes.checkedAt) },
        installedAt = installedAt,
        launchedAt = config.launchedAt,
        thanksMonths = config.thanksMonths,
        dayPassUntil = dayPass?.let { AdRules.dayPassUntil(it, zone) },
    )

    /** [MON-06] Consent to the paid month after the trial: the server's record, or the answer given here. */
    val consentAt: Instant? get() = sub?.consentAt ?: trialAnswerAt?.takeIf { trialAnswer == "keep" }
}

/** [MON-02] Where [ProState] is kept: this phone only, apart from the recovery code in the backup. */
class ProStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pro", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProState> = _state.asStateFlow()
    val current: ProState get() = _state.value

    fun setSub(s: SubRecord?) = edit { it.copy(sub = s) }
    fun setPasses(p: Passes) = edit { it.copy(passes = p) }
    fun dayPass(day: LocalDate) = edit { it.copy(dayPass = day) }
    fun answer(answer: String, at: Instant) = edit { it.copy(trialAnswer = answer, trialAnswerAt = at) }
    fun setCode(code: String?) = edit { it.copy(code = code) }
    fun claimLater(code: String?) = edit { it.copy(pendingClaim = code) }
    fun thanksSeen() = edit { it.copy(thanksSeen = true) }
    fun trialNoticed(start: Instant) = edit { it.copy(trialNoticeFor = start) }

    /** [MON-08] For the backup: the recovery code and what was already shown, never the purchase. */
    fun toBackup(): JSONObject = JSONObject().put("code", current.code ?: JSONObject.NULL).put("thanksSeen", current.thanksSeen)

    /** A backup's code moves its passes here at the next sync, unless it is this phone's own. */
    fun fromBackup(o: JSONObject) {
        val code = if (o.isNull("code")) null else o.optString("code").takeIf { it.isNotBlank() }
        edit { s ->
            s.copy(
                thanksSeen = s.thanksSeen || o.optBoolean("thanksSeen"),
                code = code ?: s.code,
                pendingClaim = code?.takeIf { it != s.code } ?: s.pendingClaim,
            )
        }
    }

    private fun edit(change: (ProState) -> ProState) {
        _state.update(change)
        prefs.edit().putString(KEY, write(_state.value).toString()).apply()
    }

    private fun read(): ProState = runCatching { parse(JSONObject(prefs.getString(KEY, null) ?: return ProState())) }.getOrDefault(ProState())

    companion object {
        private const val KEY = "state"

        private fun JSONObject.instant(k: String): Instant? = if (isNull(k) || !has(k)) null else Instant.parse(getString(k))
        private fun JSONObject.text(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
        private fun Instant?.json(): Any = this?.toString() ?: JSONObject.NULL

        internal fun write(s: ProState): JSONObject = JSONObject()
            .put("sub", s.sub?.let { r ->
                JSONObject().put("product", r.product).put("basePlan", r.basePlan ?: JSONObject.NULL).put("state", r.state.name)
                    .put("expiry", r.expiry.json()).put("trial", r.trial).put("autoRenew", r.autoRenew).put("purchasedAt", r.purchasedAt.toString())
                    .put("verified", r.verified).put("checkedAt", r.checkedAt.toString()).put("consentAt", r.consentAt.json()).put("stoppedAt", r.stoppedAt.json())
            } ?: JSONObject.NULL)
            .put("passes", s.passes?.let { p ->
                JSONObject().put("until", p.until.json()).put("waitingMonths", p.waitingMonths)
                    .put("items", JSONArray(p.items.map { JSONObject().put("months", it.months).put("at", it.at.toString()).put("why", it.why) }))
                    .put("deferred", p.deferred).put("code", p.hasCode).put("checkedAt", p.checkedAt.toString())
                    .put("admin", p.admin?.let { JSONObject().put("status", it.status).put("name", it.name) } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("dayPass", s.dayPass?.toString() ?: JSONObject.NULL)
            .put("trialAnswer", s.trialAnswer ?: JSONObject.NULL).put("trialAnswerAt", s.trialAnswerAt.json())
            .put("code", s.code ?: JSONObject.NULL).put("pendingClaim", s.pendingClaim ?: JSONObject.NULL)
            .put("thanksSeen", s.thanksSeen).put("trialNoticeFor", s.trialNoticeFor.json())

        internal fun parse(o: JSONObject): ProState = ProState(
            sub = o.optJSONObject("sub")?.let { r ->
                SubRecord(
                    product = r.getString("product"), basePlan = r.text("basePlan"),
                    state = PlaySub.State.entries.firstOrNull { it.name == r.optString("state") } ?: PlaySub.State.PENDING,
                    expiry = r.instant("expiry"), trial = r.optBoolean("trial"), autoRenew = r.optBoolean("autoRenew"),
                    purchasedAt = Instant.parse(r.getString("purchasedAt")), verified = r.optBoolean("verified"),
                    checkedAt = Instant.parse(r.getString("checkedAt")), consentAt = r.instant("consentAt"), stoppedAt = r.instant("stoppedAt"),
                )
            },
            passes = o.optJSONObject("passes")?.let { TierClient.readPasses(it) },
            dayPass = o.text("dayPass")?.let(LocalDate::parse),
            trialAnswer = o.text("trialAnswer"), trialAnswerAt = o.instant("trialAnswerAt"),
            code = o.text("code"), pendingClaim = o.text("pendingClaim"),
            thanksSeen = o.optBoolean("thanksSeen"), trialNoticeFor = o.instant("trialNoticeFor"),
        )
    }
}
