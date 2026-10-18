package com.bamnat.app.ui.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.company.CompanyException
import com.bamnat.app.company.CompanyText
import com.bamnat.app.company.Inquiry
import com.bamnat.app.company.InquiryClient
import com.bamnat.app.company.InquiryInfo
import com.bamnat.app.company.InquiryKind
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
    val info = InquiryInfo(
        app = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )

    fun refresh() {
        viewModelScope.launch {
            // Nothing to look up before this phone has asked anything.
            if (!c.inquiryStore.asked) {
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

    fun send(kind: InquiryKind, text: String, diag: String?, done: (Inquiry?, String?) -> Unit) = act(done) {
        c.inquiryApi.send(device, kind, text, info.copy(diag = diag)).also {
            c.inquiryStore.asked = true
            _list.value = listOf(it) + _list.value.orEmpty().filter { o -> o.id != it.id }
        }
    }

    fun more(id: String, text: String, done: (Inquiry?, String?) -> Unit) = act(done) {
        c.inquiryApi.more(device, id, text).also { updated -> _list.value = _list.value.orEmpty().map { if (it.id == id) updated else it } }
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

    private fun act(done: (Inquiry?, String?) -> Unit, call: suspend () -> Inquiry) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { call() }
            _busy.value = false
            result.onSuccess { done(it, null) }.onFailure { done(null, failure(it)) }
        }
    }

    private fun failure(e: Throwable): String = when ((e as? CompanyException)?.code) {
        "offline" -> "인터넷에 연결되지 않았어요. 연결한 뒤 다시 보내 주세요"
        "rate_limited" -> "오늘은 더 보낼 수 없어요. 내일 다시 보내거나 메일로 보내 주세요"
        "too_many" -> "이 문의에는 더 쓸 수 없어요. 새 문의로 보내 주세요"
        "email", "phone", "amount" -> personalProblem(e.message ?: "")
        "not_found" -> "이 문의는 지워졌어요"
        else -> "보내지 못했어요. 잠시 뒤 다시 해 주세요"
    }

    private fun personalProblem(code: String) = when (code) {
        // Questions about pay come up most: a ratio says it without the amount.
        "amount" -> "금액은 적지 말아 주세요. ‘기본급의 10%’, ‘3시간 차이’처럼 쓰면 돼요"
        else -> CompanyText.problem(code)
    }
}
