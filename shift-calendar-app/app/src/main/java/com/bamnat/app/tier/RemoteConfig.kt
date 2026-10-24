package com.bamnat.app.tier

import android.content.Context
import com.bamnat.core.ops.AppNotice
import com.bamnat.core.ops.ServerSwitch
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
import com.bamnat.core.time.Today

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
    /** [SRV-04] The developer app's notices, shown one at a time over the calendar. */
    val notices: List<AppNotice> = emptyList(),
    /** [SRV-04] Features the developer paused; the app shows them as resting and does not call the server for them. */
    val off: Set<ServerSwitch> = emptySet(),
) {
    fun paused(s: ServerSwitch): Boolean = s in off

    fun toJson(): JSONObject = JSONObject().put("billingLive", billingLive).put("launchedAt", launchedAt?.toString() ?: JSONObject.NULL)
        .put("thanksMonths", thanksMonths).put("freeFeatures", JSONArray(freeFeatures.map { it.name }))
        .put("adQuietDays", adQuietDays).put("trialAskDays", trialAskDays).put("fetchedAt", fetchedAt?.toString() ?: JSONObject.NULL)
        .put("notices", JSONArray(notices.map { n ->
            JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("level", if (n.important) "important" else "info")
                .put("from", Instant.ofEpochMilli(n.from).toString()).put("until", Instant.ofEpochMilli(n.until).toString())
                .put("minApp", n.minApp ?: JSONObject.NULL).put("maxApp", n.maxApp ?: JSONObject.NULL)
                .put("audience", n.audience?.let { a ->
                    JSONObject().put("makers", JSONArray(a.makers.toList())).put("minSdk", a.minSdk ?: JSONObject.NULL)
                        .put("maxSdk", a.maxSdk ?: JSONObject.NULL).put("workTypes", JSONArray(a.workTypes.toList()))
                        .put("features", JSONArray(a.features.toList()))
                } ?: JSONObject.NULL)
                .put("action", n.action?.let { x -> JSONObject().put("kind", x.kind).put("label", x.label ?: JSONObject.NULL).put("url", x.url ?: JSONObject.NULL) }
                    ?: JSONObject.NULL)
        }))
        .put("off", JSONArray(off.map { it.key }))

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
                notices = o.optJSONArray("notices")?.let { a -> (0 until a.length()).map { notice(a.getJSONObject(it)) } }.orEmpty().take(AppNoticesMax),
                // A switch of a newer server is skipped; this app has nothing of it to pause.
                off = o.optJSONArray("off")?.let { a -> (0 until a.length()).mapNotNull { ServerSwitch.of(a.getString(it)) } }.orEmpty().toSet(),
            )
        }

        private const val AppNoticesMax = com.bamnat.core.ops.AppNotices.MAX

        /** [SRV-04] One notice, strictly: a wrong shape fails the whole answer and the last good one stays. */
        private fun notice(n: JSONObject): AppNotice {
            val id = n.getString("id")
            if (!Regex("^n[A-Za-z0-9]{9}$").matches(id)) throw JSONException("id")
            val version = { k: String -> if (!n.has(k) || n.isNull(k)) null else n.getInt(k) }
            return AppNotice(
                id = id, title = n.getString("title").take(40), body = n.getString("body").take(500),
                important = n.getString("level") == "important",
                from = Instant.parse(n.getString("from")).toEpochMilli(), until = Instant.parse(n.getString("until")).toEpochMilli(),
                minApp = version("minApp"), maxApp = version("maxApp"),
                audience = n.optJSONObject("audience")?.let(::audience),
                action = n.optJSONObject("action")?.let(::action),
            )
        }

        /** [SRV-09] Parts this app does not know are left out, so a newer server's notice is not lost. */
        private fun audience(a: JSONObject): com.bamnat.core.ops.NoticeAudience {
            fun set(k: String) = a.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }.orEmpty()
            fun int(k: String) = if (!a.has(k) || a.isNull(k)) null else a.getInt(k)
            return com.bamnat.core.ops.NoticeAudience(set("makers").map { it.lowercase() }.toSet(), int("minSdk"), int("maxSdk"), set("workTypes"), set("features"))
        }

        /** [SRV-09] A button this app cannot open is dropped; an address must be https. */
        private fun action(x: JSONObject): com.bamnat.core.ops.NoticeAction? {
            val kind = x.optString("kind").takeIf { it in com.bamnat.core.ops.NoticeAction.KINDS } ?: return null
            val url = if (x.isNull("url")) null else x.optString("url").takeIf { it.startsWith("https://") }
            if (kind == "URL" && url == null) return null
            val label = if (x.isNull("label")) null else x.optString("label").takeIf { it.isNotBlank() }?.take(12)
            return com.bamnat.core.ops.NoticeAction(kind, label, url)
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
    suspend fun refresh(api: TierApi, now: Instant = Today.instant(), force: Boolean = false): Boolean {
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
