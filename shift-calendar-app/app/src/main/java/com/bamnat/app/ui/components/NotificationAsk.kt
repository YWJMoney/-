package com.bamnat.app.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.review.InAppReview.findActivity

/** Opens a system settings page, or the app's own page when the phone has no such screen. */
object SystemSettings {
    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun appNotifications(context: Context) = open(
        context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** One kind of notice, where the user turned it off. */
    fun channel(context: Context, channelId: String) = open(
        context, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
    )
}

/**
 * [SLP-15] Asks for notifications where they are needed, after the screen has said why: the
 * system's question while Android still lets the app ask, the app's notification settings once it
 * does not (asked twice, or turned off there). Returns what to call on the user's tap.
 */
@Composable
fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            val device = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
            val activity = context.findActivity()
            val canAsk = Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context) && (
                !device.getBoolean(K_ASKED, false) ||
                    (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
                )
            if (canAsk) {
                device.edit().putBoolean(K_ASKED, true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemSettings.appNotifications(context)
            }
        }
    }
}

/** [ONBOARD-01] The consent pages asked the system's question themselves: later taps open the settings once Android stops asking. */
fun markNotificationAsked(context: Context) {
    context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ASKED, true).apply()
}

/** Kept per phone, outside the backed-up settings, so a restore onto a new phone asks there again. */
private const val DEVICE_PREFS = "device"
private const val K_ASKED = "notification_asked"
