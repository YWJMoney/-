package com.shiftmate.calendar.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shiftmate.calendar.R
import com.shiftmate.calendar.appContainer
import com.shiftmate.calendar.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.format.DateTimeFormatter

/**
 * Rings the alarm: plays the chosen sound on the alarm stream, vibrates, and shows a
 * full-screen notification. Runs as a foreground service so it keeps ringing with the screen off.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volume = 1f

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ring = RingRequest.fromIntent(intent)
                if (ring == null) {
                    // startForegroundService() requires startForeground() even when there is nothing to do.
                    startInForeground(null)
                    finish()
                } else {
                    startRinging(ring)
                }
            }
            ACTION_SNOOZE -> {
                val ring = _ringing.value
                if (ring != null) {
                    appContainer.alarmScheduler.scheduleSnooze(ring, appContainer.settingsRepository.current.snoozeMinutes)
                }
                finish()
            }
            else -> finish() // ACTION_DISMISS, or a restart with no intent
        }
        return START_NOT_STICKY
    }

    private fun startRinging(ring: RingRequest) {
        stopOutputs()
        _ringing.value = ring
        startInForeground(ring)

        val settings = appContainer.settingsRepository.current
        val sound = ring.soundUri ?: settings.defaultAlarmSound
        if (sound != AppSettings.SILENT_SOUND) {
            requestFocus()
            playSound(candidateUris(sound), settings.gradualVolume)
        }
        if (settings.vibrate || sound == AppSettings.SILENT_SOUND) startVibration()

        handler.postDelayed({ finish() }, settings.ringMinutes.coerceIn(1, 60) * 60_000L)
    }

    private fun startInForeground(ring: RingRequest?) {
        val notification = buildNotification(ring)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ALARM_NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // Only exact alarms may start a foreground service from the background. If exact alarms
            // were revoked, still show the alarm notification and ring for as long as the system allows.
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) {
                NotificationManagerCompat.from(this).notify(Notifications.ALARM_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(ring: RingRequest?): android.app.Notification {
        val fullScreen = PendingIntent.getActivity(
            this, REQ_FULL_SCREEN,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeMinutes = appContainer.settingsRepository.current.snoozeMinutes
        val builder = NotificationCompat.Builder(this, Notifications.ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
        if (ring == null) {
            return builder.setContentTitle(getString(R.string.app_name)).build()
        }
        return builder
            .setContentTitle(ring.title)
            .setContentText(describe(ring))
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "${snoozeMinutes}분 후 다시", serviceIntent(this, ACTION_SNOOZE, REQ_SNOOZE))
            .addAction(0, "끄기", serviceIntent(this, ACTION_DISMISS, REQ_DISMISS))
            // Swiping the notification away must not leave the alarm ringing with no controls.
            .setDeleteIntent(serviceIntent(this, ACTION_SNOOZE, REQ_DELETE))
            .build()
    }

    /** Chosen sound first, then the system alarm, ringtone and notification sounds. */
    private fun candidateUris(sound: String?): List<Uri> = buildList {
        sound?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull()?.let(::add) }
        RingtoneManager.getActualDefaultRingtoneUri(this@AlarmService, RingtoneManager.TYPE_ALARM)?.let(::add)
        add(Settings.System.DEFAULT_ALARM_ALERT_URI)
        add(Settings.System.DEFAULT_RINGTONE_URI)
        add(Settings.System.DEFAULT_NOTIFICATION_URI)
    }.distinct()

    private fun playSound(candidates: List<Uri>, gradual: Boolean) {
        for (uri in candidates) {
            val mp = MediaPlayer()
            val started = runCatching {
                mp.setAudioAttributes(alarmAttributes)
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                volume = if (gradual) GRADUAL_START else 1f
                mp.setVolume(volume, volume)
                mp.start()
            }.isSuccess
            if (started) {
                player = mp
                if (gradual) handler.postDelayed(volumeRamp, RAMP_STEP_MILLIS)
                return
            }
            mp.release()
        }
        // No playable sound at all: make sure the user still notices.
        startVibration()
    }

    private val volumeRamp = object : Runnable {
        override fun run() {
            val mp = player ?: return
            volume = (volume + RAMP_INCREMENT).coerceAtMost(1f)
            runCatching { mp.setVolume(volume, volume) }
            if (volume < 1f) handler.postDelayed(this, RAMP_STEP_MILLIS)
        }
    }

    private fun startVibration() {
        if (vibrator != null) return
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0), alarmAttributes)
        vibrator = v
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttributes)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(request)
        focusRequest = request
    }

    private fun stopOutputs() {
        handler.removeCallbacksAndMessages(null)
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            mp.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun finish() {
        stopOutputs()
        _ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(Notifications.ALARM_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        stopOutputs()
        _ringing.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.shiftmate.calendar.alarm.START"
        const val ACTION_DISMISS = "com.shiftmate.calendar.alarm.DISMISS"
        const val ACTION_SNOOZE = "com.shiftmate.calendar.alarm.SNOOZE"

        private const val REQ_FULL_SCREEN = 2001
        private const val REQ_SNOOZE = 2002
        private const val REQ_DISMISS = 2003
        private const val REQ_DELETE = 2004
        private const val GRADUAL_START = 0.15f
        private const val RAMP_INCREMENT = 0.085f
        private const val RAMP_STEP_MILLIS = 2_000L

        private val _ringing = MutableStateFlow<RingRequest?>(null)
        /** The alarm currently ringing, observed by [AlarmActivity]. */
        val ringing: StateFlow<RingRequest?> = _ringing.asStateFlow()

        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

        fun describe(ring: RingRequest): String {
            val start = ring.shiftStart.format(timeFormat)
            val work = ring.shiftName.isNotBlank()
            return when {
                ring.isSnooze && work -> "$start 근무 시작 · 다시 알림"
                ring.isSnooze -> "다시 알림"
                ring.custom && work -> "$start 근무 시작 · 직접 설정한 알람"
                ring.custom -> "직접 설정한 알람"
                else -> "$start 근무 시작 · ${ring.offsetMinutes}분 전 알람"
            }
        }

        fun start(context: Context, ring: RingRequest) {
            val intent = ring.putInto(Intent(context, AlarmService::class.java).setAction(ACTION_START))
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismiss(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS))
        }

        fun snooze(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE))
        }

        private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context, requestCode,
                Intent(context, AlarmService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
