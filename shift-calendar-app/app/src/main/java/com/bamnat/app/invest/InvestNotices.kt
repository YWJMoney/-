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
    // [INV-08][INV-14] Notice ids: an alarm's take NOTICE_BASE + 0..127, a date's EVENT_BASE + 0..127,
    // so the bases stay at least 128 apart and the saved notice sits clear of both.
    private const val NOTICE_BASE = 7600
    private const val EVENT_BASE = 7800
    private const val SAVED_BASE = 7999
    private const val EXTRA_AT = "at"
    const val EXTRA_CALENDAR = "calendar"
    const val EXTRA_ALARM = "alarm"
    const val ACTION_SAVE = "com.bamnat.app.action.INVEST_SAVE"
    /** A notice delivered later than this (the phone was off) is not shown any more. */
    private const val STALE_AFTER_MILLIS = 2 * 60 * 60 * 1000L

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
            a.amount?.let { com.bamnat.app.ui.Fmt.wonShort(it) + "원어치" },
            draft.quantity?.let { "${Portfolio.quantityText(it)}주" + if (a.quantity == null) " 예상" else "" },
            a.targetPrice?.let { "목표가 ${Portfolio.quantityText(it)}" },
        )
        return (listOf(InvestAlarms.describe(a)) + what).joinToString(" · ")
    }

    private fun post(context: Context, calendarId: String, calendarName: String?, a: InvestAlarm, book: InvestBook, today: LocalDate) {
        val id = NOTICE_BASE + (a.id.toInt() and 0x7f)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN + id,
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
                context, REQUEST_OPEN + id + 1000,
                Intent(context, InvestActionReceiver::class.java).setAction(ACTION_SAVE)
                    .setData(android.net.Uri.parse("bamnat-invest://$calendarId/${a.id}"))
                    .putExtra(EXTRA_CALENDAR, calendarId).putExtra(EXTRA_ALARM, a.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(NotificationCompat.Action.Builder(0, if (a.side == com.bamnat.core.invest.Side.SELL) "팔았어요, 바로 기록" else "샀어요, 바로 기록", save).build())
        }
        Notifications.post(context, id, builder.build())
    }

    private fun postEvent(context: Context, calendarId: String, calendarName: String?, e: InvestEvent) {
        val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(listOfNotNull(calendarName, "오늘 ${e.kind.label}").joinToString(" · "))
            .setContentText(listOf(e.label, e.memo).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.INVEST, REQUEST_OPEN + EVENT_BASE + (e.id.toInt() and 0x7f), calendarId))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, EVENT_BASE + (e.id.toInt() and 0x7f), n)
    }

    /** [INV-08] 바로 기록: the plan's trade written as the notice said, then a notice that it was. */
    internal suspend fun saveFromNotice(context: Context, c: AppContainer, calendarId: String, alarmId: Long) {
        // A calendar removed since the notice came has nothing to save into; never 내 달력 instead.
        if (calendarId != PersonalCalendar.MAIN && c.calendars.get(calendarId) == null) return
        val repo = c.invest(calendarId)
        val book = repo.now()
        val alarm = book.alarms.firstOrNull { it.id == alarmId } ?: return
        val today = LocalDate.now()
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
        Notifications.cancel(context, NOTICE_BASE + (alarm.id.toInt() and 0x7f))
        val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${alarm.name.ifBlank { alarm.symbol }} ${d.side.label} 기록했어요")
            .setContentText("${Portfolio.quantityText(qty)}주 × ${Portfolio.quantityText(price)} · 눌러서 고칠 수 있어요")
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
                InvestNotices.handle(context, c, atMillis)
                val after = if (atMillis != 0L) AlarmScheduler.millisToLocal(atMillis) else LocalDateTime.now()
                InvestNotices.reschedule(context, InvestNotices.plansNow(c), maxOf(after, LocalDateTime.now().minusSeconds(1)))
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
