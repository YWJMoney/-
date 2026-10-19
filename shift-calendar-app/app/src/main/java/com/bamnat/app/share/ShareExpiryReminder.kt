package com.bamnat.app.share

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
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [SHR-12] Before the link closes, a notice says when, with a button that keeps it open as long
 * again as the sharer first picked: 3 days ahead for a 30- or 90-day link, a day ahead for a 7-day
 * one, at 10:00. Inexact: an hour late is fine and needs no exact-alarm permission. Once per period;
 * a link extended or made again gets its own.
 */
object ShareExpiryReminder {
    private const val REQUEST = 3301
    private const val OPEN_REQUEST = 3302
    private const val EXTEND_REQUEST = 3303
    private val NOTICE_TIME: LocalTime = LocalTime.of(10, 0)

    /** How long before [ShareLinkState.expiresAt] the notice comes. */
    fun lead(ttlDays: Int): Duration = if (ttlDays <= 7) Duration.ofDays(1) else Duration.ofDays(3)

    /**
     * When the notice for [link] comes: 10:00 on the day [lead] before it closes, or in a minute when
     * that has passed (the phone was off) and the link is still open. Null once it was posted for this end date.
     */
    fun noticeAt(link: ShareLinkState, noticedFor: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        if (noticedFor == link.expiresAt || !link.expiresAt.isAfter(now)) return null
        val day = link.expiresAt.minus(lead(link.ttlDays)).atZone(zone).toLocalDate()
        val at = day.atTime(NOTICE_TIME).atZone(zone).toInstant()
        return if (at.isAfter(now)) at else now.plusSeconds(60)
    }

    fun reschedule(context: Context, link: ShareLinkState?, noticedFor: Instant?, now: Instant = Instant.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = link?.let { noticeAt(it, noticedFor, now) }
        if (at == null) {
            am.cancel(pending(context))
            // A link cut or extended takes its notice away too.
            if (link == null || noticedFor != link.expiresAt) Notifications.cancel(context, Notifications.SHARE_ID)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ShareExpiryReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, link: ShareLinkState) {
        val notification = NotificationCompat.Builder(context, Notifications.SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle("근무표 링크가 ${dateText(link.expiresAt)}에 닫혀요")
            .setContentText("늘리지 않으면 받은 사람 앱에서도 근무표가 사라져요")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "늘리지 않으면 받은 사람 앱에서도 근무표가 사라져요. ‘${link.ttlDays}일 늘리기’를 누르면 같은 링크가 오늘부터 ${link.ttlDays}일 동안 열려요."))
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.SHARE, OPEN_REQUEST))
            .addAction(0, "${link.ttlDays}일 늘리기", PendingIntent.getBroadcast(
                context, EXTEND_REQUEST, Intent(context, ShareExtendReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.SHARE_ID, notification)
    }

    /** After the button: what happened, in the same place. */
    fun postResult(context: Context, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, Notifications.SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.SHARE, OPEN_REQUEST))
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.SHARE_ID, notification)
    }

    /** "11월 3일" */
    fun dateText(at: Instant): String =
        at.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("M월 d일"))

    /** [SHR-12] Whether the sheet should say the link closes soon. */
    fun closingSoon(link: ShareLinkState, now: Instant = Instant.now()): Boolean =
        link.expiresAt.isAfter(now) && !link.expiresAt.minus(lead(link.ttlDays)).isAfter(now)
}

class ShareExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.appContainer
        val pending = goAsync()
        c.appScope.launch {
            try {
                c.shareLinks.expire()
                val link = c.shareLinks.link.value
                if (link != null && c.shareLinks.noticedFor != link.expiresAt && ShareExpiryReminder.closingSoon(link)) {
                    ShareExpiryReminder.post(context, link)
                    c.shareLinks.noticedFor = link.expiresAt
                }
                ShareExpiryReminder.reschedule(context, c.shareLinks.link.value, c.shareLinks.noticedFor)
            } finally {
                pending.finish()
            }
        }
    }
}

/** [SHR-12] ‘N일 늘리기’ on the notice: keeps the same link open, from the lock screen too. */
class ShareExtendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.appContainer
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (c.shareSync.extend()) {
                    ShareSync.Result.Sent -> c.shareLinks.link.value?.let {
                        ShareExpiryReminder.postResult(context, "근무표 링크를 늘렸어요", "${ShareExpiryReminder.dateText(it.expiresAt)}까지 열려요")
                    }
                    // These months have nothing to show, so the server would refuse the link.
                    ShareSync.Result.Same -> ShareExpiryReminder.postResult(context, "링크를 늘리지 못했어요", "보낼 근무가 없어요. 눌러서 공유 화면에서 확인해 주세요")
                    ShareSync.Result.Gone -> ShareExpiryReminder.postResult(context, "근무표 링크가 이미 닫혔어요", "눌러서 새 링크를 만들어 보내 주세요")
                    ShareSync.Result.Offline -> ShareExpiryReminder.postResult(context, "링크를 늘리지 못했어요", "인터넷 연결을 확인하고 눌러서 다시 해 주세요")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
