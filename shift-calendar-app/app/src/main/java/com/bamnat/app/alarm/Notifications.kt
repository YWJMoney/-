package com.bamnat.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.MainActivity
import com.bamnat.app.R

object Notifications {
    /** The service plays the chosen sound itself, so the channel is silent. */
    const val ALARM_CHANNEL_ID = "shift_alarm_v1"
    const val FALLBACK_CHANNEL_ID = "shift_alarm_fallback_v1"
    const val ALARM_NOTIFICATION_ID = 7001
    private const val EXACT_WARNING_ID = 7003

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        // Used only when the ringing service cannot start, so this channel makes the sound itself.
        val fallback = NotificationChannel(
            FALLBACK_CHANNEL_ID,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_description)
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
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, fallback))
    }

    /**
     * Tells the user that alarms can no longer ring on time because the exact-alarm permission
     * was turned off (Android 12; on 13+ the permission cannot be revoked) [SHR-08].
     */
    fun postExactAlarmWarning(context: Context) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 2102,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("알람이 늦게 울릴 수 있어요")
            .setContentText("‘정확한 시간에 울리기’ 권한이 꺼졌어요. 설정 > 알람에서 다시 켜 주세요")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("‘정확한 시간에 울리기’ 권한이 꺼져서 근무 알람이 몇 분 늦게 울릴 수 있어요. 밤낮 설정 > 알람에서 다시 켜 주세요."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(EXACT_WARNING_ID, notification)
        } catch (e: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }

    /** Plain alarm notification used when the ringing service cannot start. */
    fun postFallbackAlarm(context: Context, ring: RingRequest) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 2101,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(ring.title)
            .setContentText(AlarmService.describe(ring))
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ALARM_NOTIFICATION_ID + 1, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the post.
        }
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 14+ lets users turn off full-screen alarms per app. */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
