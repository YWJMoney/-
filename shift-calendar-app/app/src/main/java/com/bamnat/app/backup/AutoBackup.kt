package com.bamnat.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.R
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.data.BackupException
import com.bamnat.app.tier.Feature
import com.bamnat.core.backup.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/**
 * [PROV-03] 매일 자동 백업 (프로): once a day the same backup 백업 저장 makes, sealed with the user's
 * password, overwrites one file the user picked (on the phone or in Google Drive), is read back to check
 * it went in whole, and the time it last did is shown. Three days without a good backup bring a notice.
 *
 * The password is not kept: the key it gives (PBKDF2, as [BackupCrypto]) is, wrapped by the phone's key
 * store ([KeyBox]), so the daily run needs no password and the file still opens with it on any phone.
 * None of this goes into a backup: the file's permission and the key belong to this phone.
 */
class AutoBackup(
    private val c: AppContainer,
    private val keys: () -> KeyBox,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val prefs = c.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    data class State(
        val on: Boolean = false,
        /** The file's name as its app shows it, e.g. 밤낮-자동백업.json. */
        val name: String? = null,
        val lastOk: Instant? = null,
        /** When the first of the failures since the last good backup was, null while it works. */
        val failingSince: Instant? = null,
        val error: String? = null,
        val running: Boolean = false,
    )

    sealed interface Result {
        data class Ok(val at: Instant, val bytes: Int) : Result
        data class Failed(val why: String) : Result
        data object Off : Result
        /** Without 프로 it waits, and starts again with it. */
        data object Locked : Result
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun load() = State(
        on = prefs.getString(K_URI, null) != null && prefs.getString(K_KEY, null) != null,
        name = prefs.getString(K_NAME, null),
        lastOk = prefs.getLong(K_OK, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        failingSince = prefs.getLong(K_FAILING, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        error = prefs.getString(K_ERROR, null),
    )

    val on: Boolean get() = _state.value.on

    /**
     * Turns it on with [uri] (a file the user just made with the system's file picker) and [password],
     * and makes the first backup at once. The password is cleared here.
     */
    suspend fun turnOn(uri: Uri, password: CharArray): Result {
        val salt = BackupCrypto.newSalt()
        val key = try {
            withContext(Dispatchers.Default) { BackupCrypto.deriveKey(password, salt, ITERATIONS) }
        } finally {
            password.fill(' ')
        }
        val wrapped = try {
            withContext(Dispatchers.IO) { keys().forget(); keys().wrap(key) }
        } catch (e: Exception) {
            key.fill(0)
            return Result.Failed("휴대폰이 암호 열쇠를 만들지 못했어요")
        }
        key.fill(0)
        val old = prefs.getString(K_URI, null)
        hold(uri)
        prefs.edit().clear()
            .putString(K_URI, uri.toString()).putString(K_NAME, nameOf(uri))
            .putString(K_SALT, B64.encodeToString(salt)).putInt(K_ITER, ITERATIONS).putString(K_KEY, B64.encodeToString(wrapped))
            .commit()
        if (old != null && old != uri.toString()) release(Uri.parse(old))
        _state.value = load()
        AutoBackupJob.schedule(c.appContext)
        c.telemetry.event("auto_backup", "on")
        return runNow()
    }

    /** [uri] in place of the file it wrote to, with the same password; backs up there at once. */
    suspend fun moveTo(uri: Uri): Result {
        if (!on) return Result.Off
        val old = prefs.getString(K_URI, null)
        hold(uri)
        prefs.edit().putString(K_URI, uri.toString()).putString(K_NAME, nameOf(uri)).remove(K_FAILING).remove(K_ERROR).commit()
        if (old != null && old != uri.toString()) release(Uri.parse(old))
        _state.value = load()
        return runNow()
    }

    /** Stops, forgets the key and the file's permission; the file stays where it is, a backup like any other. */
    fun turnOff() {
        prefs.getString(K_URI, null)?.let { release(Uri.parse(it)) }
        runCatching { keys().forget() }
        prefs.edit().clear().commit()
        AutoBackupJob.cancel(c.appContext)
        Notifications.cancel(c.appContext, Notifications.BACKUP_ID)
        _state.value = load()
        c.telemetry.event("auto_backup", "off")
    }

    /** Whether the daily backup is owed: a day since the last good one, and a few hours since the last try. */
    fun due(now: Instant = clock()): Boolean {
        if (!on) return false
        val ok = _state.value.lastOk
        val tried = prefs.getLong(K_TRIED, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        return (ok == null || !ok.plus(DUE_AFTER).isAfter(now)) && (tried == null || !tried.plus(RETRY_AFTER).isAfter(now))
    }

    suspend fun runIfDue(): Result? = if (due()) runNow() else null

    /** Writes the backup now, whatever the time since the last. */
    suspend fun runNow(): Result = lock.withLock {
        if (!on) return@withLock Result.Off
        if (!c.featureGate.allows(Feature.AUTO_BACKUP)) return@withLock Result.Locked
        _state.value = _state.value.copy(running = true)
        val now = clock()
        val result = try {
            withContext(Dispatchers.IO) { write() }.let { Result.Ok(now, it) }
        } catch (e: KeyBox.KeyGone) {
            Result.Failed("휴대폰의 암호 열쇠가 없어졌어요. 자동 백업을 껐다 켜 주세요")
        } catch (e: SecurityException) {
            Result.Failed("백업 파일에 쓸 권한이 없어졌어요. ‘위치 바꾸기’로 다시 골라 주세요")
        } catch (e: FileNotFoundException) {
            Result.Failed("백업 파일을 찾을 수 없어요. 지웠거나 옮겼다면 ‘위치 바꾸기’로 다시 골라 주세요")
        } catch (e: Verify) {
            Result.Failed("저장한 파일을 다시 읽어 보니 내용이 달라요. 공간이 모자라지 않은지 확인해 주세요")
        } catch (e: BackupException) {
            Result.Failed("백업을 만들지 못했어요")
        } catch (e: IOException) {
            Result.Failed("백업 파일에 쓰지 못했어요. 인터넷이나 저장 공간을 확인해 주세요")
        } catch (e: IllegalArgumentException) {
            Result.Failed("이 위치에는 덮어쓸 수 없어요. ‘위치 바꾸기’로 다른 곳을 골라 주세요")
        }
        record(now, result)
        result
    }

    private class Verify : IOException("verify")

    /** The file's bytes are the backup and nothing after it, read back the same; the size written. */
    private suspend fun write(): Int {
        val uri = Uri.parse(prefs.getString(K_URI, null) ?: throw FileNotFoundException())
        val salt = B64.decode(prefs.getString(K_SALT, null) ?: throw KeyBox.KeyGone())
        val iterations = prefs.getInt(K_ITER, ITERATIONS)
        val key = keys().unwrap(B64.decode(prefs.getString(K_KEY, null) ?: throw KeyBox.KeyGone()))
        try {
            // [REL-15] A sealed file of format 3, its photos as files inside.
            val bytes = java.io.ByteArrayOutputStream().also { c.backupManager.exportFileWithKey(it, key, salt, iterations) }.toByteArray()
            overwrite(uri, bytes)
            val back = c.appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException()
            if (!back.contentEquals(bytes)) throw Verify()
            // The file opens and every part of it checks out.
            runCatching { c.backupManager.verifyWithKey(back, key) }.getOrElse { throw Verify() }
            return bytes.size
        } finally {
            key.fill(0)
        }
    }

    /**
     * Writes [bytes] over the file and cuts what a longer earlier backup left after them. "rwt" first, as
     * Google Drive and the phone's own files take it; a place that takes only "wt" or "w" gets that.
     */
    private fun overwrite(uri: Uri, bytes: ByteArray) {
        val resolver = c.appContext.contentResolver
        val fd = MODES.firstNotNullOfOrNull { mode ->
            try {
                resolver.openFileDescriptor(uri, mode)
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: UnsupportedOperationException) {
                null
            }
        } ?: throw IllegalArgumentException("mode")
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            out.write(bytes)
            out.flush()
            // A pipe from a cloud provider has no length to cut; "t" in the mode already emptied it.
            runCatching { out.channel.truncate(bytes.size.toLong()) }
            runCatching { out.fd.sync() }
        }
    }

    private fun record(now: Instant, r: Result) {
        val e = prefs.edit().putLong(K_TRIED, now.toEpochMilli())
        when (r) {
            is Result.Ok -> e.putLong(K_OK, r.at.toEpochMilli()).remove(K_FAILING).remove(K_ERROR).remove(K_NOTICED)
            is Result.Failed -> {
                e.putString(K_ERROR, r.why)
                if (!prefs.contains(K_FAILING)) e.putLong(K_FAILING, now.toEpochMilli())
            }
            else -> Unit
        }
        e.commit()
        _state.value = load()
        when (r) {
            is Result.Ok -> {
                Notifications.cancel(c.appContext, Notifications.BACKUP_ID)
                c.telemetry.event("auto_backup", "ok")
            }
            is Result.Failed -> {
                c.telemetry.event("auto_backup", "fail")
                noticeIfLong(now)
            }
            else -> Unit
        }
    }

    /** [FAIL_DAYS] days with no good backup: one notice a day until it works or is turned off. */
    private fun noticeIfLong(now: Instant) {
        val since = _state.value.failingSince ?: return
        val from = _state.value.lastOk?.let { maxOf(it, since) } ?: since
        if (Duration.between(from, now) < Duration.ofDays(FAIL_DAYS)) return
        val today = now.atZone(zone()).toLocalDate().toString()
        if (prefs.getString(K_NOTICED, null) == today) return
        prefs.edit().putString(K_NOTICED, today).apply()
        post(c.appContext, _state.value.error.orEmpty(), Duration.between(from, now).toDays())
    }

    private fun hold(uri: Uri) {
        runCatching {
            c.appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun release(uri: Uri) {
        runCatching {
            c.appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun nameOf(uri: Uri): String? = runCatching {
        c.appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private object B64 {
        fun encodeToString(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
    }

    companion object {
        /** What the file picker suggests. */
        const val FILE_NAME = "밤낮-자동백업.json"
        const val FAIL_DAYS = 3L
        /** The same work per password as a backup saved by hand. */
        val ITERATIONS = BackupCrypto.ITERATIONS
        val DUE_AFTER: Duration = Duration.ofHours(20)
        val RETRY_AFTER: Duration = Duration.ofHours(3)
        private val MODES = listOf("rwt", "wt", "w")
        private const val PREFS = "auto_backup"
        private const val K_URI = "uri"
        private const val K_NAME = "name"
        private const val K_SALT = "salt"
        private const val K_ITER = "iter"
        private const val K_KEY = "key"
        private const val K_OK = "ok"
        private const val K_TRIED = "tried"
        private const val K_FAILING = "failing"
        private const val K_ERROR = "error"
        private const val K_NOTICED = "noticed"

        private const val OPEN_REQUEST = 4402

        fun post(context: Context, why: String, days: Long) {
            val notification = NotificationCompat.Builder(context, Notifications.BACKUP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentTitle("자동 백업이 ${days}일째 안 되고 있어요")
                .setContentText(why.ifEmpty { "눌러서 확인해 주세요" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(why.ifEmpty { "눌러서 확인해 주세요" }))
                .setContentIntent(Notifications.open(context, NoticeLinks.Target.BACKUP, OPEN_REQUEST))
                .setAutoCancel(true)
                .build()
            Notifications.post(context, Notifications.BACKUP_ID, notification)
        }
    }
}
