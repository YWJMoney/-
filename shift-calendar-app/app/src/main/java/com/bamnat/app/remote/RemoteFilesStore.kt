package com.bamnat.app.remote

import com.bamnat.core.money.TradeWages
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.WithholdingTable
import com.bamnat.core.remote.PresetBook
import com.bamnat.core.remote.RemoteFile
import com.bamnat.core.remote.RemoteFileException
import com.bamnat.core.remote.RemoteFiles
import com.bamnat.core.remote.RemoteKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/** [REMOTE-02] Reads one published file; null when it is not there or the phone is offline. */
fun interface RemoteFetcher {
    suspend fun get(path: String): ByteArray?
}

/** [REMOTE-02] GitHub Pages (`bamnat.remoteBaseUrl`): plain GET, short timeouts, at most 1 MB. */
class HttpRemoteFetcher(private val baseUrl: String) : RemoteFetcher {
    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        try {
            val conn = URL(baseUrl.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8_192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return@withContext null
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val MAX_BYTES = 1_000_000
    }
}

/**
 * [REMOTE-02] The signed remote files (결정 D3, `remote/v1/` on GitHub Pages): asks once a day, keeps a file
 * only when its Ed25519 signature checks out, it is the kind asked for, this app can read it and it is newer
 * than what the phone has; then lays it over the built-in data ([apply]). Saved copies are checked again when
 * the app starts, so a phone offline for months keeps the last good files, and with nothing saved it uses
 * the files the app shipped with. Nothing about the phone or the user is sent: it is a plain download.
 */
class RemoteFilesStore(
    private val dir: File,
    private val fetcher: RemoteFetcher,
    private val appVersion: Int,
    private val keys: List<String> = RemoteKeys.TRUSTED,
) {
    private val stamp = File(dir, "fetched_at")

    /** The version in use per kind (the shipped file's until a newer one is saved). */
    fun version(kind: String): Long = saved(kind)?.version ?: RemoteFiles.bundled(kind)?.version ?: 0L

    private fun saved(kind: String): RemoteFile? = runCatching {
        val json = File(dir, "$kind.json")
        val sig = File(dir, "$kind.json.sig")
        if (!json.exists() || !sig.exists()) return null
        RemoteFiles.open(json.readBytes(), sig.readText(), kind, appVersion, keys)
    }.getOrNull()

    /** Lays every saved file over the built-in data; call once when the app starts. */
    fun applySaved() {
        for (kind in RemoteFiles.KINDS) saved(kind)?.let { runCatching { apply(it) } }
    }

    fun due(now: Instant): Boolean {
        val at = runCatching { Instant.parse(stamp.readText().trim()) }.getOrNull() ?: return true
        return now.isBefore(at) || Duration.between(at, now) >= EVERY
    }

    /** Asks for each file when due; returns the kinds that changed. A bad or missing file changes nothing. */
    suspend fun refresh(now: Instant = Instant.now(), force: Boolean = false): List<String> {
        if (!force && !due(now)) return emptyList()
        val changed = mutableListOf<String>()
        var reached = false
        for (kind in RemoteFiles.KINDS) {
            val bytes = fetcher.get(RemoteFiles.path(kind)) ?: continue
            reached = true
            val sig = fetcher.get(RemoteFiles.path(kind) + ".sig")?.toString(Charsets.US_ASCII) ?: continue
            val file = try {
                RemoteFiles.open(bytes, sig, kind, appVersion, keys)
            } catch (e: RemoteFileException) {
                continue
            }
            if (file.version <= version(kind)) continue
            try {
                apply(file)
            } catch (e: RemoteFileException) {
                continue
            }
            dir.mkdirs()
            write(File(dir, "$kind.json.sig"), sig.toByteArray(Charsets.US_ASCII))
            write(File(dir, "$kind.json"), bytes)
            changed += kind
        }
        if (reached) runCatching { dir.mkdirs(); stamp.writeText(now.toString()) }
        return changed
    }

    private fun write(f: File, b: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        val EVERY: Duration = Duration.ofDays(1)

        /** Hands a checked file to the part of core that uses it. */
        fun apply(f: RemoteFile) {
            when (f.kind) {
                RemoteFiles.PRESETS -> PresetBook.use(f)
                RemoteFiles.RATES -> RateTable.use(f)
                RemoteFiles.WAGES -> TradeWages.use(f)
                RemoteFiles.TAXTABLE -> WithholdingTable.use(f)
                RemoteFiles.PUBLICPAY -> com.bamnat.core.pay.PublicPayTable.use(f)
                RemoteFiles.HOLIDAYS -> com.bamnat.core.holiday.KoreanHolidays.use(f)
                RemoteFiles.TAXES -> com.bamnat.core.invest.InvestTaxes.use(f)
                RemoteFiles.ALARM_GUIDE -> com.bamnat.core.remote.AlarmGuides.use(f)
            }
        }
    }
}
