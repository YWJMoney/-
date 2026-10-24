package com.bamnat.app.tier

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.appContainer
import com.bamnat.core.tier.TrialConsent
import java.time.Instant
import java.time.ZoneId
import com.bamnat.core.time.Today

/**
 * [MON-06] One notice on day 23 of the month's trial (7 days before it turns paid) that the app has a
 * question about going on, unless the user already answered. Inexact: an hour late is fine.
 */
object TrialNotice {
    private const val REQUEST = 3301

    fun reschedule(context: Context, state: ProState, zone: ZoneId = Today.zone(), now: Instant = Today.instant()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val sub = state.sub
        val end = sub?.trialEnd(zone)
        val stage = TrialConsent.stage(sub?.play(), end, state.consentAt, now)
        if (sub == null || end == null || stage == TrialConsent.Stage.NONE || stage == TrialConsent.Stage.DONE ||
            state.trialNoticeFor == sub.purchasedAt) {
            am.cancel(pending(context))
            return
        }
        val at = maxOf(TrialConsent.askFrom(end), now).toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, TrialNoticeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, end: Instant, zone: ZoneId = Today.zone()) {
        val day = end.atZone(zone).toLocalDate()
        val notification = NotificationCompat.Builder(context, Notifications.PRO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle("무료 체험이 ${day.monthValue}월 ${day.dayOfMonth}일에 끝나요")
            .setContentText("그날부터 결제돼요. 앱을 열어 계속 쓸지 알려 주세요. 해지는 Play 스토어에서 해요.")
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.PRO, 3302))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.PRO_ID, notification)
    }
}

class TrialNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.appContainer
        val state = c.proStore.current
        val sub = state.sub ?: return
        val end = sub.trialEnd(Today.zone())
        val stage = TrialConsent.stage(sub.play(), end, state.consentAt, Today.instant())
        if (stage != TrialConsent.Stage.ASK && stage != TrialConsent.Stage.LAST_DAY) return
        TrialNotice.post(context, end)
        c.proStore.trialNoticed(sub.purchasedAt)
    }
}
