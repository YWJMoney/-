package com.bamnat.app.company

import com.bamnat.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant

/** A company found by name [COMM-01]: how many setups coworkers agreed on and how many are being checked. */
data class CompanyHit(val id: String, val name: String, val alias: String?, val adopted: Int, val pending: Int)

/**
 * One setup coworkers shared for a company [COMM-01][COMM-03]: adopted, or still gathering points.
 * [text] is the company settings file as CompanyShare reads it; [lines] say it in plain words.
 */
data class SharedSetup(
    val id: String,
    val adopted: Boolean,
    val points: Int,
    val persons: Int,
    val bad: Int,
    val threshold: Int,
    val dept: String,
    val version: Int?,
    val text: String,
    val lines: List<Pair<String, String>>,
    /** [COMM-07] 0~100: the share who said 맞았어요 among all who voted, times how full the points are. */
    val trust: Int = 0,
    /** The share who said 맞았어요, as a percent. */
    val agree: Int = 0,
    /** [COMM-06] Posts in the talk on this setup. */
    val posts: Int = 0,
) {
    val hasPay: Boolean get() = runCatching { JSONObject(text).has("pay") }.getOrDefault(false)
}

data class CompanyPage(val id: String, val name: String, val aliases: List<String>, val setups: List<SharedSetup>)

/** What a setup needs to be adopted and what adoption earns, said before anyone sends [COMM-03]. */
data class CompanyTerms(val threshold: Int, val rewardMonths: Int, val agree: Int = 75)

/** [COMM-07] One company's rule set on 회사 규칙 광장: still being voted on, or adopted for anyone to use. */
data class BoardItem(
    val companyId: String,
    val company: String,
    val setup: String,
    val dept: String,
    val adopted: Boolean,
    val points: Int,
    val persons: Int,
    val bad: Int,
    val threshold: Int,
    val trust: Int,
    val agree: Int,
    val posts: Int,
    val version: Int?,
    val at: Instant,
    /** The shifts in a line, the rotation's length ("순서 8일") and whether pay rules come with it. */
    val gist: String,
    val cycle: String,
    val pay: Boolean,
)

data class Board(val items: List<BoardItem>, val next: Int?)

/**
 * [COMM-06] A post in the talk on a rule set. [who] is 참여자 N of this talk only; [stance] what they said
 * about the rule set: sender (올린 분), ok (맞았어요), bad (달라요) or null. [hidden] says why it is hidden,
 * shown only to its writer.
 */
data class TalkPost(
    val id: String,
    val who: Int,
    val stance: String?,
    val verified: Boolean,
    val body: String,
    val at: Instant,
    val mine: Boolean,
    val hidden: String? = null,
)

data class Talk(val posts: List<TalkPost>, val mutedUntil: Instant? = null)

data class Reward(val months: Int, val status: String) {
    val owed: Boolean get() = status == "owed"
    val paid: Boolean get() = status == "paid"
}

/** Where one report stands [COMM-02][COMM-03][EXTRA-06]. */
data class ReportStatus(
    val id: String,
    /** pending, adopted, rejected, replaced, withdrawn, ask (a way the app cannot set yet) or answered. */
    val status: String,
    val ask: Boolean,
    val companyId: String,
    val company: String,
    val dept: String,
    val setup: String,
    val verified: Boolean,
    val points: Int,
    val persons: Int,
    val threshold: Int,
    /** Enough coworkers agree and the developer is checking it. */
    val review: Boolean,
    val createdAt: Instant,
    val decidedAt: Instant?,
    /** Why it was turned down, or the developer's answer to a way the app cannot set yet. */
    val reason: String?,
    val reasonCode: String?,
    val reward: Reward?,
    val rewardMonths: Int,
    /** The same setup was sent from this phone before; this is that report. */
    val again: Boolean = false,
)

data class VoteResult(val points: Int, val persons: Int, val bad: Int, val threshold: Int, val adopted: Boolean, val trust: Int = 0, val agree: Int = 0)

/** A refusal from the server: [code] is its error word (amount, phone, rate_limited, offline …), [field] what it was about. */
class CompanyException(val code: String, val field: String? = null) : IOException(code)

/** [COMM-04] What the app asks of the company rules server; screenshots and tests answer with a fake. */
interface CompanyApi {
    /** False in a build without a server address: no 회사 찾기 and no sending, files still go by hand. */
    val enabled: Boolean
    suspend fun search(query: String): List<CompanyHit>
    suspend fun company(id: String): CompanyPage
    suspend fun terms(): CompanyTerms
    suspend fun report(device: String, company: String, dept: String, verified: Boolean, note: String, setup: JSONObject, ask: Boolean = false): ReportStatus
    suspend fun status(id: String): ReportStatus?
    suspend fun takeBack(device: String, id: String)
    suspend fun forgetMine(device: String): Int
    suspend fun vote(device: String, company: String, setup: String, ok: Boolean, verified: Boolean = false, why: String? = null): VoteResult

    /**
     * [COMM-07] Every company's rule sets: [adopted] ones anyone can use, or ones still being voted on.
     * [q] finds a company by name, written any way 회사 찾기 takes it.
     */
    suspend fun board(adopted: Boolean, from: Int = 0, q: String = ""): Board = Board(emptyList(), null)
    /** [COMM-06] The talk on one rule set; this install's own posts are marked. */
    suspend fun talk(device: String, company: String, setup: String): Talk = Talk(emptyList())
    /** Posts in it; the new post's id comes back. */
    suspend fun post(device: String, company: String, setup: String, body: String): String = throw CompanyException("offline")
    suspend fun deletePost(device: String, id: String) = Unit
    /** 신고: abuse, personal, spam or false. */
    suspend fun flag(device: String, id: String, reason: String) = Unit
}

/**
 * [COMM-04] The company rules server (server/company-worker). Nothing here carries an amount: the
 * settings file goes through CompanyShare.exportForServer, and the install id is random, made on
 * this phone and never tied to a person.
 */
class CompanyClient(private val baseUrl: String = BuildConfig.COMPANY_BASE_URL) : CompanyApi {
    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun search(query: String): List<CompanyHit> = serverCall {
        if (CompanyText.norm(query).length < 2) return@serverCall emptyList()
        val o = request("GET", "/api/companies?q=" + URLEncoder.encode(query.trim().take(40), "UTF-8"))
        o.optJSONArray("companies").objects().map { c ->
            CompanyHit(c.getString("id"), c.getString("name"), c.optStringOrNull("alias"), c.optInt("adopted"), c.optInt("pending"))
        }
    }

    /** A company and its setups; one merged into another opens as that one. */
    override suspend fun company(id: String): CompanyPage = serverCall {
        var o = request("GET", "/api/companies/$id")
        o.optStringOrNull("mergedInto")?.let { o = request("GET", "/api/companies/$it") }
        CompanyPage(
            id = o.getString("id"),
            name = o.getString("name"),
            aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            setups = o.optJSONArray("setups").objects().map { s ->
                SharedSetup(
                    id = s.getString("id"),
                    adopted = s.optString("status") == "adopted",
                    points = s.optInt("points"),
                    persons = s.optInt("persons"),
                    bad = s.optInt("bad"),
                    threshold = s.optInt("threshold", 3),
                    dept = s.optString("dept"),
                    version = if (s.has("version")) s.getInt("version") else null,
                    text = s.getJSONObject("setup").toString(),
                    lines = s.optJSONArray("lines")?.let { a ->
                        (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
                    }.orEmpty(),
                    trust = s.optInt("trust"),
                    agree = s.optInt("agree"),
                    posts = s.optInt("posts"),
                )
            },
        )
    }

    override suspend fun terms(): CompanyTerms = serverCall {
        val o = request("GET", "/api/terms")
        CompanyTerms(o.optInt("threshold", 3), o.optInt("rewardMonths", 0), o.optInt("agree", 75))
    }

    override suspend fun board(adopted: Boolean, from: Int, q: String): Board = serverCall {
        val name = q.trim().take(40).takeIf { it.isNotEmpty() }?.let { "&q=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val o = request("GET", "/api/board?tab=${if (adopted) "adopted" else "voting"}&from=$from$name")
        Board(
            items = o.optJSONArray("items").objects().map { i ->
                val c = i.getJSONObject("company")
                BoardItem(
                    companyId = c.getString("id"), company = c.getString("name"), setup = i.getString("id"), dept = i.optString("dept"),
                    adopted = i.optString("status") == "adopted", points = i.optInt("points"), persons = i.optInt("persons"),
                    bad = i.optInt("bad"), threshold = i.optInt("threshold", 3), trust = i.optInt("trust"), agree = i.optInt("agree"),
                    posts = i.optInt("posts"), version = if (i.has("version") && !i.isNull("version")) i.getInt("version") else null,
                    at = i.optStringOrNull("at")?.let(Instant::parse) ?: Instant.EPOCH, gist = i.optString("gist"),
                    cycle = i.optString("cycle"), pay = i.optBoolean("pay"),
                )
            },
            next = if (o.has("next") && !o.isNull("next")) o.getInt("next") else null,
        )
    }

    override suspend fun talk(device: String, company: String, setup: String): Talk = serverCall {
        val o = request("GET", "/api/companies/$company/setups/$setup/posts", device = device)
        Talk(
            posts = o.optJSONArray("posts").objects().map { p ->
                TalkPost(
                    id = p.getString("id"), who = p.optInt("who"), stance = p.optStringOrNull("stance"), verified = p.optBoolean("verified"),
                    body = p.optString("body"), at = Instant.parse(p.getString("at")), mine = p.optBoolean("mine"),
                    hidden = p.optJSONObject("hidden")?.optString("text"),
                )
            },
            mutedUntil = o.optStringOrNull("mutedUntil")?.let(Instant::parse),
        )
    }

    override suspend fun post(device: String, company: String, setup: String, body: String): String = serverCall {
        request("POST", "/api/posts", JSONObject().put("device", device).put("company", company).put("setup", setup).put("body", body.trim()))
            .getString("id")
    }

    override suspend fun deletePost(device: String, id: String) = serverCall {
        try {
            request("DELETE", "/api/posts/$id", device = device)
        } catch (e: CompanyException) {
            if (e.code != "not_found") throw e
        }
        Unit
    }

    override suspend fun flag(device: String, id: String, reason: String) = serverCall {
        request("POST", "/api/posts/$id/flag", JSONObject().put("device", device).put("reason", reason))
        Unit
    }

    /** Sends a setup for coworkers, or with [ask] a way the app cannot set yet for the developer to read. */
    override suspend fun report(
        device: String, company: String, dept: String, verified: Boolean, note: String, setup: JSONObject, ask: Boolean,
    ): ReportStatus = serverCall {
        val body = JSONObject()
            .put("v", 1).put("device", device).put("company", company.trim()).put("dept", dept.trim())
            .put("verified", verified).put("note", note.trim()).put("setup", setup)
        if (ask) body.put("ask", true)
        status(request("POST", "/api/reports", body))
    }

    /** Null when the server no longer has it: taken back, or deleted with everything this phone sent. */
    override suspend fun status(id: String): ReportStatus? = serverCall {
        try {
            status(request("GET", "/api/reports/$id"))
        } catch (e: CompanyException) {
            if (e.code == "not_found") null else throw e
        }
    }

    /** Takes a report back with its reward; one already gone counts as taken back. */
    override suspend fun takeBack(device: String, id: String) = serverCall {
        try {
            request("DELETE", "/api/reports/$id", device = device)
        } catch (e: CompanyException) {
            if (e.code != "not_found") throw e
        }
        Unit
    }

    /** Deletes everything this install sent: reports, rewards not yet given, 맞았어요·달라요, posts and 신고. */
    override suspend fun forgetMine(device: String): Int = serverCall {
        request("DELETE", "/api/mine", device = device).optInt("reports")
    }

    /** 맞았어요 ([ok]) or 달라요 with what was wrong: wrong, old or name. */
    override suspend fun vote(device: String, company: String, setup: String, ok: Boolean, verified: Boolean, why: String?): VoteResult =
        serverCall {
            val body = JSONObject().put("device", device).put("company", company).put("setup", setup).put("ok", ok)
            if (ok && verified) body.put("verified", true)
            if (!ok && why != null) body.put("why", why)
            val o = request("POST", "/api/votes", body)
            VoteResult(o.optInt("points"), o.optInt("persons"), o.optInt("bad"), o.optInt("threshold", 3), o.optString("status") == "adopted",
                o.optInt("trust"), o.optInt("agree"))
        }

    private fun status(o: JSONObject): ReportStatus {
        val c = o.getJSONObject("company")
        return ReportStatus(
            id = o.getString("id"),
            status = o.getString("status"),
            ask = o.optBoolean("ask"),
            companyId = c.getString("id"),
            company = c.getString("name"),
            dept = o.optString("dept"),
            setup = o.optString("setup"),
            verified = o.optBoolean("verified"),
            points = o.optInt("points"),
            persons = o.optInt("persons"),
            threshold = o.optInt("threshold", 3),
            review = o.optBoolean("review"),
            createdAt = Instant.parse(o.getString("createdAt")),
            decidedAt = o.optStringOrNull("decidedAt")?.let(Instant::parse),
            reason = o.optJSONObject("reason")?.optString("text")?.takeIf { it.isNotEmpty() },
            reasonCode = o.optJSONObject("reason")?.optString("code"),
            reward = o.optJSONObject("reward")?.let { Reward(it.getInt("months"), it.getString("status")) },
            rewardMonths = o.optInt("rewardMonths"),
            again = o.optBoolean("again"),
        )
    }

    private fun request(method: String, path: String, body: JSONObject? = null, device: String? = null): JSONObject =
        serverRequest(baseUrl, method, path, body, device)
}

/**
 * Runs a server call off the main thread. An answer that is not what the app expects (a Wi-Fi sign-in
 * page in place of JSON, a missing field) is [CompanyException] "bad_response", never a crash.
 */
internal suspend fun <T> serverCall(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: JSONException) {
        throw CompanyException("bad_response")
    } catch (e: DateTimeException) {
        throw CompanyException("bad_response")
    }
}

/**
 * One call to the company server; [device] goes in the X-Device header. A refusal comes back as
 * [CompanyException] with the server's error word, no connection as "offline". [ADMIN-02] shares it.
 */
internal fun serverRequest(baseUrl: String, method: String, path: String, body: JSONObject? = null, device: String? = null): JSONObject {
    val conn = try {
        URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
    } catch (e: IOException) {
        throw CompanyException("offline")
    }
    try {
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        device?.let { conn.setRequestProperty("X-Device", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code >= 400) {
            val err = runCatching { JSONObject(text) }.getOrNull()
            throw CompanyException(err?.optString("error")?.takeIf { it.isNotEmpty() } ?: "http_$code", err?.optStringOrNull("field"))
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    } catch (e: CompanyException) {
        throw e
    } catch (e: IOException) {
        throw CompanyException("offline")
    } finally {
        conn.disconnect()
    }
}

/**
 * [COMM-01][COMM-02] The server's own rules for names and typed text, checked on the phone first so
 * a refusal never surprises: the same patterns as normName and personal in server/company-worker.
 */
object CompanyText {
    private val forms = Regex("\\((주|株|유|사|재|합)\\)|주식회사|유한회사|유한책임회사|합자회사|합명회사|사단법인|재단법인|\\b(co|ltd|inc|corp|llc)\\b\\.?")
    private val marks = Regex("[^\\p{L}\\p{N}]")
    private val personal = listOf(
        "email" to Regex("[^\\s@]+@[^\\s@]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE),
        "phone" to Regex("(\\+?82[\\s.-]?0?|0)\\d{1,2}[\\s.-]?\\d{3,4}[\\s.-]?\\d{4}"),
        "amount" to Regex(
            "\\d[\\d,.]*\\s*(원|만\\s*원|천\\s*원|억\\s*원|won\\b|krw\\b)|[일이삼사오육칠팔구십백천]+\\s*만\\s*원|\\d{1,3}(,\\d{3})+|\\d{5,}",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** A name as search sees it: no spaces, marks or company forms such as (주), 주식회사, Co., Ltd. */
    fun norm(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase().replace(forms, "").replace(marks, "")

    /** Why [text] cannot be shared: email, phone or amount; null when it can. */
    fun personal(text: String): String? = personal.firstOrNull { (_, re) -> re.containsMatchIn(text) }?.first

    /** What to tell the user about a refused text or field. */
    fun problem(code: String): String = when (code) {
        "email" -> "메일 주소는 적지 말아 주세요"
        "phone" -> "전화번호는 적지 말아 주세요"
        "amount" -> "금액은 적지 말아 주세요. ‘기본급의 10%’처럼 비율로 적으면 돼요"
        else -> "보낼 수 없는 내용이 있어요"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
