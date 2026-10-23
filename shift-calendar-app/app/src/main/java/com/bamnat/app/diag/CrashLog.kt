package com.bamnat.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bamnat.app.BuildConfig
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import java.io.File

/**
 * [MEAS-08] 328: when the app stops by itself, a short note of where, kept on the phone.
 *
 * The note holds the app's version, the Android version, the phone's maker and model, the thread and the
 * exception classes with their code lines, never an exception's message (it can quote what a person typed) and
 * nothing from the calendar. It stays in files/diag (left out of every backup) and leaves the phone only when the
 * person reads it in 알람 점검 and sends it with "점검 내용과 함께 문의하기".
 */
object CrashLog {
    private const val DIR = "diag"
    private const val PREFIX = "crash-"
    const val KEEP = 5
    const val FRAMES = 30
    private const val NOTICE_ID = 7006
    private const val DAYS_30 = 30L * 24 * 60 * 60 * 1000

    @Volatile private var installed = false

    /** Called first thing in Application.onCreate; the handler before it (Android's) still ends the process. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread.name, error, System.currentTimeMillis()) }
            runCatching { notice(app) }
            before?.uncaughtException(thread, error)
        }
    }

    fun dir(context: Context) = File(context.filesDir, DIR)

    /** Writes one note and keeps the newest [KEEP]. */
    fun write(context: Context, thread: String, error: Throwable, atMillis: Long): File {
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, "$PREFIX$atMillis.txt")
        file.writeText(describe(thread, error))
        notes(context).drop(KEEP).forEach { it.delete() }
        return file
    }

    fun describe(thread: String, error: Throwable): String = buildString {
        appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("thread ${Telemetry.cleanDetail(thread)}")
        var cause: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            appendLine(if (cause === error) cause.javaClass.name else "caused by ${cause.javaClass.name}")
            cause.stackTrace.take(FRAMES).forEach { appendLine("  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})") }
            cause = cause.cause
        }
    }

    /** The notes, newest first. */
    fun notes(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.name.removePrefix(PREFIX).removeSuffix(".txt").toLongOrNull() ?: 0L }

    fun clear(context: Context) = notes(context).forEach { it.delete() }

    /** How the app ended in the last 30 days, by Android's own record (Android 11+): crash, ANR, native crash. */
    data class Exits(val crashes: Int, val anrs: Int, val native: Int)

    fun exits(context: Context, now: Long = System.currentTimeMillis()): Exits? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val recent = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) }.getOrNull()
            ?.filter { now - it.timestamp < DAYS_30 } ?: return null
        return Exits(
            crashes = recent.count { it.reason == ApplicationExitInfo.REASON_CRASH },
            anrs = recent.count { it.reason == ApplicationExitInfo.REASON_ANR },
            native = recent.count { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE },
        )
    }

    /** The "앱 멈춤" lines of the diagnostics report. */
    fun reportLines(context: Context, now: Long = System.currentTimeMillis()): List<String> = buildList {
        val notes = notes(context).filter { now - (it.name.removePrefix(PREFIX).removeSuffix(".txt").toLongOrNull() ?: 0L) < DAYS_30 }
        exits(context, now)?.let { add("최근 30일: 멈춤 ${it.crashes}회 · 응답 없음(ANR) ${it.anrs}회 · 기기 쪽 멈춤 ${it.native}회") }
        add("남은 기록: ${notes.size}개")
        notes.firstOrNull()?.let { add("가장 최근 기록:"); addAll(it.readLines().take(12)) }
    }

    private fun notice(context: Context) {
        val notification = NotificationCompat.Builder(context, Notifications.STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("밤낮이 멈췄어요")
            .setContentText("알람 점검에서 무엇이 멈췄는지 보고, 원하면 보낼 수 있어요")
            .setContentIntent(Notifications.open(context, NoticeLinks.Target.ALARM_CHECK, 2104))
            .setAutoCancel(true)
            .build()
        Notifications.post(context, NOTICE_ID, notification)
    }
}
