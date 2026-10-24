package com.bamnat.app.tier

import com.bamnat.app.BuildConfig
import com.bamnat.app.company.CompanyException
import com.bamnat.app.company.objects
import com.bamnat.app.company.optStringOrNull
import com.bamnat.app.company.serverCall
import com.bamnat.app.company.serverRequest
import com.bamnat.core.tier.PlaySub
import org.json.JSONObject
import java.time.Instant
import com.bamnat.core.time.Today

/** [MON-08] One reward pass the developer gave this install: [why] is "rule" (회사 방식) or "idea" (제안). */
data class PassItem(val months: Int, val at: Instant, val why: String)

/** [MON-09] This phone tied to an admin account: "pending" until the owner approves it, then "approved". */
data class AdminState(val status: String, val name: String) {
    val approved: Boolean get() = status == "approved"
}

/**
 * [MON-08][MON-09] What the company server keeps for this install: the end of its stacked passes (null
 * before the launch, when [waitingMonths] says how many start then), its admin link and whether a
 * recovery code exists. [checkedAt] is the server's clock.
 */
data class Passes(
    val until: Instant?,
    val waitingMonths: Int,
    val items: List<PassItem>,
    val deferred: Int,
    val admin: AdminState?,
    val hasCode: Boolean,
    val checkedAt: Instant,
)

/** [MON-03] A subscription as Play told the server; [verified] false when the server has no Play key. */
data class ServerSub(
    val verified: Boolean,
    val state: PlaySub.State? = null,
    val expiry: Instant? = null,
    val trial: Boolean = false,
    val autoRenew: Boolean = false,
    val consentAt: Instant? = null,
    val stoppedAt: Instant? = null,
)

/**
 * [MON-02][MON-03][MON-06][MON-08][MON-09] What the app asks the company server about 프로. The device is
 * the same random install id as 회사 찾기 (CompanyStore.device); screenshots and tests answer with a fake.
 */
interface TierApi {
    val enabled: Boolean
    suspend fun config(now: Instant): LaunchConfig
    suspend fun passes(device: String): Passes
    /** A new recovery code for this install's passes; the server keeps only its hash. */
    suspend fun newCode(device: String): String
    /** Moves the passes of [code] to this install; the old phone keeps nothing. */
    suspend fun claim(device: String, code: String): Passes
    suspend fun link(device: String, code: String): AdminState
    suspend fun unlink(device: String)
    suspend fun purchase(device: String, product: String, token: String): ServerSub
    /**
     * [MON-06] The user's own answer before a trial turns paid: "keep" or "cancel", with what they were shown.
     * [SRV-07] [answeredAt] is when they tapped, which may be before a queued answer is sent.
     */
    suspend fun consent(device: String, answer: String, product: String, price: String, trialEnd: Instant, shown: String, answeredAt: Instant)
}

/** [MON-02] The company server (server/company-worker); a build without its address asks nothing. */
class TierClient(private val baseUrl: String = BuildConfig.COMPANY_BASE_URL) : TierApi {
    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun config(now: Instant): LaunchConfig = serverCall {
        LaunchConfig.parse(request("GET", "/api/config"), now)
    }

    override suspend fun passes(device: String): Passes = serverCall { readPasses(request("GET", "/api/passes", device = device)) }

    override suspend fun newCode(device: String): String = serverCall {
        request("POST", "/api/passes/code", JSONObject(), device).getString("code")
    }

    override suspend fun claim(device: String, code: String): Passes = serverCall {
        readPasses(request("POST", "/api/passes/claim", JSONObject().put("code", code.trim()), device))
    }

    override suspend fun link(device: String, code: String): AdminState = serverCall {
        val o = request("POST", "/api/admin-link", JSONObject().put("code", code.trim()), device)
        AdminState(o.getString("status"), o.optString("name"))
    }

    override suspend fun unlink(device: String) = serverCall {
        request("DELETE", "/api/admin-link", device = device)
        Unit
    }

    override suspend fun purchase(device: String, product: String, token: String): ServerSub = serverCall {
        val o = request("POST", "/api/purchases", JSONObject().put("product", product).put("token", token), device)
        if (!o.optBoolean("verified")) return@serverCall ServerSub(verified = false)
        ServerSub(
            verified = true,
            state = o.optStringOrNull("state")?.let { s -> PlaySub.State.entries.firstOrNull { it.name == s } } ?: PlaySub.State.PENDING,
            expiry = o.optStringOrNull("expiry")?.let(Instant::parse),
            trial = o.optBoolean("trial"),
            autoRenew = o.optBoolean("autoRenew"),
            consentAt = o.optStringOrNull("consentAt")?.let(Instant::parse),
            stoppedAt = o.optStringOrNull("stoppedAt")?.let(Instant::parse),
        )
    }

    override suspend fun consent(device: String, answer: String, product: String, price: String, trialEnd: Instant, shown: String,
        answeredAt: Instant) = serverCall {
        request("POST", "/api/consents", JSONObject().put("answer", answer).put("product", product).put("price", price)
            .put("trialEnd", trialEnd.toString()).put("shown", shown.take(300)).put("answeredAt", answeredAt.toString()), device)
        Unit
    }

    private fun request(method: String, path: String, body: JSONObject? = null, device: String? = null): JSONObject {
        if (!enabled) throw CompanyException("offline")
        return serverRequest(baseUrl, method, path, body, device)
    }

    companion object {
        fun readPasses(o: JSONObject): Passes = Passes(
            until = o.optStringOrNull("until")?.let(Instant::parse),
            waitingMonths = o.optInt("waitingMonths"),
            items = o.optJSONArray("items").objects().map { PassItem(it.getInt("months"), Instant.parse(it.getString("at")), it.optString("why")) },
            deferred = o.optInt("deferred"),
            admin = o.optJSONObject("admin")?.let { AdminState(it.getString("status"), it.optString("name")) },
            hasCode = o.optBoolean("code"),
            checkedAt = o.optStringOrNull("checkedAt")?.let(Instant::parse) ?: Today.instant(),
        )
    }
}
