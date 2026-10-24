package com.bamnat.app.ui.company

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.company.AppliedSetup
import com.bamnat.app.company.CompanyDraft
import com.bamnat.app.company.CompanyException
import com.bamnat.app.company.CompanyHit
import com.bamnat.app.company.CompanyPage
import com.bamnat.app.company.CompanyTerms
import com.bamnat.app.company.CompanyText
import com.bamnat.app.company.ReportStatus
import com.bamnat.app.company.SentReport
import com.bamnat.app.company.SharedSetup
import com.bamnat.app.data.BackupException
import com.bamnat.app.data.CompanyShare
import com.bamnat.app.data.CompanySummary
import com.bamnat.app.ui.pay.hourlyWeek
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.pay.WageType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Terms are the same for every screen and rarely change: read once per run. */
private object TermsCache {
    @Volatile var value: CompanyTerms? = null
}

private suspend fun AppContainer.loadTerms(): CompanyTerms? =
    TermsCache.value ?: runCatching { companyApi.terms() }.getOrNull()?.also { TermsCache.value = it }

/** [COMM-01] 회사 찾기: the name as it is typed, what the server found and the way on when nothing fits. */
@OptIn(FlowPreview::class)
class CompanyFindViewModel(private val c: AppContainer) : ViewModel() {
    sealed interface Result {
        /** Fewer than two letters typed. */
        data object Idle : Result
        data object Searching : Result
        data class Found(val query: String, val hits: List<CompanyHit>) : Result
        data class Failed(val query: String, val code: String) : Result
    }

    val query = MutableStateFlow("")
    private val again = MutableStateFlow(0)
    private val _result = MutableStateFlow<Result>(Result.Idle)
    val result: StateFlow<Result> = _result.asStateFlow()
    private val _terms = MutableStateFlow(TermsCache.value)
    val terms: StateFlow<CompanyTerms?> = _terms.asStateFlow()

    init {
        viewModelScope.launch { _terms.value = c.loadTerms() }
        viewModelScope.launch {
            combine(query, again) { q, _ -> q.trim() }.collectLatest { q ->
                if (CompanyText.norm(q).length < 2) {
                    _result.value = Result.Idle
                    return@collectLatest
                }
                // A pause in typing before asking, so each letter is not a request.
                delay(300)
                _result.value = Result.Searching
                _result.value = try {
                    Result.Found(q, c.companyApi.search(q))
                } catch (e: CompanyException) {
                    Result.Failed(q, e.code)
                }
            }
        }
    }

    fun retry() {
        again.value++
    }

    /** Remembers the company typed for the setup by hand, so the user can share it once it is done. */
    fun byHand(name: String) {
        val n = name.trim().take(40)
        if (CompanyText.norm(n).length >= 2) c.companyStore.setDraft(CompanyDraft(n))
        c.telemetry.event("company_by_hand", if (n.isEmpty()) "blank" else "named")
    }
}

/** One choice of where today falls in a shared rotation: the next week from it. */
data class Phase(val step: Int, val days: List<ShiftType?>)

/**
 * A shared rotation and today's place in it, for "오늘은 어떤 근무예요?". [current] is the row the
 * calendar already shows; [sent] the row of whoever sent the file, with their [team] when they wrote it.
 */
data class Phases(
    val options: List<Phase>,
    val cycle: Int,
    val current: Int?,
    val sent: Int? = null,
    val team: String? = null,
    val hasPattern: Boolean = false,
)

/**
 * [COMM-01] The rotation of a company file as rows of the next seven days, one per place today could
 * be in it. [fromSender] marks the row the sender's own start day gives [SETUP-02]: right for the same 조.
 */
internal suspend fun rotationPhases(c: AppContainer, text: String, fromSender: Boolean, today: LocalDate = LocalDate.now()): Phases? {
    val root = JSONObject(text)
    val rotation = root.optJSONObject("rotation") ?: return null
    val steps = rotation.optJSONArray("steps") ?: return null
    val names = (0 until steps.length()).map { if (steps.isNull(it)) null else steps.getString(it) }
    if (names.isEmpty()) return null
    val snapshot = c.scheduleRepository.snapshot.first()
    // The colors and marks the user already gave a shift of the same name, else the file's own.
    val mine = snapshot.shiftTypes.associateBy { it.name }
    val types = root.optJSONArray("shiftTypes")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
            val name = o.getString("name")
            name to (mine[name] ?: ShiftType(
                id = 0, name = name, label = o.optString("label").ifEmpty { name.take(1) },
                colorArgb = o.optLong("color", 0xFF5C6BC0), start = null, end = null, isOff = o.optBoolean("off"),
                icon = if (o.has("icon") && !o.isNull("icon")) o.getString("icon") else null,
            ))
        }
    }.orEmpty()
    val n = names.size
    val orderFrom = { i: Int -> (0 until n).map { names[(i + it) % n] } }
    val seen = HashSet<List<String?>>()
    val options = (0 until n).mapNotNull { i ->
        if (!seen.add(orderFrom(i))) return@mapNotNull null
        Phase(i, (0 until 7).map { d -> names[(i + d) % n]?.let(types::get) })
    }
    // The user's own order, not the calendar with its swaps and leave, says where today falls.
    val typeNames = snapshot.shiftTypes.associate { it.id to it.name }
    val week = (0 until 7).map { d ->
        val date = today.plusDays(d.toLong())
        PatternTimeline.on(snapshot.patterns, date)?.shiftTypeIdOn(date)?.let(typeNames::get)
    }
    val current = if (snapshot.hasPattern) options.firstOrNull { o -> o.days.map { it?.name } == week }?.step else null
    val anchor = rotation.optString("anchor").takeIf { fromSender && it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val sent = anchor?.let { a ->
        val order = orderFrom(Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(a, today), n.toLong()).toInt())
        options.firstOrNull { orderFrom(it.step) == order }?.step
    }
    val team = rotation.optString("team").trim().take(CompanyShare.MAX_TEAM).takeIf { fromSender && it.isNotEmpty() }
    return Phases(options, n, current, sent, team, snapshot.hasPattern)
}

/** [COMM-01] A company's shared setups, and applying one from today's place in its rotation. */
class CompanyDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Loaded(val page: CompanyPage) : State
        data class Failed(val code: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    val applied: StateFlow<AppliedSetup?> = c.companyStore.applied
    val hasPattern: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(null).also { f ->
        viewModelScope.launch { f.value = c.scheduleRepository.snapshot.first().hasPattern }
    }
    val hasPay: Boolean get() = c.payStore.profile.value != null

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            _state.value = try {
                State.Loaded(c.companyApi.company(id))
            } catch (e: CompanyException) {
                State.Failed(e.code)
            }
        }
    }

    /** The rotation of [setup] as rows of the next seven days, with today's row when the calendar already shows it. */
    suspend fun phases(setup: SharedSetup): Phases? = rotationPhases(c, setup.text, fromSender = false)

    /**
     * Applies [setup] with today at [todayStep] of its rotation. What is still to do comes back:
     * the user's own amounts when there was no pay setup yet.
     */
    fun apply(page: CompanyPage, setup: SharedSetup, todayStep: Int?, done: (CompanyShare.Applied) -> Unit, failed: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = c.companyShare.apply(setup.text, rotation = true, todayStep = todayStep)
                // [PLZ-08] 쓰는 사람: this phone now uses this rule set, and no longer the one before.
                val before = c.companyStore.applied.value
                if (before != null && before.setup != setup.id) launch { runCatching { c.companyApi.use(c.companyStore.device, before.company, before.setup, false) } }
                launch { runCatching { c.companyApi.use(c.companyStore.device, page.id, setup.id, true) } }
                c.companyStore.setApplied(AppliedSetup(page.id, page.name, setup.dept, setup.id, setup.version, setup.adopted, Instant.now()))
                c.companyStore.setDraft(null)
                c.companyStore.pendingPay = if (result.pendingPay) setup.text else null
                c.telemetry.event("company_apply", if (setup.adopted) "adopted" else "pending")
                done(result)
            } catch (e: BackupException) {
                failed(e.message ?: "설정을 넣지 못했어요")
            } catch (e: org.json.JSONException) {
                // A setup the server sent that this app cannot read is a message, not a crash.
                failed("이 설정을 읽지 못했어요. 앱을 업데이트한 뒤 다시 해 주세요")
            }
        }
    }

    /** The company found, but not the user's own department: they set up by hand under its name. */
    fun byHand(page: CompanyPage) = c.companyStore.setDraft(CompanyDraft(page.name))

    /** [COMM-07] What this phone said about each setup on this screen: 맞았어요 (true) or 달라요. */
    private val _votes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val votes: StateFlow<Map<String, Boolean>> = _votes.asStateFlow()

    /** 맞았어요 or 달라요 on any setup here, applied or not; the page comes back with the new points and trust. */
    fun vote(page: CompanyPage, setup: SharedSetup, ok: Boolean, why: String? = null, done: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = try {
                c.companyApi.vote(c.companyStore.device, page.id, setup.id, ok, why = why)
                _votes.value = _votes.value + (setup.id to ok)
                if (c.companyStore.applied.value?.setup == setup.id) c.companyStore.voted(ok)
                c.telemetry.event("company_vote", if (ok) "ok_board" else why ?: "wrong")
                _state.value = runCatching { State.Loaded(c.companyApi.company(id)) }.getOrDefault(_state.value)
                null
            } catch (e: CompanyException) {
                companyProblem(e.code, e.field)
            }
            done(error)
        }
    }

    /** [COMM-06] The talk on one setup, while its sheet is open. */
    val talk = TalkHolder(c, viewModelScope)

    /** [PLZ-10] 내 월급으로 시험 of one setup, while its sheet is open. */
    private val _trial = MutableStateFlow<Trial?>(null)
    val trial: StateFlow<Trial?> = _trial.asStateFlow()

    fun tryPay(setup: SharedSetup, month: YearMonth = YearMonth.now()) {
        _trial.value = Trial(setup.id, month, loading = true)
        viewModelScope.launch { _trial.value = payTrial(c, setup.text, setup.id, month) }
    }

    fun closeTrial() {
        _trial.value = null
    }

    /** [PLZ-04] The versions of one department's rule, while its sheet is open. */
    private val _history = MutableStateFlow<HistoryState?>(null)
    val history: StateFlow<HistoryState?> = _history.asStateFlow()

    fun showHistory(page: CompanyPage, setup: SharedSetup) {
        _history.value = HistoryState(setup.dept.ifEmpty { "회사 전체" }, null)
        viewModelScope.launch {
            _history.value = try {
                HistoryState(setup.dept.ifEmpty { "회사 전체" }, c.companyApi.history(page.id, setup.id))
            } catch (e: CompanyException) {
                HistoryState(setup.dept.ifEmpty { "회사 전체" }, null, companyProblem(e.code, e.field))
            }
        }
    }

    fun closeHistory() {
        _history.value = null
    }
}

data class HistoryState(val title: String, val versions: List<com.bamnat.app.company.RuleVersion>?, val error: String? = null)

/**
 * [PLZ-10] One month's pay worked out on the phone twice, with the user's own rules and with a shared
 * setup's, line by line. Nothing leaves the phone (결정 D36); [note] says why it could not be done.
 */
data class Trial(
    val setup: String,
    val month: YearMonth,
    val loading: Boolean = false,
    val now: Long = 0,
    val then: Long = 0,
    val lines: List<TrialLine> = emptyList(),
    val note: String? = null,
) {
    val diff: Long get() = then - now
}

data class TrialLine(val label: String, val now: Long, val then: Long)

internal suspend fun payTrial(c: AppContainer, text: String, setup: String, month: YearMonth): Trial {
    val mine = c.payStore.profile.value ?: return Trial(setup, month, note = "내 급여 설정이 없어 비교할 수 없어요. 급여 설정을 먼저 해 주세요")
    val trial = try {
        c.companyShare.trial(text)
    } catch (e: Exception) {
        null
    } ?: return Trial(setup, month, note = "이 규칙에는 급여 계산 방식이 없어 근무표만 비교할 수 있어요")
    val snapshot = c.scheduleRepository.snapshot.first()
    val settings = c.settingsRepository.current
    val calc = com.bamnat.core.pay.PayCalculator()
    return runCatching {
        val a = calc.calculate(mine.hourlyWeek(settings), month, snapshot.resolver::resolve, checks = false)
        val b = calc.calculate(trial.hourlyWeek(settings), month, snapshot.resolver::resolve, checks = false)
        val nowBy = a.lines.groupBy { it.label }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val thenBy = b.lines.groupBy { it.label }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val labels = (nowBy.keys + thenBy.keys).toList()
        Trial(setup, month, now = a.total, then = b.total,
            lines = labels.map { TrialLine(it, nowBy[it] ?: 0, thenBy[it] ?: 0) }.sortedByDescending { kotlin.math.abs(it.then - it.now) })
    }.getOrElse { Trial(setup, month, note = "이 달은 계산하지 못했어요") }
}

/**
 * [COMM-06] The talk on one rule set: its posts, writing one, taking one's own back and 신고. Shared by
 * the company screen and 회사 규칙 광장.
 */
class TalkHolder(private val c: AppContainer, private val scope: kotlinx.coroutines.CoroutineScope) {
    data class Open(val company: String, val setup: String, val title: String, val talk: com.bamnat.app.company.Talk? = null, val error: String? = null)

    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    fun show(company: String, setup: String, title: String) {
        _open.value = Open(company, setup, title)
        reload()
    }

    fun close() {
        _open.value = null
    }

    /** [SRV-04] The developer paused writing in the plaza: the talk is read only for now. */
    val writePaused: Boolean get() = c.remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE)

    /** [COMM-09] 이 사람 글 안 보기: the writer's posts in every talk go, on this phone only. */
    fun hide(p: com.bamnat.app.company.TalkPost) {
        val mark = p.author ?: return
        c.settingsRepository.update { s ->
            s.copy(plazaHidden = com.bamnat.core.plaza.HideList.decode(s.plazaHidden).hide(mark, p.nick ?: "참여자 ${p.who}", System.currentTimeMillis()).encode())
        }
        c.telemetry.event("plaza_hide")
        reload()
    }

    fun reload() {
        val o = _open.value ?: return
        scope.launch {
            val next = try {
                o.copy(talk = c.companyApi.talk(c.companyStore.device, o.company, o.setup), error = null)
            } catch (e: CompanyException) {
                o.copy(error = companyProblem(e.code, e.field))
            }
            if (_open.value?.setup == o.setup) _open.value = next
        }
    }

    /**
     * Sends a post; [done] gets what to tell the user when it was not taken. [parent] answers a post,
     * [kind] is talk, question or proposal; a proposal carries its [change] lines and, with [withSetup],
     * this phone's own setup (amounts left out) as the rule should be ([PLZ-09]).
     */
    fun send(
        text: String,
        parent: String? = null,
        kind: String = "talk",
        change: List<com.bamnat.app.company.ChangeLine> = emptyList(),
        withSetup: Boolean = false,
        done: (String?) -> Unit,
    ) {
        val o = _open.value ?: return done(null)
        CompanyText.personal(text)?.let { return done(talkProblem(it)) }
        change.flatMap { listOf(it.now, it.want, it.why) }.firstNotNullOfOrNull(CompanyText::personal)?.let { return done(talkProblem(it)) }
        scope.launch {
            val error = try {
                val setup = if (kind == "proposal" && withSetup) c.companyShare.exportForServer() else null
                if (parent == null && kind == "talk") c.companyApi.post(c.companyStore.device, o.company, o.setup, text)
                else c.companyApi.post(c.companyStore.device, o.company, o.setup, text, parent, kind, change, setup)
                c.telemetry.event("company_post", if (kind == "proposal") "proposal" else if (parent != null) "reply" else "sent")
                null
            } catch (e: CompanyException) {
                talkProblem(e.code)
            }
            if (error == null) reload()
            done(error)
        }
    }

    /** [PLZ-01] 맞아요 (true) or 달라요 (false) on someone's post; the same again takes it back. */
    fun vote(id: String, ok: Boolean, done: (String?) -> Unit = {}) {
        val o = _open.value ?: return
        val post = o.talk?.posts?.firstOrNull { it.id == id } ?: return
        val next = if (post.myVote == ok) null else ok
        scope.launch {
            val error = try {
                val v = c.companyApi.postVote(c.companyStore.device, id, next)
                val now = _open.value
                if (now?.setup == o.setup && now.talk != null) {
                    _open.value = now.copy(talk = now.talk.copy(posts = now.talk.posts.map {
                        if (it.id == id) it.copy(agree = v.agree, disagree = v.disagree, trust = v.trust, myVote = v.myVote) else it
                    }))
                }
                // A proposal people agree with can be adopted by this vote; its state comes back with the talk.
                if (post.proposal != null) reload()
                null
            } catch (e: CompanyException) {
                talkProblem(e.code)
            }
            done(error)
        }
    }

    fun delete(id: String) {
        scope.launch {
            runCatching { c.companyApi.deletePost(c.companyStore.device, id) }
            reload()
        }
    }

    fun flag(id: String, reason: String, done: (String?) -> Unit) {
        scope.launch {
            val error = try {
                c.companyApi.flag(c.companyStore.device, id, reason)
                null
            } catch (e: CompanyException) {
                talkProblem(e.code)
            }
            done(error)
            reload()
        }
    }
}

/** What to say when a post is refused. */
fun talkProblem(code: String): String = when (code) {
    "amount" -> "금액은 빼 주세요. ‘기본급의 10%’, ‘1.5배’처럼 비율로 적으면 돼요"
    "phone" -> "전화번호는 빼 주세요"
    "email" -> "메일 주소는 빼 주세요"
    "body" -> "두 글자 이상, 500자 안으로 적어 주세요"
    "muted" -> "운영 원칙을 지키지 않은 글이 있어 지금은 글을 쓸 수 없어요"
    "rate_limited" -> "오늘은 글을 더 쓸 수 없어요. 내일 다시 써 주세요"
    "own" -> "내 글에는 할 수 없어요"
    "parent" -> "답글을 달 글이 지워졌어요"
    "change" -> "무엇을 어떻게 바꾸자는지 한 줄 이상 적어 주세요"
    "same" -> "지금 규칙과 같은 설정이에요. 앱 설정을 실제와 맞게 고친 뒤 보내 주세요"
    "paused" -> com.bamnat.core.ops.ServerSwitch.pausedText(com.bamnat.core.ops.ServerSwitch.PLAZA_WRITE)
    else -> companyProblem(code)
}

/** [COMM-07] 회사 규칙 광장: every company's rule sets, ones being voted on or adopted ones, a page at a time. */
class CompanyBoardViewModel(private val c: AppContainer) : ViewModel() {
    data class Tab(val items: List<com.bamnat.app.company.BoardItem> = emptyList(), val next: Int? = 0, val loading: Boolean = false, val error: String? = null)

    val enabled: Boolean = c.companyApi.enabled
    private val _adopted = MutableStateFlow(false)
    val adopted: StateFlow<Boolean> = _adopted.asStateFlow()
    private val tabs = mapOf(false to MutableStateFlow(Tab()), true to MutableStateFlow(Tab()))
    val voting: StateFlow<Tab> = tabs.getValue(false).asStateFlow()
    val chosen: StateFlow<Tab> = tabs.getValue(true).asStateFlow()
    private val _terms = MutableStateFlow(TermsCache.value)
    val terms: StateFlow<CompanyTerms?> = _terms.asStateFlow()
    val talk = TalkHolder(c, viewModelScope)
    /** [COMM-07] A company's name, as typed; both tabs show only its rules once the typing stops. */
    val query = MutableStateFlow("")
    private var asked = ""

    /** [PLZ-12][PLZ-05] The job plaza and 시·도 shown (empty for all), and the order. */
    data class Filter(val job: String = "", val region: String = "", val sort: String = "recent")
    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()
    private val _plazas = MutableStateFlow<List<com.bamnat.app.company.PlazaJob>>(emptyList())
    val plazas: StateFlow<List<com.bamnat.app.company.PlazaJob>> = _plazas.asStateFlow()

    fun setFilter(f: Filter) {
        if (f == _filter.value) return
        _filter.value = f
        tabs.values.forEach { it.value = Tab() }
        more()
    }

    /** [PLZ-08] 내 활동 and the plaza name, while its sheet is open. */
    val activity = ActivityHolder(c, viewModelScope)

    init {
        viewModelScope.launch { _terms.value = c.loadTerms() }
        viewModelScope.launch { _plazas.value = runCatching { c.companyApi.plazas() }.getOrDefault(emptyList()) }
        more(false)
        viewModelScope.launch {
            query.collectLatest { typed ->
                val q = typed.trim()
                if (q == asked) return@collectLatest
                delay(300)
                asked = q
                tabs.values.forEach { it.value = Tab() }
                more()
            }
        }
    }

    fun pick(adopted: Boolean) {
        _adopted.value = adopted
        if (tabs.getValue(adopted).value.items.isEmpty()) more(adopted)
    }

    fun refresh() {
        val a = _adopted.value
        tabs.getValue(a).value = Tab()
        more(a)
    }

    /** The next page of a tab; nothing when it is loading or has no more. */
    fun more(adopted: Boolean = _adopted.value) {
        val f = tabs.getValue(adopted)
        val from = f.value.next ?: return
        if (f.value.loading) return
        f.value = f.value.copy(loading = true, error = null)
        val q = asked
        val fl = _filter.value
        viewModelScope.launch {
            val done = try {
                val b = c.companyApi.board(adopted, from, q, fl.job, fl.region, fl.sort)
                f.value.copy(items = f.value.items + b.items, next = b.next, loading = false)
            } catch (e: CompanyException) {
                f.value.copy(loading = false, error = companyProblem(e.code, e.field))
            }
            // A page for a name no longer in the box is dropped; the new name's pages are on their way.
            if (q == asked && fl == _filter.value) f.value = done
        }
    }
}

/** [PLZ-08] 내 활동: what this phone sent to the plaza, and the name it is shown by. */
class ActivityHolder(private val c: AppContainer, private val scope: kotlinx.coroutines.CoroutineScope) {
    data class Open(val activity: com.bamnat.app.company.Activity? = null, val error: String? = null)

    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    fun show() {
        _open.value = Open()
        scope.launch {
            _open.value = try {
                Open(c.companyApi.activity(c.companyStore.device))
            } catch (e: CompanyException) {
                Open(error = companyProblem(e.code, e.field))
            }
        }
    }

    fun close() {
        _open.value = null
    }

    /** Sets the plaza name, or none when blank; [done] gets what to tell the user. */
    fun setNick(nick: String, done: (String?) -> Unit) {
        val t = nick.trim()
        if (t.isNotEmpty() && (t.length < 2 || t.length > 12)) return done("별명은 2~12자로 정해 주세요")
        CompanyText.personal(t)?.let { return done(talkProblem(it)) }
        scope.launch {
            val error = try {
                val saved = c.companyApi.nick(c.companyStore.device, t)
                _open.value = _open.value?.let { o -> o.copy(activity = o.activity?.copy(nick = saved)) }
                null
            } catch (e: CompanyException) {
                when (e.code) {
                    "taken" -> "다른 분이 쓰는 별명이에요"
                    "nick" -> "쓸 수 없는 별명이에요. 관리자·개발자·운영자 같은 말은 빼 주세요"
                    else -> talkProblem(e.code)
                }
            }
            done(error)
        }
    }
}

/**
 * [PLZ-14][PLZ-15] 임금표: what a trade is paid per day, hour or month in a job and region, as people
 * shared it, with the adopted ones' middle as 광장 시세. Sending one asks no name or pay slip.
 */
class RatesViewModel(private val c: AppContainer) : ViewModel() {
    data class Filter(val job: String = "construction", val region: String = "", val unit: String = "")
    data class State(val board: com.bamnat.app.company.RateBoard? = null, val loading: Boolean = true, val error: String? = null)

    val enabled: Boolean = c.companyApi.enabled
    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun setFilter(f: Filter) {
        _filter.value = f
        load()
    }

    fun load() {
        val f = _filter.value
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val next = try {
                State(c.companyApi.rates(c.companyStore.device, f.job, f.region, f.unit).also { b ->
                    c.rateHints.keep(f.region, b.groups)
                    b.entries.firstOrNull { it.mine }?.let { c.rateHints.shared(it.job, it.region, it.trade) }
                }, loading = false)
            } catch (e: CompanyException) {
                State(_state.value.board, loading = false, error = companyProblem(e.code, e.field))
            }
            if (f == _filter.value) _state.value = next
        }
    }

    /** Sends a rate; [done] gets what to tell the user when it was not taken, null when it was. */
    fun add(trade: String, unit: String, amount: Long, note: String, done: (String?) -> Unit) {
        val f = _filter.value
        val u = com.bamnat.app.company.Plaza.RATE_UNITS[unit] ?: return done("일당·시급·월급 가운데 골라 주세요")
        if (trade.trim().length < 2) return done("직종을 두 글자 이상 적어 주세요 (예: 형틀목수)")
        if (amount !in u.min..u.max) return done("${u.name}은 ${won(u.min)}~${won(u.max)} 사이로 적어 주세요")
        CompanyText.personal(trade)?.let { return done(talkProblem(it)) }
        CompanyText.personal(note)?.let { return done(talkProblem(it)) }
        viewModelScope.launch {
            val error = try {
                c.companyApi.addRate(c.companyStore.device, f.job, f.region, trade, unit, amount, note)
                c.rateHints.shared(f.job, f.region, trade)
                c.telemetry.event("plaza_rate", "sent")
                null
            } catch (e: CompanyException) {
                when (e.code) {
                    "adopted" -> "같은 시세가 이미 채택됐어요. 다른 분의 시세에 맞아요·달라요를 눌러 주세요"
                    "trade" -> "직종을 두 글자 이상, 20자 안으로 적어 주세요"
                    else -> talkProblem(e.code)
                }
            }
            if (error == null) load()
            done(error)
        }
    }

    fun vote(rate: com.bamnat.app.company.Rate, ok: Boolean) {
        viewModelScope.launch {
            runCatching { c.companyApi.rateVote(c.companyStore.device, rate.id, if (rate.myVote == ok) null else ok) }
            load()
        }
    }

    fun takeBack(rate: com.bamnat.app.company.Rate, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val kept = runCatching { c.companyApi.takeRateBack(c.companyStore.device, rate.id) }.getOrDefault(false)
            load()
            done(kept)
        }
    }

    /**
     * [PLZ-11] 광장 시세를 내 급여에: the middle of [g] becomes my 일당·시급·월급 from this month, when my pay is
     * paid that way. Earlier months keep what they were; undo is the pay screen's. [done] gets what to say.
     */
    fun applyToMyPay(g: com.bamnat.app.company.RateGroup, done: (String) -> Unit) {
        val p = c.payStore.profile.value ?: return done("급여 설정이 없어요. 급여 설정을 먼저 해 주세요")
        val want = when (g.unit) {
            "day" -> WageType.DAILY
            "hour" -> WageType.HOURLY
            else -> WageType.MONTHLY
        }
        if (p.wageType != want) return done("내 급여는 ${wageName(p.wageType)} 방식이라 ${com.bamnat.app.company.Plaza.unit(g.unit)}을 넣을 수 없어요")
        viewModelScope.launch {
            val from = YearMonth.now().atDay(1)
            c.payStore.save(p.copy(base = p.base.with(com.bamnat.core.pay.DatedValue(from, g.mid))))
            c.telemetry.event("plaza_rate", "applied")
            done("${from.monthValue}월부터 ${com.bamnat.app.company.Plaza.unit(g.unit)}을 ${won(g.mid)}으로 바꿨어요. 급여 화면에서 되돌릴 수 있어요")
        }
    }

    private fun wageName(w: WageType) = when (w) {
        WageType.DAILY -> "일당"
        WageType.HOURLY -> "시급"
        else -> "월급"
    }

    private fun won(v: Long) = "%,d원".format(v)
}

/** [COMM-01] The first pay setup from a shared setup: only the user's own amounts are asked. */
class CompanyPayViewModel(private val c: AppContainer) : ViewModel() {
    val text: String? = c.companyStore.pendingPay
    val company: String = c.companyStore.applied.value?.company.orEmpty()
    val needs: List<CompanyShare.AmountNeed> = text?.let { runCatching { c.companyShare.amountNeeds(it) }.getOrNull() }.orEmpty()
    val summary: CompanySummary? = text?.let { runCatching { c.companyShare.summarize(it) }.getOrNull() }
    /** A share of 기본급 bonus, e.g. 상여 짝수 달 100%: said so the user does not look for its amount. */
    val bonusNote: String? = text?.let { t ->
        runCatching {
            JSONObject(t).optJSONObject("pay")?.optJSONArray("items")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .filter { it.optString("cadence") == "BONUS" && it.has("percentOfBase") }
                    .joinToString(" · ") { o ->
                        val months = o.optJSONArray("months")?.let { m -> (0 until m.length()).map { m.getInt(it) } }.orEmpty()
                        "${o.optString("name")} ${months.joinToString("·")}월 기본급 ${com.bamnat.core.pay.PayCalculator.pct(o.getDouble("percentOfBase"))}"
                    }.ifEmpty { null }
            }
        }.getOrNull()
    }

    fun save(
        wageType: WageType, base: Long, rough: Boolean, dailyHours: Int, since: YearMonth, amounts: Map<String, Long>,
        done: () -> Unit, failed: (String) -> Unit,
    ) {
        val t = text ?: return failed("설정을 다시 골라 주세요")
        viewModelScope.launch {
            try {
                c.companyShare.createPay(t, wageType, base, rough, dailyHours, since, amounts)
                c.companyStore.pendingPay = null
                c.telemetry.event("pay_setup_done", "COMPANY")
                done()
            } catch (e: BackupException) {
                failed(e.message ?: "급여 설정을 만들지 못했어요")
            }
        }
    }
}

/** [COMM-02] Sending this phone's setup, and what became of everything it sent. */
class CompanyReportViewModel(private val c: AppContainer) : ViewModel() {
    sealed interface Sending {
        data object Idle : Sending
        data object Busy : Sending
        data class Sent(val status: ReportStatus) : Sending
        data class Failed(val code: String, val field: String?) : Sending
    }

    private val _terms = MutableStateFlow(TermsCache.value)
    val terms: StateFlow<CompanyTerms?> = _terms.asStateFlow()
    /** What goes, read from the very file that is sent. */
    private val _preview = MutableStateFlow<CompanySummary?>(null)
    val preview: StateFlow<CompanySummary?> = _preview.asStateFlow()
    private val _sending = MutableStateFlow<Sending>(Sending.Idle)
    val sending: StateFlow<Sending> = _sending.asStateFlow()
    val draft: StateFlow<CompanyDraft?> = c.companyStore.draft
    /** [PAYS-02] The month a payslip was matched on this phone; only the yes or no goes out, never the figures. */
    val payslipMonth: java.time.YearMonth? get() = c.payStore.profile.value?.verified?.month
    val applied: StateFlow<AppliedSetup?> = c.companyStore.applied
    val sent: StateFlow<List<SentReport>> = c.companyStore.sent
    private val _statuses = MutableStateFlow<Map<String, ReportStatus?>>(emptyMap())
    /** What the server says of each sent report; null when it no longer has one. */
    val statuses: StateFlow<Map<String, ReportStatus?>> = _statuses.asStateFlow()
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    init {
        viewModelScope.launch { _terms.value = c.loadTerms() }
        viewModelScope.launch {
            _preview.value = runCatching { c.companyShare.summarize(c.companyShare.exportForServer().toString()) }.getOrNull()
        }
    }

    /** Where the form starts: the company typed during setup or corrected, else the setup in use. */
    val start: CompanyDraft? = c.companyStore.draft.value
        ?: c.companyStore.applied.value?.let { CompanyDraft(it.company, it.dept) }

    /** Sends the setup; a text with an amount, phone or mail address is caught here before it goes. */
    fun send(company: String, dept: String, verified: Boolean, note: String, job: String = "", region: String = "", topic: Boolean = false) {
        if (topic && job.isEmpty()) { _sending.value = Sending.Failed("job", "job"); return }
        // [SRV-04] The developer paused new reports; nothing is sent and the form stays as written.
        if (c.remoteConfig.current.paused(com.bamnat.core.ops.ServerSwitch.COMPANY_REPORT)) { _sending.value = Sending.Failed("paused", "report"); return }
        if (!topic) CompanyText.personal(company)?.let { _sending.value = Sending.Failed(it, "company"); return }
        CompanyText.personal(dept)?.let { _sending.value = Sending.Failed(it, "dept"); return }
        CompanyText.personal(note)?.let { _sending.value = Sending.Failed(it, "note"); return }
        if (!topic && CompanyText.norm(company).length < 2) { _sending.value = Sending.Failed("company", "company"); return }
        _sending.value = Sending.Busy
        viewModelScope.launch {
            _sending.value = try {
                val setup = c.companyShare.exportForServer()
                // [PLZ-12][PLZ-13] Its job plaza and 시·도, or a rule for a whole job rather than one place.
                val status = if (job.isEmpty() && region.isEmpty() && !topic) c.companyApi.report(c.companyStore.device, company, dept, verified, note, setup)
                    else c.companyApi.report(c.companyStore.device, company, dept, verified, note, setup, job, region, topic)
                c.companyStore.addSent(SentReport(status.id, status.company, status.dept, ask = false, at = Instant.now()))
                _statuses.value = _statuses.value + (status.id to status)
                c.companyStore.setDraft(null)
                c.telemetry.event("company_report", listOf(if (verified) "verified" else "plain", if (start?.fix == true) "fix" else "share",
                    if (status.again) "again" else "new").joinToString(","))
                Sending.Sent(status)
            } catch (e: CompanyException) {
                Sending.Failed(e.code, e.field)
            }
        }
    }

    fun resetSending() {
        _sending.value = Sending.Idle
    }

    /** Looks up the newest reports; older ones keep what was last seen. */
    fun refresh() {
        viewModelScope.launch {
            var failed = false
            for (r in sent.value.take(20)) {
                try {
                    _statuses.value = _statuses.value + (r.id to c.companyApi.status(r.id))
                } catch (e: CompanyException) {
                    failed = true
                    break
                }
            }
            _offline.value = failed
        }
    }

    fun takeBack(id: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { c.companyApi.takeBack(c.companyStore.device, id) }.isSuccess
            if (ok) {
                c.companyStore.removeSent(id)
                _statuses.value = _statuses.value - id
            }
            done(ok)
        }
    }

    /** Everything this phone sent goes from the server; the setup in use stays on the phone. */
    fun forgetAll(done: (Boolean) -> Unit) = forgetAllSaying { ok, _ -> done(ok) }

    /** [INQ-05] I28: as [forgetAll], with what was deleted in words. */
    fun forgetAllSaying(done: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val counts = runCatching { c.companyApi.forgetMineCounts(c.companyStore.device) }.getOrNull()
            val ok = counts != null
            if (ok) {
                c.companyStore.forgetSent()
                _statuses.value = emptyMap()
                // [ADMIN-02] The server deleted this phone's questions with the rest.
                c.inquiryStore.update(emptyList())
                c.inquiryStore.asked = false
            }
            done(ok, counts?.words())
        }
    }
}

/**
 * [COMM-02][COMM-03] The small asks around a company setup, on the pay screen and in settings:
 * 맞았어요·달라요 on the shared setup in use, and the invitation to share a hand-made or corrected one.
 */
class CompanyNudgeViewModel(private val c: AppContainer) : ViewModel() {
    val enabled: Boolean = c.companyApi.enabled
    val applied: StateFlow<AppliedSetup?> = c.companyStore.applied
    val draft: StateFlow<CompanyDraft?> = c.companyStore.draft
    private val _terms = MutableStateFlow(TermsCache.value)
    val terms: StateFlow<CompanyTerms?> = _terms.asStateFlow()

    /** The setup in use while 맞았어요·달라요 is still to say and was not put off. */
    val askVote: StateFlow<AppliedSetup?> = combine(c.companyStore.applied, c.companyStore.voteLater) { a, later ->
        a?.takeIf { enabled && it.vote == null && System.currentTimeMillis() >= later }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A setup made or corrected by hand under a company's name and not shared yet. */
    val invite: StateFlow<CompanyDraft?> = combine(c.companyStore.draft, c.companyStore.inviteClosed) { d, closed ->
        d?.takeIf { enabled && !closed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        if (enabled) viewModelScope.launch { _terms.value = c.loadTerms() }
    }

    /** [why] for 달라요: wrong, old or name. [done] says whether the server took it. */
    fun vote(ok: Boolean, verified: Boolean = false, why: String? = null, done: (Boolean) -> Unit = {}) {
        val a = applied.value ?: return
        viewModelScope.launch {
            val sent = runCatching { c.companyApi.vote(c.companyStore.device, a.companyId, a.setup, ok, verified, why) }.isSuccess
            if (sent) {
                c.companyStore.voted(ok)
                c.telemetry.event("company_vote", if (ok) (if (verified) "ok_verified" else "ok") else why ?: "wrong")
            }
            done(sent)
        }
    }

    /** "나중에": asked again in a week. */
    fun later() = c.companyStore.voteLaterUntil(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)

    /** After 달라요 the user corrects the setup; the invitation then asks for the right one. */
    fun startFix() {
        val a = applied.value ?: return
        c.companyStore.setDraft(CompanyDraft(a.company, a.dept, fix = true))
        c.telemetry.event("company_fix_start")
    }

    fun closeInvite() {
        c.companyStore.closeInvite()
        c.telemetry.event("company_invite_close")
    }
}

/**
 * [EXTRA-06][COMM-04] A company way the app cannot set yet. Since 1.14.1 it is a question in the app
 * like any other [INQ-01]: the developer answers it with the rest in the developer app's 문의, and the
 * answer shows in 설정 > 문의하기. It is never public, since it names the workplace. [setup] is the
 * kind's settings in words, without an amount. Null when it went, else what to tell the user.
 */
suspend fun AppContainer.askDeveloper(company: String, how: String, example: String, setup: String? = null): String? {
    val text = "회사: ${company.trim()}\n${how.trim()}" + (if (example.isNotBlank()) "\n예시: ${example.trim()}" else "")
    CompanyText.personal(company)?.let { return companyProblem(it, "company") }
    CompanyText.personal(text)?.let { return companyProblem(it, "note") }
    if (CompanyText.norm(company).length < 2) return companyProblem("company")
    if (text.length > com.bamnat.app.company.InquiryClient.TEXT_MAX) return companyProblem("note")
    return try {
        inquiryApi.send(companyStore.device, com.bamnat.app.company.InquiryKind.COMPANY, text, deviceInquiryInfo().copy(setup = setup))
        inquiryStore.asked = true
        telemetry.event("company_ask", "inquiry")
        null
    } catch (e: CompanyException) {
        // The settings are made by the app; one the server will not take is a name that looks like an amount.
        if (e.field == "setup") "이 종류의 이름이나 설정에 금액·연락처처럼 보이는 글자가 있어요. 이름을 바꾼 뒤 보내 주세요"
        else companyProblem(e.code, if (e.field == "text") "note" else e.field)
    }
}

/** [ADMIN-02] What always goes with a question: this app and phone, shown on the form before it is sent. */
fun deviceInquiryInfo() = com.bamnat.app.company.InquiryInfo(
    app = "${com.bamnat.app.BuildConfig.VERSION_NAME} (${com.bamnat.app.BuildConfig.VERSION_CODE})",
    model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
    android = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
)
