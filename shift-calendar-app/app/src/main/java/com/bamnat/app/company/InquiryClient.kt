package com.bamnat.app.company

import android.content.Context
import com.bamnat.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant

/** What a question is about [ADMIN-02]; [code] is the server's word for it. */
enum class InquiryKind(val code: String, val label: String, val hint: String) {
    QUESTION("question", "궁금해요", "쓰는 법이나 계산이 궁금할 때"),
    BUG("bug", "앱이 이상해요", "알람이 안 울리거나 화면이 멈출 때"),
    IDEA("idea", "이렇게 바뀌면 좋겠어요", "있었으면 하는 기능"),
    ;

    companion object {
        fun of(code: String): InquiryKind = entries.firstOrNull { it.code == code } ?: QUESTION
    }
}

data class InquiryMessage(val fromDeveloper: Boolean, val text: String, val at: Instant)

/** One question and its answers [ADMIN-02]. [status]: open (waiting for the developer), answered or closed. */
data class Inquiry(
    val id: String,
    val kind: InquiryKind,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<InquiryMessage>,
) {
    val answers: Int get() = messages.count { it.fromDeveloper }
    val waiting: Boolean get() = status == "open"
}

/**
 * What goes with a question, all shown before it is sent: the app and phone always, the alarm
 * check only when the user ticks it. Never a name, contact, amount or anything from the calendar
 * beyond what the alarm check itself says.
 */
data class InquiryInfo(val app: String, val model: String, val android: String, val diag: String? = null)

/** [ADMIN-02] 설정 > 문의하기 through the company server; screenshots and tests answer with a fake. */
interface InquiryApi {
    /** False in a build without the company server: 문의하기 opens a mail app instead. */
    val enabled: Boolean
    suspend fun send(device: String, kind: InquiryKind, text: String, info: InquiryInfo): Inquiry
    suspend fun mine(device: String): List<Inquiry>
    suspend fun more(device: String, id: String, text: String): Inquiry
    suspend fun delete(device: String, id: String)
}

class InquiryClient(private val baseUrl: String = BuildConfig.COMPANY_BASE_URL) : InquiryApi {
    override val enabled: Boolean get() = baseUrl.isNotBlank()

    override suspend fun send(device: String, kind: InquiryKind, text: String, info: InquiryInfo): Inquiry = serverCall {
        val i = JSONObject().put("app", info.app).put("model", info.model).put("android", info.android)
        info.diag?.takeIf { it.isNotBlank() }?.let { i.put("diag", it.take(DIAG_MAX)) }
        val body = JSONObject().put("v", 1).put("device", device).put("kind", kind.code).put("text", text.trim()).put("info", i)
        inquiry(serverRequest(baseUrl, "POST", "/api/inquiries", body))
    }

    override suspend fun mine(device: String): List<Inquiry> = serverCall {
        serverRequest(baseUrl, "GET", "/api/inquiries", device = device).optJSONArray("inquiries").objects().map(::inquiry)
    }

    override suspend fun more(device: String, id: String, text: String): Inquiry = serverCall {
        inquiry(serverRequest(baseUrl, "POST", "/api/inquiries/$id/messages", JSONObject().put("text", text.trim()), device))
    }

    /** One already gone counts as deleted. */
    override suspend fun delete(device: String, id: String) = serverCall {
        try {
            serverRequest(baseUrl, "DELETE", "/api/inquiries/$id", device = device)
        } catch (e: CompanyException) {
            if (e.code != "not_found") throw e
        }
        Unit
    }

    private fun inquiry(o: JSONObject) = Inquiry(
        id = o.getString("id"),
        kind = InquiryKind.of(o.optString("kind")),
        status = o.optString("status", "open"),
        createdAt = Instant.parse(o.getString("createdAt")),
        updatedAt = Instant.parse(o.getString("updatedAt")),
        messages = o.optJSONArray("messages").objects().map {
            InquiryMessage(fromDeveloper = it.optString("from") == "admin", text = it.optString("text"), at = Instant.parse(it.getString("at")))
        },
    )

    companion object {
        /** The longest question the server takes, and the longest alarm check. */
        const val TEXT_MAX = 2000
        const val DIAG_MAX = 6000
    }
}

/**
 * [ADMIN-02] The developer's answers this phone has already shown, so a new one can be pointed out.
 * The questions themselves stay on the server, found by the random install id of [CompanyStore].
 */
class InquiryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("inquiry", Context.MODE_PRIVATE)

    private val _unread = MutableStateFlow(prefs.getInt("unread", 0))
    /** Answers not yet looked at, as of the last time the questions were fetched. */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** This phone has sent a question, so looking for answers is worth a call to the server. */
    var asked: Boolean
        get() = prefs.getBoolean("asked", false)
        set(v) = prefs.edit().putBoolean("asked", v).apply()

    fun seen(id: String): Int = prefs.getInt("seen_$id", 0)

    /** After fetching: counts answers the user has not opened yet. */
    fun update(list: List<Inquiry>) {
        val n = list.sumOf { (it.answers - seen(it.id)).coerceAtLeast(0) }
        _unread.value = n
        prefs.edit().putInt("unread", n).apply()
    }

    /** The user opened [inquiry]: its answers so far are read. */
    fun read(inquiry: Inquiry, all: List<Inquiry>) {
        prefs.edit().putInt("seen_${inquiry.id}", inquiry.answers).apply()
        update(all)
    }

    fun forget(id: String) {
        prefs.edit().remove("seen_$id").apply()
    }
}
