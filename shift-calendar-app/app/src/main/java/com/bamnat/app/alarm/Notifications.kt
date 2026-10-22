package com.bamnat.app.alarm

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R

/**
 * [SLP-15] Every channel, made when the app starts so all of them can be set in the system settings
 * before the first notice, in three groups: 근무 알람, 하루 안내, 돈과 휴가.
 */
object Notifications {
    /** The service plays the chosen sound itself, so the channel is silent. */
    const val ALARM_CHANNEL_ID = "shift_alarm_v1"
    const val FALLBACK_CHANNEL_ID = "shift_alarm_fallback_v1"
    /** A snooze waiting to ring again, and an alarm permission that went missing. */
    const val STATUS_CHANNEL_ID = "alarm_status_v1"
    /** 하루 브리핑, 잘 준비 and 자는 중: silent. */
    const val DAY_CHANNEL_ID = "day_flow_v1"
    /** When a night ends: the one day notice with a sound. */
    const val COMMUTE_CHANNEL_ID = "commute_v1"
    const val JOB_CHANNEL_ID = "job_reminder_v1"
    const val MONEY_CHANNEL_ID = "money_alert_v1"
    const val LEAVE_CHANNEL_ID = "leave_notice_v1"
    /** [SCHED-01] A memo that is a schedule, when it comes. */
    const val MEMO_CHANNEL_ID = "memo_schedule_v1"
    /** [SHR-12] The sharer's link closing soon. */
    const val SHARE_CHANNEL_ID = "share_expiry_v1"
    /** [FEAT-18] The lock-screen 오늘 근무 notice: no sound, no badge, shown in full on the lock screen. */
    const val TODAY_CHANNEL_ID = "today_shift_v1"
    /** [INV-08] The investor's 장 시작·마감, 정기 매수일, 목표가 확인 and date notices. */
    const val INVEST_CHANNEL_ID = "invest_alarm_v1"
    /** [MON-06] The one notice before a free trial turns paid. */
    const val PRO_CHANNEL_ID = "pro_notice_v1"
    /** Before 1.11 every sleep tip made a sound here; a channel's importance cannot be lowered, so it goes. */
    private const val OLD_SLEEP_CHANNEL_ID = "sleep_tips_v1"

    private const val GROUP_ALARMS = "alarms"
    private const val GROUP_DAY = "day"
    private const val GROUP_MONEY = "money"

    const val ALARM_NOTIFICATION_ID = 7001
    private const val EXACT_WARNING_ID = 7003
    const val SNOOZE_NOTIFICATION_ID = 7004
    const val BRIEFING_ID = 7310
    const val BEDTIME_ID = 7311
    const val COMMUTE_ID = 7312
    const val SLEEPING_ID = 7313
    const val LEAVE_ID = 7201
    const val SHARE_ID = 7202
    const val PRO_ID = 7203

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroups(listOf(
            NotificationChannelGroup(GROUP_ALARMS, "근무 알람"),
            NotificationChannelGroup(GROUP_DAY, "하루 안내"),
            NotificationChannelGroup(GROUP_MONEY, "돈과 휴가"),
        ))
        val alarm = NotificationChannel(ALARM_CHANNEL_ID, context.getString(R.string.channel_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarm_description)
            group = GROUP_ALARMS
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        // Used only when the ringing service cannot start, so this channel makes the sound itself.
        val fallback = NotificationChannel(FALLBACK_CHANNEL_ID, "근무 알람 예비 소리", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람 화면을 띄우지 못할 때만 대신 울려요. 끄면 알람을 놓칠 수 있어요"
            group = GROUP_ALARMS
            setSound(
                android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val status = NotificationChannel(STATUS_CHANNEL_ID, "알람 상태", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "다시 울릴 알람과, 알람이 제때 울리지 못하게 하는 문제를 알려요"
            group = GROUP_ALARMS
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val day = NotificationChannel(DAY_CHANNEL_ID, "오늘의 흐름", NotificationManager.IMPORTANCE_LOW).apply {
            description = "하루 브리핑, 잘 준비, 자는 중. 소리 없이 알림창에만 와요"
            group = GROUP_DAY
        }
        val commute = NotificationChannel(COMMUTE_CHANNEL_ID, "퇴근길 안전", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "야간·당번이 끝날 때 한 번, 졸음운전을 조심하라고 알려요"
            group = GROUP_DAY
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val job = NotificationChannel(JOB_CHANNEL_ID, "일감 시작 전", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "잡아 둔 일감이 다가오면 알려요"
            group = GROUP_MONEY
        }
        val money = NotificationChannel(MONEY_CHANNEL_ID, "나갈 돈·받을 돈", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "곧 나갈 돈과 아직 못 받은 돈을 알려요. 자는 동안에는 깬 뒤로 미뤄요"
            group = GROUP_MONEY
        }
        val leave = NotificationChannel(LEAVE_CHANNEL_ID, "연차 소멸 안내", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "올해 안 쓰면 없어지는 연차를 11월과 12월 첫날에 알려요"
            group = GROUP_MONEY
        }
        val memo = NotificationChannel(MEMO_CHANNEL_ID, "메모 일정", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "일정으로 넣은 메모를 그 시각에 알림창에 띄워요"
            group = GROUP_DAY
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val share = NotificationChannel(SHARE_CHANNEL_ID, "근무표 링크 기간", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "보낸 근무표 링크가 닫히기 며칠 전에 알려요. 알림에서 바로 늘릴 수 있어요"
            group = GROUP_DAY
        }
        val today = NotificationChannel(TODAY_CHANNEL_ID, "잠금 화면 오늘 근무", NotificationManager.IMPORTANCE_LOW).apply {
            description = "오늘과 내일 근무를 잠금 화면과 알림창에 늘 보여 줘요. 소리는 나지 않아요"
            group = GROUP_DAY
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val invest = NotificationChannel(INVEST_CHANNEL_ID, "투자 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "장 시작·마감, 정기 매수일, 목표가 확인, 적어 둔 배당·실적 날을 알려요. 알림에서 바로 매매를 적을 수 있어요"
            group = GROUP_MONEY
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        val pro = NotificationChannel(PRO_CHANNEL_ID, "구독 안내", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "1개월 무료 체험이 유료로 바뀌기 7일 전에 계속 쓸지 한 번 여쭤봐요"
            group = GROUP_MONEY
        }
        nm.createNotificationChannels(listOf(alarm, fallback, status, day, commute, job, money, leave, memo, share, today, invest, pro))
        nm.deleteNotificationChannel(OLD_SLEEP_CHANNEL_ID)
    }

    /** [SLP-15] A notice's tap opens the screen it is about. */
    fun open(context: Context, target: NoticeLinks.Target, requestCode: Int, calendar: String? = null): PendingIntent = PendingIntent.getActivity(
        context, requestCode, NoticeLinks.intent(context, target, calendar),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Posts unless notifications were turned off in the meantime. */
    fun post(context: Context, id: Int, notification: android.app.Notification, tag: String? = null) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Notifications were turned off between the check and the post.
        }
    }

    fun cancel(context: Context, id: Int, tag: String? = null) = NotificationManagerCompat.from(context).cancel(tag, id)

    /**
     * Tells the user that alarms can no longer ring on time because the exact-alarm permission
     * was turned off (Android 12; on 13+ the permission cannot be revoked) [SHR-08].
     */
    fun postExactAlarmWarning(context: Context) {
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("알람이 늦게 울릴 수 있어요")
            .setContentText("‘정확한 시간에 울리기’ 권한이 꺼졌어요. 눌러서 다시 켜 주세요")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("‘정확한 시간에 울리기’ 권한이 꺼져서 근무 알람이 몇 분 늦게 울릴 수 있어요. 눌러서 알람 점검에서 다시 켜 주세요."))
            .setContentIntent(open(context, NoticeLinks.Target.ALARM_CHECK, 2102))
            .setAutoCancel(true)
            .build()
        post(context, EXACT_WARNING_ID, notification)
    }

    /** Plain alarm notification used when the ringing service cannot start. */
    fun postFallbackAlarm(context: Context, ring: RingRequest) {
        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(ring.title)
            .setContentText(AlarmService.describe(ring))
            .setContentIntent(open(context, NoticeLinks.Target.TODAY, 2101))
            .build()
        post(context, ALARM_NOTIFICATION_ID + 1, notification)
    }

    /**
     * [SLP-15] A snooze waiting to ring again stays in the shade, quietly, with a way to stop it, so a
     * tap on the lock screen leaves a trace and can be taken back there.
     */
    fun postSnoozeWaiting(context: Context, ring: RingRequest, atMillis: Long) {
        val at = AlarmScheduler.millisToLocal(atMillis).toLocalTime()
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOngoing(true)
            .setContentTitle("%02d:%02d에 다시 울려요".format(at.hour, at.minute))
            .setContentText(ring.title)
            .setWhen(atMillis)
            .setShowWhen(false)
            .setContentIntent(open(context, NoticeLinks.Target.TODAY, 2103))
            .addAction(NotificationCompat.Action.Builder(0, "지금 끄기", PendingIntent.getBroadcast(
                context, 2104, Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_CANCEL_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )).setAuthenticationRequired(false).build())
            .build()
        post(context, SNOOZE_NOTIFICATION_ID, notification)
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** [SLP-15] Notifications are allowed and this channel was not turned off in the system settings. */
    fun channelOn(context: Context, channelId: String): Boolean {
        if (!canPost(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = nm.getNotificationChannel(channelId) ?: return true
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        // A whole group can be turned off from Android 9.
        if (Build.VERSION.SDK_INT >= 28 && channel.group?.let { nm.getNotificationChannelGroup(it) }?.isBlocked == true) return false
        return true
    }

    /** Android 14+ lets users turn off full-screen alarms per app. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
