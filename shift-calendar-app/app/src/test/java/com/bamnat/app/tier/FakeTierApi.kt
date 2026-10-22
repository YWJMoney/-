package com.bamnat.app.tier

import com.bamnat.app.company.CompanyException
import java.time.Instant

/** [MON-02] The company server as tests need it: answers set by hand, and what was asked. */
class FakeTierApi(
    var configAnswer: LaunchConfig? = LaunchConfig(),
    var passesAnswer: Passes? = null,
    override val enabled: Boolean = true,
) : TierApi {
    val calls = mutableListOf<String>()
    val consents = mutableListOf<Pair<String, String>>()

    override suspend fun config(now: Instant): LaunchConfig {
        calls += "config"
        return configAnswer?.copy(fetchedAt = now) ?: throw CompanyException("offline")
    }

    override suspend fun passes(device: String): Passes {
        calls += "passes"
        return passesAnswer ?: throw CompanyException("offline")
    }

    override suspend fun newCode(device: String): String = "ABCD-EFGH-JKLM".also { calls += "newCode" }

    override suspend fun claim(device: String, code: String): Passes {
        calls += "claim $code"
        return passesAnswer ?: throw CompanyException("not_found")
    }

    override suspend fun link(device: String, code: String): AdminState = AdminState("pending", "관리자").also { calls += "link" }
    override suspend fun unlink(device: String) { calls += "unlink" }
    override suspend fun purchase(device: String, product: String, token: String): ServerSub = ServerSub(verified = false).also { calls += "purchase" }

    override suspend fun consent(device: String, answer: String, product: String, price: String, trialEnd: Instant, shown: String) {
        consents += answer to shown
    }
}
