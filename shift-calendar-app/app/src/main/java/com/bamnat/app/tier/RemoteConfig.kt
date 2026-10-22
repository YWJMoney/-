package com.bamnat.app.tier

import android.content.Context
import com.bamnat.core.tier.AdRules
import com.bamnat.core.tier.TrialConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * [MON-02] What the company server's `GET /api/config` says: the developer app's 정식 출시 switch and
 * what goes with it. A phone that never heard from the server keeps the switch off, so everything stays
 * free. [freeFeatures] can only open a 프로 feature to everyone, never lock a free one.
 */
data class LaunchConfig(
    val billingLive: Boolean = false,
    val launchedAt: Instant? = null,
    val thanksMonths: Int = 0,
    val freeFeatures: Set<Feature> = emptySet(),
    val adQuietDays: Int = AdRules.QUIET_DAYS,
    val trialAskDays: Int = TrialConsent.ASK_BEFORE.toDays().toInt(),
    val fetchedAt: Instant? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("billingLive", billingLive).put("launchedAt", launchedAt?.toString() ?: JSONObject.NULL)
        .put("thanksMonths", thanksMonths).put("freeFeatures", JSONArray(freeFeatures.map { it.name }))
        .put("adQuietDays", adQuietDays).put("trialAskDays", trialAskDays).put("fetchedAt", fetchedAt?.toString() ?: JSONObject.NULL)

    companion object {
        /** The server's answer read strictly: a wrong shape is an error, so the last good answer stays. */
        fun parse(o: JSONObject, now: Instant): LaunchConfig {
            if (!o.has("billingLive")) throw JSONException("billingLive")
            val live = o.getBoolean("billingLive")
            val names = o.optJSONArray("freeFeatures")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return LaunchConfig(
                billingLive = live,
                launchedAt = if (o.isNull("launchedAt") || !o.has("launchedAt")) null else Instant.parse(o.getString("launchedAt")),
                thanksMonths = o.optInt("thanksMonths", 0).coerceIn(0, 12),
                // Unknown names (a feature of a newer app) are skipped; a free feature cannot be locked from here.
                freeFeatures = names.mapNotNull { n -> Feature.entries.firstOrNull { it.name == n } }.filter { it.minTier > Tier.FREE }.toSet(),
                adQuietDays = o.optInt("adQuietDays", AdRules.QUIET_DAYS).coerceIn(0, 30),
                trialAskDays = o.optInt("trialAskDays", TrialConsent.ASK_BEFORE.toDays().toInt()).coerceIn(1, 30),
                fetchedAt = now,
            )
        }

        fun fromSaved(text: String?): LaunchConfig? = runCatching {
            val o = JSONObject(text ?: return null)
            parse(o, Instant.parse(o.getString("fetchedAt")))
        }.getOrNull()
    }
}

/** [MON-02] The last [LaunchConfig] on this phone; asked again once a day. */
class RemoteConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("launch_config", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(LaunchConfig.fromSaved(prefs.getString(KEY, null)) ?: LaunchConfig())
    val config: StateFlow<LaunchConfig> = _config.asStateFlow()
    val current: LaunchConfig get() = _config.value

    /** Due when never asked, a day has passed, or the clock went back. */
    fun due(now: Instant): Boolean {
        val at = current.fetchedAt ?: return true
        return now.isBefore(at) || Duration.between(at, now) >= EVERY
    }

    fun save(c: LaunchConfig) {
        _config.value = c
        prefs.edit().putString(KEY, c.toJson().toString()).apply()
    }

    /** Asks the server when due; no answer (offline, a sign-in page) keeps the last one. */
    suspend fun refresh(api: TierApi, now: Instant = Instant.now(), force: Boolean = false): Boolean {
        if (!api.enabled || (!force && !due(now))) return false
        val fresh = try {
            api.config(now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        save(fresh)
        return true
    }

    companion object {
        private const val KEY = "config"
        val EVERY: Duration = Duration.ofDays(1)
    }
}
