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
    /** [PLZ-08] Phones that applied it, phones that sent it, checks against a pay slip, and the names senders chose. */
    val uses: Int = 0,
    val senders: Int = 0,
    val checked: Int = 0,
    val by: List<String> = emptyList(),
) {
    val hasPay: Boolean get() = runCatching { JSONObject(text).has("pay") }.getOrDefault(false)
}

data class CompanyPage(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val setups: List<SharedSetup>,
    /** [PLZ-12] company, hospital or topic (a whole job's rule), its job plaza and 시·도 code. */
    val kind: String = "company",
    val job: String = "",
    val region: String = "",
)

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
    /** [PLZ-12] company, hospital or topic, the job plaza and 시·도 code. */
    val kind: String = "company",
    val job: String = "",
    val region: String = "",
    /** [PLZ-08] Phones using it and the names its senders chose. */
    val uses: Int = 0,
    val by: List<String> = emptyList(),
)

data class Board(val items: List<BoardItem>, val next: Int?)

/** [PLZ-09] One line of a proposal: what (a [Plaza.CHANGE_FIELDS] key), how it is now, how it should be, why. */
data class ChangeLine(val field: String, val now: String, val want: String, val why: String)

/** [PLZ-02] Where a proposal's setup stands. */
data class ProposalState(val setup: String, val adopted: Boolean, val persons: Int, val threshold: Int)

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
    /** [PLZ-08] The name the writer chose, shown instead of 참여자 N. */
    val nick: String? = null,
    /** [PLZ-05] The first post this one answers. */
    val parent: String? = null,
    /** [PLZ-09] talk, question or proposal. */
    val kind: String = "talk",
    val change: List<ChangeLine> = emptyList(),
    /** [PLZ-01] 맞아요·달라요 on the post, its trust 0~100 and what this phone said. */
    val agree: Int = 0,
    val disagree: Int = 0,
    val trust: Int = 0,
    val myVote: Boolean? = null,
    val proposal: ProposalState? = null,
    /** [COMM-09] The writer sealed by the server, different on every post; kept to hide this writer. Null on one's own. */
    val author: String? = null,
)

/**
 * [PLZ-04] [past]: an older version's talk, read only since a newer version took its place.
 * [COMM-09] [hiddenByYou]: posts left out because the user chose not to see their writers.
 */
data class Talk(val posts: List<TalkPost>, val mutedUntil: Instant? = null, val past: Boolean = false, val hiddenByYou: Int = 0)

data class PostVote(val agree: Int, val disagree: Int, val trust: Int, val myVote: Boolean?)

/** [PLZ-12] One job plaza and how much is in it. */
data class PlazaJob(val key: String, val name: String, val adopted: Int, val voting: Int, val rates: Int)

/** [PLZ-04] One version of a department's rule: how it came in, when, what changed and whether it is in use. */
data class RuleVersion(
    val version: Int,
    val setup: String,
    val source: String,
    val adoptedAt: Instant,
    val state: String,
    val senders: Int,
    val post: String?,
    val added: List<String>,
    val removed: List<String>,
)

/** [PLZ-14] A market rate someone sent: per [unit] day, hour or month, with its 맞아요·달라요. */
data class Rate(
    val id: String,
    val job: String,
    val region: String,
    val trade: String,
    val unit: String,
    val year: Int,
    val amount: Long,
    val note: String,
    val status: String,
    val agree: Int,
    val disagree: Int,
    val trust: Int,
    val mine: Boolean,
    val myVote: Boolean?,
    val nick: String?,
    val at: Instant,
)

/** [PLZ-15] The adopted rates of one kind: low, middle and high of [n]. */
data class RateGroup(val job: String, val region: String, val trade: String, val unit: String, val year: Int, val n: Int, val low: Long, val mid: Long, val high: Long)

data class RateBoard(val groups: List<RateGroup>, val entries: List<Rate>)

/** [PLZ-08] 내 활동: what this phone sent and where it stands. */
data class Activity(
    val nick: String?,
    val reports: List<ActivityReport>,
    val proposals: List<ActivityProposal>,
    val rates: List<Rate>,
    val rewards: List<ActivityReward>,
)

data class ActivityReport(val id: String, val companyId: String, val company: String, val dept: String, val setup: String, val status: String,
    val version: Int?, val persons: Int, val bad: Int, val uses: Int, val at: Instant)

data class ActivityProposal(val id: String, val companyId: String, val company: String, val setup: String, val body: String, val agree: Int,
    val disagree: Int, val trust: Int, val at: Instant)

data class ActivityReward(val months: Int, val status: String, val why: String, val at: Instant)

/**
 * [PLZ-12][PLZ-14] The plaza's fixed lists, the same as the server's: job plazas (결정 D38), 시·도 by
 * 통계청 code, what a proposal can be about, and what a rate is per with the amounts it may be.
 */
object Plaza {
    val JOBS = linkedMapOf(
        "shift" to "교대 근무", "factory" to "제조·생산", "nurse" to "간호사·병원", "public" to "공무원", "police" to "경찰", "fire" to "소방",
        "care" to "요양·돌봄", "security" to "경비·시설", "transport" to "운수·운전", "hourly" to "알바·시급", "construction" to "건설 일용직·기능공",
        "field" to "현장·프리랜서", "shop" to "자영업",
    )
    val REGIONS = linkedMapOf(
        "11" to "서울", "21" to "부산", "22" to "대구", "23" to "인천", "24" to "광주", "25" to "대전", "26" to "울산", "29" to "세종",
        "31" to "경기", "32" to "강원", "33" to "충북", "34" to "충남", "35" to "전북", "36" to "전남", "37" to "경북", "38" to "경남", "39" to "제주",
    )
    val CHANGE_FIELDS = linkedMapOf(
        "shift" to "근무 시간", "order" to "근무 순서", "break" to "휴게", "overtime" to "연장 근무", "night" to "야간", "holiday" to "휴일 근무",
        "allowance" to "수당", "bonus" to "상여", "leave" to "연차·휴가", "other" to "그 밖의 것",
    )
    data class Unit(val name: String, val min: Long, val max: Long)
    val RATE_UNITS = linkedMapOf(
        "day" to Unit("일당", 50_000, 1_000_000),
        "hour" to Unit("시급", 5_000, 200_000),
        "month" to Unit("월급", 500_000, 20_000_000),
    )

    fun job(key: String): String = JOBS[key] ?: ""
    fun region(key: String): String = REGIONS[key] ?: "전국"
    fun unit(key: String): String = RATE_UNITS[key]?.name ?: key
}

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
    /** [PLZ-12] A report that names its job plaza and 시·도, or with [topic] a rule for a whole job, not one company. */
    suspend fun report(device: String, company: String, dept: String, verified: Boolean, note: String, setup: JSONObject, job: String,
        region: String, topic: Boolean): ReportStatus = report(device, company, dept, verified, note, setup)
    suspend fun status(id: String): ReportStatus?
    suspend fun takeBack(device: String, id: String)
    suspend fun forgetMine(device: String): Int
    suspend fun vote(device: String, company: String, setup: String, ok: Boolean, verified: Boolean = false, why: String? = null): VoteResult

    /**
     * [COMM-07] Every company's rule sets: [adopted] ones anyone can use, or ones still being voted on.
     * [q] finds a company by name, written any way 회사 찾기 takes it.
     */
    suspend fun board(adopted: Boolean, from: Int = 0, q: String = "", job: String = "", region: String = "", sort: String = "recent"): Board =
        Board(emptyList(), null)
    /** [COMM-06] The talk on one rule set; this install's own posts are marked. */
    suspend fun talk(device: String, company: String, setup: String): Talk = Talk(emptyList())
    /** Posts in it; the new post's id comes back. */
    suspend fun post(device: String, company: String, setup: String, body: String): String = throw CompanyException("offline")
    suspend fun deletePost(device: String, id: String) = Unit
    /** 신고: abuse, personal, spam or false. */
    suspend fun flag(device: String, id: String, reason: String) = Unit

    /**
     * [PLZ-05][PLZ-09] A post with what it is: an answer to [parent], a question, or a proposal with its
     * [change] lines and the whole setup as it should be ([proposal], amounts left out).
     */
    suspend fun post(device: String, company: String, setup: String, body: String, parent: String?, kind: String,
        change: List<ChangeLine>, proposal: JSONObject?): String =
        if (parent == null && kind == "talk") post(device, company, setup, body) else throw CompanyException("offline")
    /** [PLZ-01] 맞아요 (true), 달라요 (false) or taking it back (null) on someone's post. */
    suspend fun postVote(device: String, id: String, ok: Boolean?): PostVote = throw CompanyException("offline")
    /** [PLZ-08] This phone uses (applied) a rule set, or stopped; how many use it comes back. */
    suspend fun use(device: String, company: String, setup: String, on: Boolean): Int = 0
    /** [PLZ-08] The name this phone is shown by on the plaza; empty for none. */
    suspend fun nick(device: String, nick: String): String? = null
    /** [PLZ-12] The job plazas with how much is in each. */
    suspend fun plazas(): List<PlazaJob> = emptyList()
    /** [PLZ-04] Every version a department's rule went through, newest first. */
    suspend fun history(company: String, setup: String): List<RuleVersion> = emptyList()
    /** [PLZ-14][PLZ-15] 임금표 of a job (and region, unit). */
    suspend fun rates(device: String, job: String, region: String = "", unit: String = "", trade: String = ""): RateBoard = RateBoard(emptyList(), emptyList())
    suspend fun addRate(device: String, job: String, region: String, trade: String, unit: String, amount: Long, note: String): String =
        throw CompanyException("offline")
    suspend fun rateVote(device: String, id: String, ok: Boolean?): PostVote = throw CompanyException("offline")
    /** Takes a rate back; an adopted one stays as everyone's (true comes back). */
    suspend fun takeRateBack(device: String, id: String): Boolean = false
    /** [PLZ-08] 내 활동. */
    suspend fun activity(device: String): Activity = Activity(null, emptyList(), emptyList(), emptyList(), emptyList())
}

/**
 * [COMM-04] The company rules server (server/company-worker). Nothing here carries an amount: the
 * settings file goes through CompanyShare.exportForServer, and the install id is random, made on
 * this phone and never tied to a person.
 */
class CompanyClient(
    private val baseUrl: String = BuildConfig.COMPANY_BASE_URL,
    /** [COMM-09] The X-Hide header: the writers this phone hides, or null. */
    private val hidden: () -> String? = { null },
) : CompanyApi {
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
            kind = o.optString("kind", "company"),
            job = o.optString("job"),
            region = o.optString("region"),
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
                    uses = s.optInt("uses"),
                    senders = s.optInt("senders"),
                    checked = s.optInt("checked"),
                    by = s.optJSONArray("by").strings(),
                )
            },
        )
    }

    override suspend fun terms(): CompanyTerms = serverCall {
        val o = request("GET", "/api/terms")
        CompanyTerms(o.optInt("threshold", 3), o.optInt("rewardMonths", 0), o.optInt("agree", 75))
    }

    override suspend fun board(adopted: Boolean, from: Int, q: String, job: String, region: String, sort: String): Board = serverCall {
        val name = q.trim().take(40).takeIf { it.isNotEmpty() }?.let { "&q=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val plaza = (if (job.isNotEmpty()) "&job=$job" else "") + (if (region.isNotEmpty()) "&region=$region" else "") + (if (sort != "recent") "&sort=$sort" else "")
        val o = request("GET", "/api/board?tab=${if (adopted) "adopted" else "voting"}&from=$from$name$plaza")
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
                    kind = c.optString("kind", "company"), job = c.optString("job"), region = c.optString("region"),
                    uses = i.optInt("uses"), by = i.optJSONArray("by").strings(),
                )
            },
            next = if (o.has("next") && !o.isNull("next")) o.getInt("next") else null,
        )
    }

    override suspend fun talk(device: String, company: String, setup: String): Talk = serverCall {
        val o = serverRequest(baseUrl, "GET", "/api/companies/$company/setups/$setup/posts", device = device,
            headers = hidden()?.let { mapOf("X-Hide" to it) }.orEmpty())
        Talk(
            posts = o.optJSONArray("posts").objects().map { p ->
                TalkPost(
                    id = p.getString("id"), who = p.optInt("who"), stance = p.optStringOrNull("stance"), verified = p.optBoolean("verified"),
                    body = p.optString("body"), at = Instant.parse(p.getString("at")), mine = p.optBoolean("mine"),
                    hidden = p.optJSONObject("hidden")?.optString("text"),
                    nick = p.optStringOrNull("nick"), parent = p.optStringOrNull("parent"), kind = p.optString("kind", "talk"),
                    change = p.optJSONArray("change").objects().map { ChangeLine(it.optString("field"), it.optString("now"), it.optString("want"), it.optString("why")) },
                    agree = p.optInt("agree"), disagree = p.optInt("disagree"), trust = p.optInt("trust"),
                    myVote = if (p.has("myVote") && !p.isNull("myVote")) p.getBoolean("myVote") else null,
                    proposal = p.optJSONObject("proposal")?.let { r ->
                        ProposalState(r.optString("setup"), r.optString("status") == "adopted", r.optInt("persons"), r.optInt("threshold", 3))
                    },
                    author = p.optStringOrNull("author")?.takeIf { com.bamnat.core.plaza.HideList.MARK.matches(it) },
                )
            },
            mutedUntil = o.optStringOrNull("mutedUntil")?.let(Instant::parse),
            past = o.optBoolean("past"),
            hiddenByYou = o.optInt("hiddenByYou"),
        )
    }

    override suspend fun post(device: String, company: String, setup: String, body: String, parent: String?, kind: String,
        change: List<ChangeLine>, proposal: JSONObject?): String = serverCall {
        val o = JSONObject().put("device", device).put("company", company).put("setup", setup).put("body", body.trim()).put("kind", kind)
        parent?.let { o.put("parent", it) }
        if (kind == "proposal") {
            o.put("change", JSONArray(change.map { JSONObject().put("field", it.field).put("now", it.now.trim()).put("want", it.want.trim()).put("why", it.why.trim()) }))
            proposal?.let { o.put("proposal", it) }
        }
        request("POST", "/api/posts", o).getString("id")
    }

    override suspend fun postVote(device: String, id: String, ok: Boolean?): PostVote = serverCall {
        vote(request("POST", "/api/posts/$id/vote", JSONObject().put("device", device).put("ok", ok ?: JSONObject.NULL)))
    }

    override suspend fun use(device: String, company: String, setup: String, on: Boolean): Int = serverCall {
        request(if (on) "POST" else "DELETE", "/api/uses", JSONObject().put("device", device).put("company", company).put("setup", setup)).optInt("uses")
    }

    override suspend fun nick(device: String, nick: String): String? = serverCall {
        request("POST", "/api/nick", JSONObject().put("device", device).put("nick", nick.trim())).optStringOrNull("nick")
    }

    override suspend fun plazas(): List<PlazaJob> = serverCall {
        request("GET", "/api/plazas").optJSONArray("jobs").objects().map {
            PlazaJob(it.getString("key"), it.optString("name"), it.optInt("adopted"), it.optInt("voting"), it.optInt("rates"))
        }
    }

    override suspend fun history(company: String, setup: String): List<RuleVersion> = serverCall {
        request("GET", "/api/companies/$company/history?setup=$setup").optJSONArray("versions").objects().map { v ->
            val ch = v.optJSONObject("changes")
            RuleVersion(v.getInt("version"), v.optString("setup"), v.optString("source"), Instant.parse(v.getString("adoptedAt")), v.optString("state"),
                v.optInt("senders"), v.optStringOrNull("post"), ch?.optJSONArray("added").lines(), ch?.optJSONArray("removed").lines())
        }
    }

    override suspend fun rates(device: String, job: String, region: String, unit: String, trade: String): RateBoard = serverCall {
        val q = listOf("job" to job, "region" to region, "unit" to unit, "trade" to trade).filter { it.second.isNotEmpty() }
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val o = request("GET", "/api/rates?$q", device = device)
        RateBoard(
            groups = o.optJSONArray("groups").objects().map { g ->
                RateGroup(g.optString("job"), g.optString("region"), g.optString("trade"), g.optString("unit"), g.optInt("year"), g.optInt("n"),
                    g.optLong("low"), g.optLong("mid"), g.optLong("high"))
            },
            entries = o.optJSONArray("entries").objects().map(::rate),
        )
    }

    override suspend fun addRate(device: String, job: String, region: String, trade: String, unit: String, amount: Long, note: String): String = serverCall {
        request("POST", "/api/rates", JSONObject().put("device", device).put("job", job).put("region", region).put("trade", trade.trim())
            .put("unit", unit).put("amount", amount).put("note", note.trim())).getString("id")
    }

    override suspend fun rateVote(device: String, id: String, ok: Boolean?): PostVote = serverCall {
        vote(request("POST", "/api/rates/$id/vote", JSONObject().put("device", device).put("ok", ok ?: JSONObject.NULL)))
    }

    override suspend fun takeRateBack(device: String, id: String): Boolean = serverCall {
        try {
            request("DELETE", "/api/rates/$id", device = device).optBoolean("kept")
        } catch (e: CompanyException) {
            if (e.code != "not_found") throw e
            false
        }
    }

    override suspend fun activity(device: String): Activity = serverCall {
        val o = request("GET", "/api/activity", device = device)
        Activity(
            nick = o.optStringOrNull("nick"),
            reports = o.optJSONArray("reports").objects().map { r ->
                val c = r.getJSONObject("company")
                ActivityReport(r.getString("id"), c.getString("id"), c.optString("name"), r.optString("dept"), r.optString("setup"), r.optString("status"),
                    if (r.has("version") && !r.isNull("version")) r.getInt("version") else null, r.optInt("persons"), r.optInt("bad"), r.optInt("uses"),
                    Instant.parse(r.getString("at")))
            },
            proposals = o.optJSONArray("proposals").objects().map { p ->
                val c = p.getJSONObject("company")
                ActivityProposal(p.getString("id"), c.getString("id"), c.optString("name"), p.optString("setup"), p.optString("body"), p.optInt("agree"),
                    p.optInt("disagree"), p.optInt("trust"), Instant.parse(p.getString("at")))
            },
            rates = o.optJSONArray("rates").objects().map(::rate),
            rewards = o.optJSONArray("rewards").objects().map { w ->
                ActivityReward(w.optInt("months"), w.optString("status"), w.optString("why"), Instant.parse(w.getString("at")))
            },
        )
    }

    private fun vote(o: JSONObject) = PostVote(o.optInt("agree"), o.optInt("disagree"), o.optInt("trust"),
        if (o.has("myVote") && !o.isNull("myVote")) o.getBoolean("myVote") else null)

    private fun rate(r: JSONObject) = Rate(
        id = r.getString("id"), job = r.optString("job"), region = r.optString("region"), trade = r.optString("trade"), unit = r.optString("unit"),
        year = r.optInt("year"), amount = r.optLong("amount"), note = r.optString("note"), status = r.optString("status"), agree = r.optInt("agree"),
        disagree = r.optInt("disagree"), trust = r.optInt("trust"), mine = r.optBoolean("mine"),
        myVote = if (r.has("myVote") && !r.isNull("myVote")) r.getBoolean("myVote") else null, nick = r.optStringOrNull("nick"),
        at = Instant.parse(r.getString("at")),
    )

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
    ): ReportStatus = send(device, company, dept, verified, note, setup, ask, "", "", false)

    override suspend fun report(device: String, company: String, dept: String, verified: Boolean, note: String, setup: JSONObject, job: String,
        region: String, topic: Boolean): ReportStatus = send(device, company, dept, verified, note, setup, false, job, region, topic)

    private suspend fun send(device: String, company: String, dept: String, verified: Boolean, note: String, setup: JSONObject, ask: Boolean,
        job: String, region: String, topic: Boolean): ReportStatus = serverCall {
        val body = JSONObject()
            .put("v", 1).put("device", device).put("company", company.trim()).put("dept", dept.trim())
            .put("verified", verified).put("note", note.trim()).put("setup", setup)
        if (ask) body.put("ask", true)
        if (job.isNotEmpty()) body.put("job", job)
        if (region.isNotEmpty()) body.put("region", region)
        if (topic) body.put("topic", true)
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
internal fun serverRequest(
    baseUrl: String, method: String, path: String, body: JSONObject? = null, device: String? = null, headers: Map<String, String> = emptyMap(),
): JSONObject {
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
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
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

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** [PLZ-04] The changed lines of a rule, [label, text] pairs from the server, as "label: text". */
internal fun JSONArray?.lines(): List<String> = if (this == null) emptyList() else (0 until length()).map { i ->
    optJSONArray(i)?.let { "${it.optString(0)}: ${it.optString(1)}" } ?: optString(i)
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
