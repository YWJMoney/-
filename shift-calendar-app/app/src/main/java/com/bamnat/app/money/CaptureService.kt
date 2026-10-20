package com.bamnat.app.money

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.bamnat.app.appContainer
import com.bamnat.core.money.PaymentParser
import kotlinx.coroutines.launch

/**
 * [LEDG-02] Writes payments down from card, bank and pay-app notifications (and the SMS app's
 * notifications), ported from 살림왕's CaptureService. Every other notification is looked at once
 * on the phone and dropped; nothing is sent anywhere. No SMS permission is used.
 */
class CaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bodies = ArrayList<String>()
        @Suppress("DEPRECATION")
        val messages = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            // A messaging-style notification stacks texts; each is one message (repeats are dropped later).
            // The whole stack is posted again with each new message, so only recent ones are read; an
            // old line the user already deleted or fixed must not come back.
            messages.forEach { m ->
                val b = m as? Bundle ?: return@forEach
                val at = b.getLong("time", sbn.postTime)
                if (at >= sbn.postTime - STACK_WINDOW_MILLIS) b.getCharSequence("text")?.toString()?.trim()?.let(bodies::add)
            }
        } else {
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            bodies += big.ifEmpty { ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
        }
        val found = bodies.map { "$title\n$it".trim() }.filter { PaymentParser.looksFinancial(it) }
        if (found.isEmpty()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        val container = applicationContext.appContainer
        // [LEDG-22] Listed for the user to turn off even when it is off already; then their filter.
        val prefs = container.autoRecordPrefs
        prefs.sawApp(sbn.packageName, app)
        val filter = prefs.current.filter
        val texts = found.filter { !filter.drops(sbn.packageName, it) }
        if (texts.isEmpty()) return
        val messenger = sbn.packageName in MESSENGERS
        val pkg = sbn.packageName
        container.appScope.launch {
            texts.forEach { text ->
                // Any text from any app reaches the parser; one it trips on must not take the app down.
                // [CALS-04] Written into the calendar its card, account or app belongs to, and only there.
                runCatching { container.capture(text, pkg, app, sbn.postTime, messenger) }
            }
        }
    }

    companion object {
        private const val STACK_WINDOW_MILLIS = 10L * 60 * 1000

        /** Chat apps: card and bank 알림톡 arrive here too, but so do people's own messages. */
        private val MESSENGERS = setOf(
            "com.kakao.talk", "jp.naver.line.android", "org.telegram.messenger", "com.whatsapp",
            "com.facebook.orca", "com.instagram.android", "com.discord", "com.google.android.apps.dynamite",
        )

        private fun component(context: Context) = ComponentName(context, CaptureService::class.java)

        /** Turned on in the app (the service exists) — access may still be missing. */
        fun isOn(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        /** The user granted notification access in the phone's settings. */
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun setOn(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** The phone's page for this app's notification access. */
        fun accessSettings(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
    }
}
