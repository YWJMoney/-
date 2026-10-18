package com.bamnat.app.data

import com.bamnat.app.company.AppliedSetup
import com.bamnat.app.company.CompanyDraft
import com.bamnat.app.company.CompanyStore
import com.bamnat.core.setup.SetupDraft
import com.bamnat.core.setup.SetupSource
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * [SETUP-01] Saves the first setup at once: the shifts, the order and the painted days in one
 * transaction, then a shared setup's kinds of extra work and pay rules, what the company screens need
 * and last the kind of work, which is what tells the app the setup is done. The draft is marked first,
 * so a save cut short is finished on the next start instead of being asked again.
 */
class FirstSetup(
    private val schedule: ScheduleRepository,
    private val settings: SettingsRepository,
    private val drafts: SetupDraftStore,
    private val share: () -> CompanyShare,
    private val company: () -> CompanyStore,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val lock = Mutex()

    private val _saved = MutableStateFlow<SetupDraft?>(null)
    /** The setup just saved, for "예상 급여도 계산할까요?" on the calendar; taken once. */
    val saved: StateFlow<SetupDraft?> = _saved.asStateFlow()

    fun consumeSaved() {
        _saved.value = null
    }

    suspend fun save(draft: SetupDraft): SetupDraft = lock.withLock {
        val style = requireNotNull(draft.style) { "A setup without a kind of work" }
        val marked = draft.copy(committing = true, later = false)
        withContext(Dispatchers.IO) {
            drafts.save(marked)
            drafts.flush()
        }
        val saved = schedule.applySetup(marked, today())
        val source = marked.source
        // A broken part of a shared file was turned down when it was read; the shifts are saved either way.
        val pendingPay = source != null && runCatching { share().applyRules(source.text) }.getOrDefault(false)
        val store = company()
        val companyId = source?.id
        val setupId = source?.setupId
        if (source?.kind == SetupSource.Kind.SERVER && companyId != null && setupId != null) {
            // [COMM-03] 맞았어요·달라요 is asked about the company's setup in use.
            store.setApplied(AppliedSetup(companyId, source.name, source.dept, setupId, source.version, source.adopted, Instant.now()))
            store.setDraft(null)
        } else if (marked.companyName.isNotBlank()) {
            // [COMM-02] Set up by hand under a company's name: the user is invited to share it later.
            store.setDraft(CompanyDraft(marked.companyName.trim().take(40)))
        }
        if (pendingPay) store.pendingPay = source!!.text
        settings.update { it.copy(workStyle = style, rosterMonthly = marked.rosterMonthly && style == WorkStyle.SHIFT) }
        drafts.clear()
        _saved.value = saved
        saved
    }

    /** A save the app was closed in the middle of is finished, never asked again. */
    suspend fun finishInterrupted() {
        val d = drafts.draft.value ?: return
        if (d.committing && d.style != null) save(d)
    }
}
