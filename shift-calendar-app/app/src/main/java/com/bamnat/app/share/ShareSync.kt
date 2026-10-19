package com.bamnat.app.share

import com.bamnat.app.AppContainer
import com.bamnat.app.data.PersonalCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    }

    /** Follows the data for as long as the app runs. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun follow() {
        // [MCAL-03][MCAL-06] The added calendars' own days, money, pay and links; memos are the same on every calendar.
        val added: Flow<Any?> = c.calendars.calendars.flatMapLatest { list ->
            val others = list.filter { !it.isMain }
            if (others.isEmpty()) flowOf(Unit)
            else combine(others.flatMap { cal ->
                val money = c.calendars.money(cal.id)
                val pay = c.calendars.pay(cal.id)
                listOf(c.calendars.repository(cal.id).snapshot, c.shareLinksOf(cal.id).link, money.jobs, money.sales, money.entries, pay.profile, pay.closed)
            }) { Unit }
        }
        val reads: List<Flow<Any?>> = listOf(
            c.scheduleRepository.snapshot, c.memoRepository.memos, c.moneyRepository.jobs, c.moneyRepository.sales,
            c.moneyRepository.entries, c.payStore.profile, c.payStore.closed, c.settingsRepository.settings, c.shareLinks.link, added,
        )
        combine(reads) { Unit }.debounce(DEBOUNCE_MILLIS).collect {
            for (cal in c.calendars.calendars.value) {
                var tries = 0
                while (sync(cal.id) == Result.Offline && ++tries < RETRIES) delay(RETRY_MILLIS)
            }
        }
    }

    /** Sends the link of [calendarId] again when what it carries changed since it was last sent. */
    suspend fun sync(calendarId: String = PersonalCalendar.MAIN): Result = lock.withLock {
        val store = c.shareLinksOf(calendarId)
        store.expire()
        val link = store.link.value ?: return@withLock Result.Same
        if (!store.auto) return@withLock Result.Same
        send(calendarId, store, link, null)
    }

    /** [SHR-12] Keeps the link open [days] more days from now, with the calendar as it is now. */
    suspend fun extend(calendarId: String = PersonalCalendar.MAIN, days: Int? = null): Result = lock.withLock {
        val store = c.shareLinksOf(calendarId)
        store.expire()
        val link = store.link.value ?: return@withLock Result.Gone
        send(calendarId, store, link, days ?: link.ttlDays)
    }

    private suspend fun send(calendarId: String, store: ShareLinkStore, link: ShareLinkState, ttlDays: Int?): Result {
        // [MCAL-03] A calendar removed since has no link left to keep.
        val cal = c.calendars.get(calendarId) ?: return Result.Gone
        val from = ShareLinkPayload.startMonth(link, today())
        val snapshot = c.calendars.repository(calendarId).snapshot.first()
        // [MCAL-06] Each calendar shares its own kind of work, money and pay.
        val payload = ShareContent.payload(c, snapshot, from.atDay(1), link.title, ttlDays, store.options(), cal.id)
        // A calendar emptied for these months stays as it was on the link rather than being refused.
        if (!ShareLinkPayload.hasContent(payload)) return Result.Same
        val sent = ShareLinkPayload.fingerprint(payload)
        if (ttlDays == null && sent == store.sent && link.from == from) return Result.Same
        return try {
            val updated = c.shareApi.update(link, payload)
            store.save(updated.copy(from = from), sent)
            if (ttlDays != null) store.noticedFor = null
            c.telemetry.event(if (ttlDays == null) "share_link_auto" else "share_link_extend")
            Result.Sent
        } catch (e: ShareLinkException) {
            when (e.message) {
                "not_found", "forbidden" -> { store.clear(); Result.Gone }
                else -> Result.Offline
            }
        } catch (e: IOException) {
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
