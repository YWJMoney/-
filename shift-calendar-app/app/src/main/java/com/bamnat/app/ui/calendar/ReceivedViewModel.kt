package com.bamnat.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.share.ReceivedCalendar
import com.bamnat.app.share.ReceivedCalendarStore
import com.bamnat.app.share.ReceivedShare
import com.bamnat.app.share.ShareLinkException
import com.bamnat.app.share.ShareLinkId
import com.bamnat.app.share.SharedSchedule
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * [SHR-02] Received calendars: opening a link someone shared, keeping it beside the user's own
 * calendar, switching between them like accounts, and letting it go when the sharer cuts it.
 */
class ReceivedViewModel(private val c: AppContainer, private val clock: () -> Instant = Instant::now) : ViewModel() {
    val enabled: Boolean get() = c.shareApi.enabled
    val calendars: StateFlow<List<ReceivedCalendar>> = c.received.calendars
    val viewing: StateFlow<ReceivedCalendar?> = combine(c.received.calendars, c.received.viewing) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.received.viewing.value?.let(c.received::get))
    /** A link the app was opened with, for the calendar to offer. */
    val opening: StateFlow<String?> = c.received.opening

    sealed interface Refresh {
        data object Idle : Refresh
        data class Loading(val id: String) : Refresh
        /** No internet: the copy from [ReceivedCalendar.fetchedAt] is what shows. */
        data class Offline(val id: String) : Refresh
    }

    private val _refresh = MutableStateFlow<Refresh>(Refresh.Idle)
    val refresh: StateFlow<Refresh> = _refresh.asStateFlow()

    sealed interface Preview {
        data object Idle : Preview
        data object Loading : Preview
        data class Ready(val id: String, val share: ReceivedShare, val schedule: SharedSchedule, val existing: Boolean) : Preview
        data class Failed(val message: String) : Preview
    }

    private val _preview = MutableStateFlow<Preview>(Preview.Idle)
    val preview: StateFlow<Preview> = _preview.asStateFlow()
    private var previewJob: Job? = null

    init {
        c.received.expire(clock())
    }

    /** Switches the calendar to [id], or back to the user's own with null. */
    fun view(id: String?) {
        // What ran out while the app stayed open goes before it shows.
        c.received.expire(clock())
        c.received.view(id)
        if (id != null) {
            c.telemetry.event("received_view")
            refreshIfStale(id)
        }
    }

    /**
     * Asks again when the copy is older than a minute, so switching feels instant but stays current.
     * [SHR-12] The ask carries the copy's tag and downloads only what the sharer changed.
     */
    fun refreshIfStale(id: String) {
        c.received.expire(clock())
        val cal = c.received.get(id) ?: return
        if (cal.gone) return
        val age = cal.fetchedAt?.let { Duration.between(it, clock()) }
        if (age == null || age >= STALE) refresh(id)
    }

    fun refresh(id: String) {
        if (_refresh.value == Refresh.Loading(id)) return
        _refresh.value = Refresh.Loading(id)
        viewModelScope.launch {
            _refresh.value = try {
                // Taken out while this was loading: it stays out.
                val changed = c.shareApi.readIfChanged(id, c.received.get(id)?.etag)
                if (changed == null) c.received.touch(id) else c.received.update(id, changed)
                Refresh.Idle
            } catch (e: ShareLinkException) {
                // Cut or run out: what the sharer took back goes from this phone too.
                if (e.message == "not_found") c.received.markGone(id)
                if (e.message == "not_found") Refresh.Idle else Refresh.Offline(id)
            } catch (e: IOException) {
                Refresh.Offline(id)
            }
        }
    }

    /** [SRV-05] 이 근무표 신고하기; [done] gets what to tell the user. */
    fun report(id: String, reason: com.bamnat.app.share.ShareReport, done: (String) -> Unit) {
        viewModelScope.launch {
            val said = try {
                c.shareApi.report(id, reason.code)
                c.telemetry.event("share_report", reason.code)
                "신고했어요. 운영자가 확인하고 운영 규칙에 어긋나면 링크를 내려요. 누가 신고했는지는 남지 않아요."
            } catch (e: ShareLinkException) {
                if (e.message == "rate_limited") "잠시 뒤에 다시 신고해 주세요." else "신고를 보내지 못했어요. 잠시 뒤 다시 해 주세요."
            } catch (e: IOException) {
                "인터넷에 연결되지 않았어요. 연결을 확인하고 다시 해 주세요."
            }
            done(said)
        }
    }

    /** Reads the link in [text] for a look before it is added. */
    fun preview(text: String) {
        val id = ShareLinkId.find(text.trim())
        if (id == null) {
            _preview.value = Preview.Failed("링크를 찾지 못했어요. 받은 메시지의 주소를 그대로 붙여 넣어 주세요.")
            return
        }
        previewId(id)
    }

    fun previewId(id: String) {
        previewJob?.cancel()
        _preview.value = Preview.Loading
        previewJob = viewModelScope.launch {
            _preview.value = try {
                val share = c.shareApi.read(id)
                Preview.Ready(id, share, SharedSchedule.parse(share.schedule), existing = c.received.get(id) != null)
            } catch (e: ShareLinkException) {
                // A kept calendar whose link is gone loses its schedule here too.
                if (e.message == "not_found" && c.received.get(id) != null) c.received.markGone(id)
                Preview.Failed(
                    if (e.message == "not_found") "이 링크는 끊겼거나 기간이 지났어요. 보낸 분께 새 링크를 부탁해 보세요."
                    else "근무표를 받지 못했어요 (${e.message}). 잠시 뒤 다시 해 주세요.",
                )
            } catch (e: IOException) {
                Preview.Failed("인터넷에 연결되지 않았어요. 연결을 확인하고 다시 해 주세요.")
            }
        }
    }

    fun resetPreview() {
        previewJob?.cancel()
        _preview.value = Preview.Idle
    }

    /** Keeps the previewed calendar and switches to it. False when the list is full. */
    fun add(ready: Preview.Ready): Boolean {
        if (!ready.existing && c.received.calendars.value.size >= ReceivedCalendarStore.MAX) {
            _preview.value = Preview.Failed("받은 달력은 ${ReceivedCalendarStore.MAX}개까지 둘 수 있어요. 안 보는 달력을 먼저 빼 주세요.")
            return false
        }
        c.received.put(ready.id, ready.share)
        c.received.view(ready.id)
        c.telemetry.event("received_add", if (ready.existing) "again" else "new")
        _preview.value = Preview.Idle
        return true
    }

    fun remove(id: String) {
        c.received.remove(id)
        c.telemetry.event("received_remove")
    }

    fun consumeOpening() = c.received.open(null)

    private companion object {
        val STALE: Duration = Duration.ofMinutes(1)
    }
}
