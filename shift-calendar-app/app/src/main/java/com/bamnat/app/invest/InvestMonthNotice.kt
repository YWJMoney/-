package com.bamnat.app.invest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.core.invest.InvestMonthSummary
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * [INV-34] 월말 요약: on the last day of the month at 20:00, one notice per investor calendar with that
 * month's 매수·매도 count and, unless 금액 가리기 is on, what was bought and the 실현 손익. Worked out on
 * the phone; a month with no trade says nothing. On by default (S10), off on the 투자 화면's 설정.
 */
object InvestMonthNotice {
    private const val REQUEST = 3610
    private const val REQUEST_OPEN = 3611
    private const val NOTICE_ID = 7590

    /**
     * The next month-end notice, set loosely (a few minutes either way is fine for a summary); [on] false,
     * with no investor calendar, takes it away.
     */
    fun schedule(context: Context, on: Boolean = true, now: LocalDateTime = LocalDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (!on) {
            am.cancel(pending(context))
            return
        }
        val at = InvestMonthSummary.next(now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, InvestMonthReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal suspend fun post(context: Context, c: AppContainer, now: LocalDateTime = LocalDateTime.now()) {
        // A notice that came after the month turned (the phone was off) still sums the month that ended.
        val month = YearMonth.from(now.minusHours(12))
        val many = c.calendars.calendars.value.size > 1
        for (cal in c.investCalendars()) {
            val book = c.invest(cal.id).now()
            if (!book.settings.monthSummary) continue
            val m = InvestMonthSummary.of(book, month)
            if (m.isEmpty) continue
            val n = NotificationCompat.Builder(context, Notifications.INVEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentTitle(listOfNotNull(cal.name.takeIf { many }, "${month.monthValue}월 투자 요약").joinToString(" · "))
                .setContentText(m.text(book.settings.hideAmounts))
                .setContentIntent(Notifications.open(context, NoticeLinks.Target.INVEST, REQUEST_OPEN, cal.id))
                .setAutoCancel(true)
                .build()
            Notifications.post(context, NOTICE_ID, n, "invest-month:${cal.id}")
        }
    }
}

class InvestMonthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.appContainer
        val pending = goAsync()
        c.appScope.launch {
            try {
                InvestMonthNotice.post(context, c)
            } finally {
                InvestMonthNotice.schedule(context, c.investCalendars().isNotEmpty(), LocalDateTime.now().plusMinutes(1))
                pending.finish()
            }
        }
    }
}
