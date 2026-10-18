package com.bamnat.app.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bamnat.app.AppContainer
import com.bamnat.app.appContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.money.JobReminderScheduler
import com.bamnat.app.pay.LeaveReminder
import com.bamnat.core.notify.QuietHours
import com.bamnat.core.sleep.SleepSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SLP-15] Money and 연차 notices that come due while the user sleeps wait here, and come 30
 * minutes after waking ([QuietHours]). Each waits as where it came from and when it was due, so
 * what it says is worked out again when it comes: a bill recorded in the meantime is not announced.
 */
object QuietHold {
    enum class Source { MONEY, LEAVE }

    private const val PREFS = "quiet_hold"
    private const val K_HELD = "held"
    private const val REQUEST = 3401
    private val lock = Mutex()

    /** Keeps a notice from [source] due at [at] while the user sleeps; true when it was kept. */
    suspend fun holdIfAsleep(context: Context, source: Source, at: LocalDateTime): Boolean = lock.withLock {
        val c = context.appContainer
        val settings = c.settingsRepository.current
        if (!settings.quietWhileAsleep) return@withLock false
        holdUntil(c, settings, at, LocalDateTime.now()) ?: return@withLock false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(K_HELD, prefs.getStringSet(K_HELD, emptySet()).orEmpty() + "${source.name}|$at").apply()
        schedule(context, c, settings)
        true
    }

    /** Sends what may come now and sets an alarm for the rest: when the hold alarm rings, and when the user wakes. */
    suspend fun release(context: Context) = lock.withLock {
        val c = context.appContainer
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val held = prefs.getStringSet(K_HELD, emptySet()).orEmpty()
        if (held.isEmpty()) return@withLock
        val settings = c.settingsRepository.current
        val now = LocalDateTime.now()
        val keep = mutableSetOf<String>()
        val due = mutableListOf<Pair<Source, LocalDateTime>>()
        for (entry in held) {
            val (source, at) = parse(entry) ?: continue
            val wait = settings.quietWhileAsleep && holdUntil(c, settings, at, now) != null
            if (wait) keep += entry else due += source to at
        }
        prefs.edit().putStringSet(K_HELD, keep).apply()
        for ((source, at) in due.sortedBy { it.second }) when (source) {
            Source.MONEY -> JobReminderScheduler.postMoneyDue(context, at)
            Source.LEAVE -> LeaveReminder.postIfLapsing(context)
        }
        schedule(context, c, settings)
    }

    private suspend fun schedule(context: Context, c: AppContainer, settings: AppSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        val held = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(K_HELD, emptySet()).orEmpty()
        val next = held.mapNotNull(::parse).mapNotNull { (_, at) -> holdUntil(c, settings, at, now) }.minOrNull()
        val op = PendingIntent.getBroadcast(context, REQUEST, Intent(context, QuietHoldReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (next == null) {
            // Nothing waits any more, or what waits may come now: let the receiver send it.
            if (held.isEmpty()) am.cancel(op) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000, op)
            return
        }
        // A few minutes late is fine for these, and needs no exact alarm.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), op)
    }

    private suspend fun holdUntil(c: AppContainer, settings: AppSettings, at: LocalDateTime, now: LocalDateTime): LocalDateTime? {
        val log = c.sleepRepository.recent(days = 2).first()
        // The plan's sleep counts only for those who use the sleep times; a logged sleep counts for everyone.
        val plan = if (!settings.sleepTips || settings.workStyle?.usesPayEngine == false) null else {
            val snapshot = c.scheduleRepository.snapshot.first()
            SleepSchedule.of(snapshot.resolver, at.toLocalDate().minusDays(1), at.toLocalDate(), settings.alarmsEnabled,
                settings.sleepTargetMinutes, settings.commuteMinutes)
        }
        return QuietHours.holdUntil(at, now, plan, log, settings.sleepTargetMinutes)
    }

    private fun parse(entry: String): Pair<Source, LocalDateTime>? {
        val (name, time) = entry.split('|').takeIf { it.size == 2 } ?: return null
        val source = Source.entries.firstOrNull { it.name == name } ?: return null
        return runCatching { source to LocalDateTime.parse(time) }.getOrNull()
    }
}

class QuietHoldReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.appContainer.appScope.launch {
            try {
                QuietHold.release(context)
            } finally {
                pending.finish()
            }
        }
    }
}
