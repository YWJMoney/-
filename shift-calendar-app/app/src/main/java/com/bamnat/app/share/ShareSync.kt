package com.bamnat.app.share

import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * [SHR-12] Keeps the one link this phone made in step with the calendar: a change to the shifts,
 * memos, money or what the link shows goes to the server a few seconds later, and only when what the
 * link carries actually changed. The receivers' apps then download it the next time they look
 * (see [ShareApi.readIfChanged]). The link's end date stays; only [extend] moves it.
 * [MCAL-03] Each of the user's calendars has its own link, kept in step with that calendar.
 * [PROV-04] With 프로 a calendar has up to five, each kept in step the same way.
 */
class ShareSync(
    private val c: AppContainer,
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()

    sealed interface Result {
        /** Nothing to send: no link, turned off, or the server already has this. */
        data object Same : Result
        data object Sent : Result
        /** No internet or the server did not answer; tried again later. */
        data object Offline : Result
        /** The link was cut or ran out on the server; the phone forgets it too. */
        data object Gone : Result
        /** [PROV-04] Keeping this link open longer is 프로's. */
        data object Locked : Result
    }

    /** Follows the data for as long as the app runs. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun follow() {
        // [MCAL-03][MCAL-06] The added calendars' own days, money, pay and links; [MEMO-01] memos as each calendar sees them.
        val added: Flow<Any?> = c.calendars.calendars.flatMapLatest { list ->
            val others = list.filter { !it.isMain }
            if (others.isEmpty()) flowOf(Unit)
            else combine(others.flatMap { cal ->
                val money = c.calendars.money(cal.id)
                val pay = c.calendars.pay(cal.id)
                listOf(c.calendars.repository(cal.id).snapshot, money.jobs, money.sales, money.entries, pay.profile, pay.closed) +
                    ShareLinkKey.all(cal.id).map { c.shareLinksOf(it).link }
            }) { Unit }
        }
        val reads: List<Flow<Any?>> = listOf(
            c.scheduleRepository.snapshot, c.memoRepository.memos, c.memoRepository.sharing, c.moneyRepository.jobs, c.moneyRepository.sales,
            c.moneyRepository.entries, c.payStore.profile, c.payStore.closed, c.settingsRepository.settings, added,
            // [FEAT-15] A feed goes off with 프로, and on again with it.
            c.featureGate.state.map { it.allows(com.bamnat.app.tier.Feature.ICS_FEED) }.distinctUntilChanged(),
        ) + ShareLinkKey.all(PersonalCalendar.MAIN).map { c.shareLinksOf(it).link }
        combine(reads) { Unit }.debounce(DEBOUNCE_MILLIS).collect {
            // [PROV-04] Each of a calendar's links, the first first.
            for (key in c.calendars.calendars.value.flatMap { ShareLinkKey.all(it.id) }) {
                var tries = 0
                var result = sync(key)
                while (result == Result.Offline && ++tries < RETRIES) {
                    delay(RETRY_MILLIS)
                    result = sync(key)
                }
                // [SHR-13] Still not through: the job sends it once the phone is online, even with the app closed.
                if (result == Result.Offline) com.bamnat.app.company.OutboxJob.schedule(c.appContext)
            }
        }
    }

    /** Sends the link [key] (a calendar's id for its first link, see [ShareLinkKey]) again when what it carries changed since it was last sent. */
    suspend fun sync(key: String = PersonalCalendar.MAIN): Result = lock.withLock {
        val store = c.shareLinksOf(key)
        store.expire()
        val link = store.link.value ?: return@withLock Result.Same
        if (!store.auto) return@withLock Result.Same
        send(store, link, null)
    }

    /** [SHR-13] Every link with a change the server has not got, sent now; true when none is left. */
    suspend fun sendUnsent(): Boolean {
        var left = false
        for (key in c.calendars.calendars.value.flatMap { ShareLinkKey.all(it.id) }) {
            if (!c.shareLinksOf(key).unsent.value) continue
            if (sync(key) == Result.Offline) left = true
        }
        return !left
    }

    /**
     * [FEAT-15] Sends the link as it is now, even with 고치면 바로 바꾸기 off: turning its calendar feed
     * on or off goes to the server at once, the end date kept.
     */
    suspend fun resend(key: String): Result = lock.withLock {
        val store = c.shareLinksOf(key)
        store.expire()
        val link = store.link.value ?: return@withLock Result.Gone
        send(store, link, null)
    }

    /**
     * [SHR-12] Keeps the link open [days] more days from now, with the calendar as it is now.
     * [PROV-04] A calendar's second to fifth links are 프로's: without it they stay open until their end
     * date and keep following the calendar, but are not kept open longer ([Result.Locked]). A 180-day or
     * 1-year period is kept as 90 days without 프로.
     */
    suspend fun extend(key: String = PersonalCalendar.MAIN, days: Int? = null): Result = lock.withLock {
        val store = c.shareLinksOf(key)
        store.expire()
        val link = store.link.value ?: return@withLock Result.Gone
        val pro = c.featureGate.allows(com.bamnat.app.tier.Feature.MORE_LINKS)
        if (store.slot > ShareLinkKey.FREE && !pro) return@withLock Result.Locked
        send(store, link, ShareLinkPayload.allowedTtl(days ?: link.ttlDays, pro))
    }

    private suspend fun send(store: ShareLinkStore, link: ShareLinkState, ttlDays: Int?): Result {
        // [MCAL-03] A calendar removed since has no link left to keep.
        val cal = c.calendars.get(store.calendarId) ?: return Result.Gone
        val from = ShareLinkPayload.startMonth(link, today())
        val snapshot = c.calendars.repository(cal.id).snapshot.first()
        // [MCAL-06] Each calendar shares its own kind of work, money and pay.
        val payload = ShareContent.payload(c, snapshot, from.atDay(1), link.title, ttlDays, store.options(), cal.id, store.feed)
        // A calendar emptied for these months stays as it was on the link rather than being refused.
        if (!ShareLinkPayload.hasContent(payload)) return Result.Same
        val sent = ShareLinkPayload.fingerprint(payload)
        if (ttlDays == null && sent == store.sent && link.from == from) {
            if (store.unsent.value) store.sendResult(clock())
            return Result.Same
        }
        return try {
            val updated = c.shareApi.update(link, payload)
            store.save(updated.copy(from = from), sent)
            store.sendResult(clock())
            if (ttlDays != null) store.noticedFor = null
            c.telemetry.event(if (ttlDays == null) "share_link_auto" else "share_link_extend")
            Result.Sent
        } catch (e: ShareLinkException) {
            when (e.message) {
                "not_found", "forbidden" -> { store.clear(); Result.Gone }
                else -> { store.sendResult(null); Result.Offline }
            }
        } catch (e: IOException) {
            store.sendResult(null)
            Result.Offline
        }
    }

    private companion object {
        /** Several edits in a row (painting a week) go as one. */
        const val DEBOUNCE_MILLIS = 5_000L
        const val RETRIES = 3
        const val RETRY_MILLIS = 60_000L
    }
}
