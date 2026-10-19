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
import com.bamnat.app.data.PersonalCalendar
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

    fun reschedule(context: Context, link: ShareLinkState?, noticedFor: Instant?, now: Instant = Instant.now()) =
        reschedule(context, PersonalCalendar.MAIN, link, noticedFor, now)

    /** [MCAL-03] Each of the user's calendars has its own link, so its own alarm and notice. */
    fun reschedule(context: Context, calendarId: String, link: ShareLinkState?, noticedFor: Instant?, now: Instant = Instant.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = link?.let { noticeAt(it, noticedFor, now) }
        if (at == null) {
            am.cancel(pending(context, calendarId))
            // A link cut or extended takes its notice away too.
            if (link == null || noticedFor != link.expiresAt) Notifications.cancel(context, Notifications.SHARE_ID, tag(calendarId))
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(context, calendarId))
    }

    /** [MCAL-03] The main calendar keeps the notice it always had; an added one's goes by its id. */
    private fun tag(calendarId: String): String? = calendarId.takeUnless { it == PersonalCalendar.MAIN }?.let { "share:$it" }

    /** [MCAL-03] The calendar a receiver's intent is for. */
    internal fun calendarOf(intent: Intent): String = intent.getStringExtra(EXTRA_CALENDAR) ?: PersonalCalendar.MAIN

    private const val EXTRA_CALENDAR = "com.bamnat.app.extra.SHARE_CALENDAR"

    /** An added calendar's intent differs by its data, so one calendar's alarm never replaces another's. */
    private fun Intent.forCalendar(calendarId: String): Intent = apply {
        if (calendarId == PersonalCalendar.MAIN) return@apply
        putExtra(EXTRA_CALENDAR, calendarId)
        data = android.net.Uri.parse("bamnat-share://calendar/$calendarId")
    }

    private fun pending(context: Context, calendarId: String): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, ShareExpiryReceiver::class.java).forCalendar(calendarId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [MCAL-03] "근무표" for the main calendar, "‘알바’ 달력" for an added one. */
    private fun what(calendarId: String, name: String?): String =
        if (calendarId == PersonalCalendar.MAIN || name == null) "근무표" else "‘$name’ 달력"

    private fun opener(context: Context, calendarId: String): PendingIntent =
        Notifications.open(context, NoticeLinks.Target.SHARE, OPEN_REQUEST, calendarId.takeUnless { it == PersonalCalendar.MAIN })

    fun post(context: Context, link: ShareLinkState, calendarId: String = PersonalCalendar.MAIN, name: String? = null) {
        val notification = NotificationCompat.Builder(context, Notifications.SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle("${what(calendarId, name)} 링크가 ${dateText(link.expiresAt)}에 닫혀요")
            .setContentText("늘리지 않으면 받은 사람 앱에서도 근무표가 사라져요")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "늘리지 않으면 받은 사람 앱에서도 근무표가 사라져요. ‘${link.ttlDays}일 늘리기’를 누르면 같은 링크가 오늘부터 ${link.ttlDays}일 동안 열려요."))
            .setContentIntent(opener(context, calendarId))
            .addAction(0, "${link.ttlDays}일 늘리기", PendingIntent.getBroadcast(
                context, EXTEND_REQUEST, Intent(context, ShareExtendReceiver::class.java).forCalendar(calendarId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.SHARE_ID, notification, tag(calendarId))
    }

    /** After the button: what happened, in the same place. */
    fun postResult(context: Context, title: String, text: String, calendarId: String = PersonalCalendar.MAIN) {
        val notification = NotificationCompat.Builder(context, Notifications.SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(opener(context, calendarId))
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        Notifications.post(context, Notifications.SHARE_ID, notification, tag(calendarId))
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
        val id = ShareExpiryReminder.calendarOf(intent)
        c.appScope.launch {
            try {
                // [MCAL-03] A calendar removed since keeps no link and gets no notice.
                val cal = c.calendars.get(id)
                if (cal == null) {
                    ShareExpiryReminder.reschedule(context, id, null, null)
                    return@launch
                }
                val store = c.shareLinksOf(id)
                store.expire()
                val link = store.link.value
                if (link != null && store.noticedFor != link.expiresAt && ShareExpiryReminder.closingSoon(link)) {
                    ShareExpiryReminder.post(context, link, id, cal.name)
                    store.noticedFor = link.expiresAt
                }
                ShareExpiryReminder.reschedule(context, id, store.link.value, store.noticedFor)
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
        val id = ShareExpiryReminder.calendarOf(intent)
        c.appScope.launch {
            try {
                when (c.shareSync.extend(id)) {
                    ShareSync.Result.Sent -> c.shareLinksOf(id).link.value?.let {
                        ShareExpiryReminder.postResult(context, "근무표 링크를 늘렸어요", "${ShareExpiryReminder.dateText(it.expiresAt)}까지 열려요", id)
                    }
                    // These months have nothing to show, so the server would refuse the link.
                    ShareSync.Result.Same -> ShareExpiryReminder.postResult(context, "링크를 늘리지 못했어요", "보낼 근무가 없어요. 눌러서 공유 화면에서 확인해 주세요", id)
                    ShareSync.Result.Gone -> ShareExpiryReminder.postResult(context, "근무표 링크가 이미 닫혔어요", "눌러서 새 링크를 만들어 보내 주세요", id)
                    ShareSync.Result.Offline -> ShareExpiryReminder.postResult(context, "링크를 늘리지 못했어요", "인터넷 연결을 확인하고 눌러서 다시 해 주세요", id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
