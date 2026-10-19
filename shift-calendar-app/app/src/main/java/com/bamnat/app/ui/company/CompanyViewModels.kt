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

    /** Sends a post; [done] gets what to tell the user when it was not taken. */
    fun send(text: String, done: (String?) -> Unit) {
        val o = _open.value ?: return done(null)
        CompanyText.personal(text)?.let { return done(talkProblem(it)) }
        scope.launch {
            val error = try {
                c.companyApi.post(c.companyStore.device, o.company, o.setup, text)
                c.telemetry.event("company_post", "sent")
                null
            } catch (e: CompanyException) {
                talkProblem(e.code)
            }
            if (error == null) reload()
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
    "own" -> "내 글은 신고할 수 없어요"
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

    init {
        viewModelScope.launch { _terms.value = c.loadTerms() }
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
        viewModelScope.launch {
            val done = try {
                val b = c.companyApi.board(adopted, from, q)
                f.value.copy(items = f.value.items + b.items, next = b.next, loading = false)
            } catch (e: CompanyException) {
                f.value.copy(loading = false, error = companyProblem(e.code, e.field))
            }
            // A page for a name no longer in the box is dropped; the new name's pages are on their way.
            if (q == asked) f.value = done
        }
    }
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
    fun send(company: String, dept: String, verified: Boolean, note: String) {
        CompanyText.personal(company)?.let { _sending.value = Sending.Failed(it, "company"); return }
        CompanyText.personal(dept)?.let { _sending.value = Sending.Failed(it, "dept"); return }
        CompanyText.personal(note)?.let { _sending.value = Sending.Failed(it, "note"); return }
        if (CompanyText.norm(company).length < 2) { _sending.value = Sending.Failed("company", "company"); return }
        _sending.value = Sending.Busy
        viewModelScope.launch {
            _sending.value = try {
                val status = c.companyApi.report(c.companyStore.device, company, dept, verified, note, c.companyShare.exportForServer())
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
    fun forgetAll(done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { c.companyApi.forgetMine(c.companyStore.device) }.isSuccess
            if (ok) {
                c.companyStore.forgetSent()
                _statuses.value = emptyMap()
                // [ADMIN-02] The server deleted this phone's questions with the rest.
                c.inquiryStore.update(emptyList())
                c.inquiryStore.asked = false
            }
            done(ok)
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
 * [EXTRA-06][COMM-04] A company way the app cannot set yet, sent to the developer with this phone's
 * setup (no amounts). The answer shows in 내가 보낸 정보. Null when it went, else what to tell the user.
 */
suspend fun AppContainer.askDeveloper(company: String, note: String): String? {
    CompanyText.personal(company)?.let { return companyProblem(it, "company") }
    CompanyText.personal(note)?.let { return companyProblem(it, "note") }
    if (CompanyText.norm(company).length < 2) return companyProblem("company")
    return try {
        val status = companyApi.report(companyStore.device, company, "", false, note.take(1500), companyShare.exportForServer(), ask = true)
        companyStore.addSent(SentReport(status.id, status.company, status.dept, ask = true, at = Instant.now()))
        telemetry.event("company_ask", if (status.again) "again" else "new")
        null
    } catch (e: CompanyException) {
        companyProblem(e.code, e.field)
    }
}
