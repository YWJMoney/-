package com.bamnat.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.company.CompanyException
import com.bamnat.app.company.CompanyText
import com.bamnat.app.company.Inquiry
import com.bamnat.app.company.InquiryClient
import com.bamnat.app.company.InquiryDraft
import com.bamnat.app.company.InquiryFlag
import com.bamnat.app.company.InquiryInfo
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.company.PublicPage
import com.bamnat.app.company.PublicThread
import com.bamnat.app.ui.diag.DiagnosticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ADMIN-02] 문의하기 in the app: questions this phone sent, the developer's answers, a new question
 * and what goes with it. Questions are found by the random install id the company setups use.
 */
class InquiryViewModel(private val c: AppContainer) : ViewModel() {
    private val _list = MutableStateFlow<List<Inquiry>?>(null)
    /** Null until the first fetch comes back. */
    val list: StateFlow<List<Inquiry>?> = _list.asStateFlow()

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val device get() = c.companyStore.device

    /** What always goes with a question; shown on the form before it is sent. */
    val info: InquiryInfo = com.bamnat.app.ui.company.deviceInquiryInfo()

    /** [ADMIN-02] Opened from 계산이 이상해요: the pay check to send, and the form comes first. */
    val payCheck: String? = c.pendingPayCheck.also { c.pendingPayCheck = null }

    /** [UX-05] The build has the company server: questions go in the app, else by mail. */
    val online: Boolean get() = c.inquiryApi.enabled

    /** [UX-05] The build can make and take calendar links, so the answer about receiving one is offered. */
    val shareOn: Boolean get() = c.shareApi.enabled

    /** [UX-05] What the screen that opened this asked for: the help answers, or the form started for it. */
    val start: InquiryStart? = InquiryLinks.take()

    /** [UX-05] 내 문의 opens first when the developer answered; else the help answers. */
    val startOnMine: Boolean = online && c.inquiryStore.unread.value > 0

    /** Answers not opened yet, for the 내 문의 tab. */
    val unread: StateFlow<Int> = c.inquiryStore.unread

    /** [UX-05] A question written and not sent; kept on the phone only. */
    val draft: StateFlow<InquiryDraft?> = c.inquiryStore.draft

    fun saveDraft(kind: InquiryKind?, text: String) = c.inquiryStore.saveDraft(kind, text)

    fun clearDraft() = c.inquiryStore.clearDraft()

    fun reply(id: String): String = c.inquiryStore.reply(id)

    fun saveReply(id: String, text: String) = c.inquiryStore.saveReply(id, text)

    /** [UX-05] New answers first, then questions still waiting, then the rest, newest first. */
    fun ordered(list: List<Inquiry>): List<Inquiry> =
        list.sortedWith(compareByDescending<Inquiry> { unreadIn(it) }.thenByDescending { it.waiting }.thenByDescending { it.updatedAt })

    private val _board = MutableStateFlow<PublicPage?>(null)
    /** 모두의 문의 as far as it is loaded; null until the first page comes back. */
    val board: StateFlow<PublicPage?> = _board.asStateFlow()

    private val _boardOffline = MutableStateFlow(false)
    val boardOffline: StateFlow<Boolean> = _boardOffline.asStateFlow()

    private val _topic = MutableStateFlow<String?>(null)
    val topic: StateFlow<String?> = _topic.asStateFlow()

    private val _bySame = MutableStateFlow(false)
    /** Most 나도 그래요 first instead of the newest. */
    val bySame: StateFlow<Boolean> = _bySame.asStateFlow()

    private val _thread = MutableStateFlow<PublicThread?>(null)
    /** The public question open now. */
    val thread: StateFlow<PublicThread?> = _thread.asStateFlow()

    fun loadBoard(more: Boolean = false) {
        // Without the server there is no 모두의 문의 to load.
        if (!online) {
            if (_board.value == null) _board.value = PublicPage(emptyList(), emptyList(), emptyMap(), null)
            return
        }
        val after = if (more) _board.value?.next ?: return else 0
        viewModelScope.launch {
            runCatching { c.inquiryApi.board(device, _topic.value, _bySame.value, after) }
                .onSuccess { page ->
                    _boardOffline.value = false
                    _board.value = if (more) page.copy(items = _board.value?.items.orEmpty() + page.items) else page
                }
                .onFailure {
                    _boardOffline.value = true
                    if (_board.value == null) _board.value = PublicPage(emptyList(), emptyList(), emptyMap(), null)
                }
        }
    }

    fun showTopic(topic: String?) {
        if (_topic.value == topic) return
        _topic.value = topic
        loadBoard()
    }

    fun sortBySame(on: Boolean) {
        if (_bySame.value == on) return
        _bySame.value = on
        loadBoard()
    }

    fun openThread(id: String, failed: () -> Unit) {
        if (_thread.value?.id != id) _thread.value = null
        viewModelScope.launch {
            runCatching { c.inquiryApi.boardThread(device, id) }
                .onSuccess { _thread.value = it }
                .onFailure { failed() }
        }
    }

    /** 나도 그래요 on the open question, shown at once and put back if the server says no. */
    fun same(on: Boolean, done: (String?) -> Unit) {
        val t = _thread.value ?: return
        _thread.value = t.copy(sameByMe = on, same = (t.same + if (on) 1 else -1).coerceAtLeast(0))
        viewModelScope.launch {
            runCatching { c.inquiryApi.same(device, t.id, on) }
                .onSuccess { n ->
                    _thread.value = _thread.value?.takeIf { it.id == t.id }?.copy(same = n, sameByMe = on) ?: _thread.value
                    _board.value = _board.value?.let { b -> b.copy(items = b.items.map { if (it.id == t.id) it.copy(same = n, sameByMe = on) else it }) }
                    done(null)
                }
                .onFailure {
                    _thread.value = t
                    done(failure(it))
                }
        }
    }

    /** [COMM-11] 이 사람 글 안 보기 in 모두의 문의: the same list as the plaza's, then 모두의 문의 again without them. */
    fun hideWriter(mark: String) {
        c.settingsRepository.update { s ->
            s.copy(plazaHidden = com.bamnat.core.plaza.HideList.decode(s.plazaHidden).hide(mark, "문의 쓴 분", System.currentTimeMillis()).encode())
        }
        _thread.value = null
        loadBoard()
    }

    fun flag(reason: InquiryFlag, done: (String?) -> Unit) {
        val t = _thread.value ?: return
        viewModelScope.launch {
            runCatching { c.inquiryApi.flag(device, t.id, reason) }
                .onSuccess {
                    _thread.value = t.copy(flaggedByMe = true)
                    done(null)
                }
                .onFailure { done(failure(it)) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // Nothing to look up before this phone has asked anything, or without the server.
            if (!online || !c.inquiryStore.asked) {
                _list.value = emptyList()
                return@launch
            }
            runCatching { c.inquiryApi.mine(device) }
                .onSuccess {
                    _list.value = it
                    _offline.value = false
                    c.inquiryStore.update(it)
                }
                .onFailure {
                    _offline.value = true
                    if (_list.value == null) _list.value = emptyList()
                }
        }
    }

    fun unreadIn(i: Inquiry): Boolean = i.answers > c.inquiryStore.seen(i.id)

    /** The user opened [i]: its answers are read. */
    fun opened(i: Inquiry) = c.inquiryStore.read(i, _list.value.orEmpty())

    /** The alarm check as the 알람 점검 screen copies it, cut to what the server takes. */
    suspend fun alarmCheck(context: Context): String = withContext(Dispatchers.IO) {
        val diag = DiagnosticsViewModel(c)
        diag.report(context, diag.events()).asText().take(InquiryClient.DIAG_MAX)
    }

    /** Why [text] cannot go yet, in words; null when it can. */
    fun problem(text: String): String? = when {
        text.length > InquiryClient.TEXT_MAX -> "${InquiryClient.TEXT_MAX}자까지 쓸 수 있어요"
        else -> CompanyText.personal(text)?.let(::personalProblem)
    }

    /** [INQ-05] Questions waiting to be sent, shown at the top of 내 문의. */
    val outbox: StateFlow<List<com.bamnat.app.company.QueuedInquiry>> = c.inquiryStore.outbox

    /**
     * Sends the question. [INQ-05] I9: when the internet or the server is not there, it waits on the phone
     * (without photos) and goes by itself once the phone is online; [done] then gets [QUEUED].
     */
    fun send(kind: InquiryKind, text: String, diag: String?, pay: String?, photos: List<ByteArray> = emptyList(), done: (Inquiry?, String?) -> Unit) {
        val requestId = InquiryClient.newRequestId()
        val full = info.copy(diag = diag, pay = pay, photos = photos)
        act({ sent, why -> done(sent, why) }, queueOn = { e ->
            if (!e.retryable) return@act false
            val queued = c.inquiryStore.queue(com.bamnat.app.company.QueuedInquiry(requestId, kind, text.trim(), full.copy(photos = emptyList()),
                java.time.Instant.now()))
            if (queued) {
                c.inquiryStore.clearDraft()
                com.bamnat.app.company.OutboxJob.schedule(c.appContext)
            }
            queued
        }) {
            c.inquiryApi.send(device, kind, text, full, requestId).also {
                c.telemetry.event(com.bamnat.core.stats.UsageEvent.INQUIRY.key)
                c.inquiryStore.asked = true
                c.inquiryStore.clearDraft()
                _list.value = listOf(it) + _list.value.orEmpty().filter { o -> o.id != it.id }
            }
        }
    }

    /** [INQ-05] 지금 보내기 on the waiting questions. */
    fun sendQueued(done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = com.bamnat.app.company.InquiryOutbox.flush(c)
            if (ok) refresh()
            done(ok)
        }
    }

    /** [INQ-05] A waiting question taken back: it goes back to the form to change, or is dropped. */
    fun dropQueued(requestId: String, edit: Boolean) {
        val q = c.inquiryStore.outbox.value.firstOrNull { it.requestId == requestId } ?: return
        c.inquiryStore.unqueue(requestId)
        if (edit) c.inquiryStore.saveDraft(q.kind, q.text)
    }

    fun more(id: String, text: String, done: (Inquiry?, String?) -> Unit) = act(done) {
        c.inquiryApi.more(device, id, text).also { updated ->
            c.inquiryStore.saveReply(id, "")
            _list.value = _list.value.orEmpty().map { if (it.id == id) updated else it }
        }
    }

    /** [INQ-03] 도움이 됐어요 / 아쉬워요 on the answers, or taking it back; a failure keeps what was there. */
    fun rate(id: String, good: Boolean?, done: (String?) -> Unit = {}) = act({ _, why -> done(why) }) {
        c.inquiryApi.rate(device, id, good).also { updated ->
            _list.value = _list.value.orEmpty().map { if (it.id == id) updated else it }
        }
    }

    fun delete(id: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { c.inquiryApi.delete(device, id) }.isSuccess
            if (ok) {
                c.inquiryStore.forget(id)
                _list.value = _list.value.orEmpty().filter { it.id != id }
                c.inquiryStore.update(_list.value.orEmpty())
            }
            done(ok)
        }
    }

    private fun act(
        done: (Inquiry?, String?) -> Unit,
        queueOn: (CompanyException) -> Boolean = { false },
        call: suspend () -> Inquiry,
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { call() }
            _busy.value = false
            result.onSuccess { done(it, null) }.onFailure { e ->
                if (e is CompanyException && queueOn(e)) done(null, QUEUED) else done(null, failure(e))
            }
        }
    }

    companion object {
        /** [INQ-05] What [send] says when the question waits to be sent. */
        const val QUEUED = "인터넷이 연결되면 저절로 보내요. 내 문의 맨 위 ‘보내기 대기’에서 볼 수 있어요"
    }

    private fun failure(e: Throwable): String = when ((e as? CompanyException)?.code) {
        "offline" -> "인터넷에 연결되지 않았어요. 연결한 뒤 다시 보내 주세요"
        "rate_limited" -> "오늘은 더 보낼 수 없어요. 내일 다시 보내거나 메일로 보내 주세요"
        "too_many" -> "이 문의에는 더 쓸 수 없어요. 새 문의로 보내 주세요"
        "email", "phone", "amount" -> personalProblem(e.message ?: "")
        "not_found" -> "이 문의는 지워졌거나 가려졌어요"
        else -> "보내지 못했어요. 잠시 뒤 다시 해 주세요"
    }

    private fun personalProblem(code: String) = when (code) {
        // Questions about pay come up most: a ratio says it without the amount.
        "amount" -> "금액은 적지 말아 주세요. ‘기본급의 10%’, ‘3시간 차이’처럼 쓰면 돼요"
        else -> CompanyText.problem(code)
    }
}
