package com.bamnat.app.invest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.AlarmService
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.alarm.RingRequest
import com.bamnat.app.appContainer
import com.bamnat.app.data.InvestRepository
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.InvestBook
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.InvestFees
import com.bamnat.core.invest.InvestPlans
import com.bamnat.core.invest.Portfolio
import com.bamnat.core.invest.Trade
import com.bamnat.core.work.WorkStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** [INV-01] The investor calendar's records: the main calendar's database or an added calendar's own. */
fun AppContainer.invest(calendarId: String): InvestRepository =
    if (calendarId == PersonalCalendar.MAIN || calendars.get(calendarId) == null) InvestRepository.of(database)
    else InvestRepository.of(calendars.database(calendarId))

/** [INV-01] The user's calendars that are investor calendars, main first. */
fun AppContainer.investCalendars(): List<PersonalCalendar> =
    calendars.calendars.value.filter { calendars.styleOf(it.id, settingsRepository.current.workStyle) == WorkStyle.INVEST }

/**
 * [INV-08][INV-14] The investor's alarms and date notices, on the app's own notice channels: one
 * system alarm for the soonest of them over every investor calendar. At that minute each alarm posts
 * a notice with 매매 기록 (the trade sheet, filled in) and, for a plan whose amount and price are
 * known, 바로 기록 (saved without opening the app); one set to ring also rings like the shift alarm.
 */
object InvestNotices {
    private const val REQUEST = 3601
    private const val REQUEST_OPEN = 3602
    // [INV-08][INV-14] An alarm's or date's notice is told apart by a tag naming its calendar (each
    // calendar's ids start at 1) and its own row id, so no two notices take each other's place.
    // Their pending intents carry the calendar and row in their data, so the request codes need not differ.
    private const val NOTICE_BASE = 7600
    private const val EVENT_BASE = 7800
    private const val SAVED_BASE = 7999

    internal fun alarmTag(calendarId: String) = "invest-alarm:$calendarId"
    internal fun eventTag(calendarId: String) = "invest-event:$calendarId"
    /** A row id as a notice id: its own number, folded into an Int only past two billion rows. */
    internal fun rowNoticeId(id: Long): Int = (id xor (id ushr 32)).toInt()
    private const val EXTRA_AT = "at"
    const val EXTRA_CALENDAR = "calendar"
    const val EXTRA_ALARM = "alarm"
    const val ACTION_SAVE = "com.bamnat.app.action.INVEST_SAVE"
    /** A notice delivered later than this (the phone was off) is not shown any more. */
    private const val STALE_AFTER_MILLIS = 2 * 60 * 60 * 1000L
    /** Notice times posted at most in one late delivery; the rest follow from the next system alarm. */
    private const val MAX_CATCH_UP = 20

    /** One investor calendar's alarms and dates, as the scheduler reads them. */
    data class Plan(val calendarId: String, val calendarName: String, val alarms: List<InvestAlarm>, val events: List<InvestEvent>)

    /** [INV-08] Follows every investor calendar's alarms and dates for as long as the app runs. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun plans(c: AppContainer): Flow<List<Plan>> = combine(c.calendars.calendars, c.settingsRepository.settings.map { it.workStyle }) { list, main ->
        list.filter { c.calendars.styleOf(it.id, main) == WorkStyle.INVEST }
    }.flatMapLatest { cals ->
        if (cals.isEmpty()) flowOf(emptyList())
        else combine(cals.map { cal -> c.invest(cal.id).let { r -> combine(r.alarms, r.events) { a, e -> Plan(cal.id, cal.name, a, e) } } }) { it.toList() }
    }.debounce(300)

    suspend fun plansNow(c: AppContainer): List<Plan> = c.investCalendars().map { cal ->
        val book = c.invest(cal.id).now()
        Plan(cal.id, cal.name, book.alarms, book.events)
    }

    fun reschedule(context: Context, plans: List<Plan>, after: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = plans.mapNotNull { InvestAlarms.nextAny(it.alarms, it.events, after) }.minOrNull()
        if (next == null) {
            am.cancel(pending(context, 0L))
            return
        }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val op = pending(context, at)
        val rings = plans.any { p -> InvestAlarms.dueAt(p.alarms, next).any { it.ring } }
        when {
            rings -> AlarmScheduler.setAlarm(context, am, at, op)
            Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    private fun pending(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, InvestAlarmReceiver::class.java).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * [INV-08] The system alarm for [atMillis] came: that minute's notices, then those due since when it
     * came late (the phone dozing), then the next one set after them, so none is skipped.
     */
    internal suspend fun fire(context: Context, c: AppContainer, atMillis: Long, now: LocalDateTime = LocalDateTime.now()) {
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        handle(context, c, atMillis, nowMillis)
        // Times too old to be shown (see [handle]) are skipped, not walked through.
        var after = maxOf(if (atMillis != 0L) AlarmScheduler.millisToLocal(atMillis) else now, now.minusNanos(STALE_AFTER_MILLIS * 1_000_000))
        val plans = plansNow(c)
        repeat(MAX_CATCH_UP) {
            val due = plans.mapNotNull { InvestAlarms.nextAny(it.alarms, it.events, after) }.minOrNull()
            if (due == null || due.isAfter(now)) {
                reschedule(context, plans, after)
                return
            }
            handle(context, c, due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), nowMillis)
            after = due
        }
        // The rest are due already: the alarm set for the next comes at once and goes on from there.
        reschedule(context, plans, after)
    }

    internal suspend fun handle(context: Context, c: AppContainer, atMillis: Long, now: Long = System.currentTimeMillis()) {
        if (atMillis == 0L || now - atMillis > STALE_AFTER_MILLIS) return
        val at = AlarmScheduler.millisToLocal(atMillis)
        val ringing = mutableListOf<String>()
        for (cal in c.investCalendars()) {
            val book = c.invest(cal.id).now()
            val named = if (c.calendars.calendars.value.size > 1) cal.name else null
            for (a in InvestAlarms.dueAt(book.alarms, at)) {
                post(context, cal.id, named, a, book, at.toLocalDate())
                if (a.ring) ringing += a.title
            }
            InvestAlarms.eventsAt(book.events, at).forEach { postEvent(context, cal.id, named, it) }
        }
        if (ringing.isEmpty()) return
        val ring = RingRequest(
            shiftName = "", shiftDate = at.toLocalDate(), shiftStart = at.toLocalTime(), offsetMinutes = 0, soundUri = null,
            triggerAtMillis = atMillis, custom = true, label = ringing.joinToString(" · ").take(60),
        )
        try {
            AlarmService.start(context, ring)
        } catch (e: RuntimeException) {
            Notifications.postFallbackAlarm(context, ring)
        }
    }

    /** What the alarm's notice says under its title: when, and for a plan what it buys. */
    fun describe(a: InvestAlarm, book: InvestBook, today: LocalDate): String {
        val draft = InvestPlans.draft(a, book, today)
        val what = listOfNotNull(
            // [INV-17] 금액 가리기 holds on the lock screen too.
            a.amount?.takeIf { !book.settings.hideAmounts }?.let { com.bamnat.app.ui.Fmt.wonShort(it) + "원어치" },
            draft.quantity?.let { "${Portfolio.quantityText(it)}${a.market.unit}" + if (a.quantity == null) " 예상" else "" },
            a.targetPrice?.let { "목표가 ${Portfolio.quantityText(it)}" },
        )
        return (listOf(InvestAlarms.describe(a)) + what).joinToString(" · ")
    }

    private fun post(context: Context, calendarId: String, calendarName: String?, a: InvestAlarm, book: InvestBook, today: LocalDate) {
        val id = rowNoticeId(a.id)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN + NOTICE_BASE,
            NoticeLinks.intent(context, NoticeLinks.Target.INVEST_TRADE, calendarId, payload = a.id.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(listOfNotNull(calendarName, a.title).joinToString(" · "))
            .setContentText(describe(a, book, today))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action.Builder(0, "매매 기록", open).build())
        if (InvestPlans.canSaveAtOnce(InvestPlans.draft(a, book, today))) {
            val save = PendingIntent.getBroadcast(
                context, REQUEST_OPEN + NOTICE_BASE + 1000,
                Intent(context, InvestActionReceiver::class.java).setAction(ACTION_SAVE)
                    .setData(android.net.Uri.parse("bamnat-invest://$calendarId/${a.id}"))
                    .putExtra(EXTRA_CALENDAR, calendarId).putExtra(EXTRA_ALARM, a.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(NotificationCompat.Action.Builder(0, if (a.side == com.bamnat.core.invest.Side.SELL) "팔았어요, 바로 기록" else "샀어요, 바로 기록", save).build())
        }
        Notifications.post(context, id, builder.build(), alarmTag(calendarId))
    }

    private fun postEvent(context: Context, calendarId: String, calendarName: String?, e: InvestEvent) {
        val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(listOfNotNull(calendarName, "오늘 ${e.kind.label}").joinToString(" · "))
            .setContentText(listOf(e.label, e.memo).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.INVEST, REQUEST_OPEN + EVENT_BASE, calendarId))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, rowNoticeId(e.id), n, eventTag(calendarId))
    }

    /** [INV-08] 바로 기록: the plan's trade written as the notice said, then a notice that it was. */
    internal suspend fun saveFromNotice(context: Context, c: AppContainer, calendarId: String, alarmId: Long) = saving.withLock {
        // [INV-08] The notice goes first, so a second tap has nothing to press.
        Notifications.cancel(context, rowNoticeId(alarmId), alarmTag(calendarId))
        // A calendar removed since the notice came has nothing to save into; never 내 달력 instead.
        if (calendarId != PersonalCalendar.MAIN && c.calendars.get(calendarId) == null) return@withLock
        val repo = c.invest(calendarId)
        val book = repo.now()
        val alarm = book.alarms.firstOrNull { it.id == alarmId } ?: return@withLock
        val today = LocalDate.now()
        // A tap that got in anyway (two at once) finds today's trade from this alarm and writes no second one.
        if (book.trades.any { it.alarmId == alarmId && it.date == today }) return@withLock
        saveDraft(context, repo, book, alarm, today, calendarId)
    }

    /** One 바로 기록 at a time, so two taps cannot both read the book before either saves. */
    private val saving = Mutex()

    private suspend fun saveDraft(context: Context, repo: InvestRepository, book: InvestBook, alarm: InvestAlarm, today: LocalDate, calendarId: String) {
        val d = InvestPlans.draft(alarm, book, today)
        if (!InvestPlans.canSaveAtOnce(d)) return
        val account = d.accountId?.takeIf { book.account(it)?.hidden == false } ?: repo.ensureAccount()
        val fx = if (d.market.foreign) book.settings.usdKrw else 1.0
        val qty = d.quantity ?: return
        val price = d.price ?: return
        val costs = InvestFees.suggest(d.market, d.side, qty, price, book.account(account))
        val now = LocalDateTime.now()
        repo.saveTrade(Trade(
            accountId = account, date = today, minute = now.hour * 60 + now.minute, symbol = d.symbol, name = d.name, market = d.market,
            side = d.side, quantity = qty, price = price, fxRate = fx, fee = costs.fee, tax = costs.tax,
            tags = listOf(if (alarm.kind == com.bamnat.core.invest.AlarmKind.PLAN) "정기 적립" else alarm.kind.label), alarmId = alarm.id,
        ))
        val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${alarm.name.ifBlank { alarm.symbol }} ${d.side.label} 기록했어요")
            .setContentText("${Portfolio.quantityText(qty)}${d.market.unit} × ${Portfolio.quantityText(price)} · 눌러서 고칠 수 있어요")
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.INVEST, REQUEST_OPEN + SAVED_BASE, calendarId))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        Notifications.post(context, SAVED_BASE, n)
    }

    /** [INV-08] Re-plans the next investor notice whenever an alarm or date changes, for as long as the app runs. */
    fun follow(c: AppContainer) = c.appScope.launch {
        plans(c).collect { reschedule(c.appContext, it) }
    }
}

class InvestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.appContainer
        val atMillis = intent.getLongExtra("at", 0L)
        val pending = goAsync()
        c.appScope.launch {
            try {
                // Read again now, so an alarm changed or deleted since it was set is not announced.
                InvestNotices.fire(context, c, atMillis)
            } finally {
                pending.finish()
            }
        }
    }
}

/** [INV-08] 바로 기록 on an alarm's notice, usable without opening the app. */
class InvestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != InvestNotices.ACTION_SAVE) return
        val calendar = intent.getStringExtra(InvestNotices.EXTRA_CALENDAR) ?: return
        val alarm = intent.getLongExtra(InvestNotices.EXTRA_ALARM, 0L).takeIf { it != 0L } ?: return
        val c = context.appContainer
        val pending = goAsync()
        c.appScope.launch {
            try {
                InvestNotices.saveFromNotice(context, c, calendar, alarm)
            } finally {
                pending.finish()
            }
        }
    }
}
