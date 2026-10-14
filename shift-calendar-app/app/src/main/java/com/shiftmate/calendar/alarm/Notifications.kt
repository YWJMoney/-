package com.shiftmate.calendar.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shiftmate.calendar.MainActivity
import com.shiftmate.calendar.R

object Notifications {
    /** The service plays the chosen sound itself, so the channel is silent. */
    const val ALARM_CHANNEL_ID = "shift_alarm_v1"
    const val FALLBACK_CHANNEL_ID = "shift_alarm_fallback_v1"
    const val ALARM_NOTIFICATION_ID = 7001

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
