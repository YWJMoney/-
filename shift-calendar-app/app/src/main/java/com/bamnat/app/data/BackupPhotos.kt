package com.bamnat.app.data

import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * [BACK-01] Receipt and contract photos inside the backup file (결정 D13), so a new phone keeps them.
 * Only photos a record still points at go in, each as it is on the phone (JPEG, longest side 1600px),
 * up to [MAX_BYTES] in all; a photo already on the phone is never written over by a restore.
 *
 *     "photos": { "receipts": { "<name>.jpg": "<base64>" }, "contracts": { "<calendar id>": { "<name>.jpg": "<base64>" } } }
 */
class BackupPhotos(
    /** files/receipts, shared by every calendar's ledger ([com.bamnat.app.ui.money.ReceiptFiles]). */
    private val receipts: File,
    /** A calendar's contract photo folder ([WorkToolsStore.photoDir]). */
    private val contracts: (String) -> File,
) {
    /** What would go in: how many photos and their size on the phone. */
    data class Size(val count: Int, val bytes: Long) {
        val isEmpty: Boolean get() = count == 0
    }

    private fun safe(name: String) = name.isNotBlank() && !name.contains('/') && !name.contains("..") && !name.contains('\\')

    private fun receiptFiles(names: Collection<String>): List<File> =
        names.filter(::safe).distinct().map { File(receipts, it) }.filter { it.isFile }

    private fun contractFiles(byCalendar: Map<String, Collection<String>>): Map<String, List<File>> =
        byCalendar.mapValues { (id, names) -> names.filter(::safe).distinct().map { File(contracts(id), it) }.filter { it.isFile } }
            .filterValues { it.isNotEmpty() }

    fun size(receiptNames: Collection<String>, contractNames: Map<String, Collection<String>>): Size {
        val files = receiptFiles(receiptNames) + contractFiles(contractNames).values.flatten()
        return Size(files.size, files.sumOf { it.length() })
    }

    /**
     * [REL-15] The photo files a backup of format 3 carries, as [BackupArchive] entries, up to [MAX_BYTES] in all
     * like [export].
     */
    fun files(receiptNames: Collection<String>, contractNames: Map<String, Collection<String>>): List<BackupArchive.Photo> {
        var left = MAX_BYTES
        fun fits(f: File) = (f.length() <= left).also { if (it) left -= f.length() }
        return receiptFiles(receiptNames).filter(::fits).map { BackupArchive.Photo(BackupArchive.RECEIPTS + it.name, it) } +
            contractFiles(contractNames).flatMap { (id, files) ->
                files.filter(::fits).map { BackupArchive.Photo("${BackupArchive.CONTRACTS}$id/${it.name}", it) }
            }
    }

    /**
     * [REL-15] Moves the photos a file of format 3 brought (entry → waiting file) to their folders, each written
     * whole or not at all; one already on the phone stays. How many moved.
     */
    fun restoreFiles(waiting: Map<String, File>): Int {
        var n = 0
        for ((entry, file) in waiting) {
            val (dir, name) = when {
                entry.startsWith(BackupArchive.RECEIPTS) -> receipts to entry.removePrefix(BackupArchive.RECEIPTS)
                entry.startsWith(BackupArchive.CONTRACTS) -> entry.removePrefix(BackupArchive.CONTRACTS).split('/')
                    .takeIf { it.size == 2 && safe(it[0]) }?.let { contracts(it[0]) to it[1] } ?: continue
                else -> continue
            }
            if (!safe(name)) continue
            if (place(dir, name) { target -> file.renameTo(target) || runCatching { file.copyTo(target); true }.getOrDefault(false) }) n++
        }
        return n
    }

    /** Writes a photo beside its place first, then gives it its name, so a cut never leaves half a photo. */
    private fun place(dir: File, name: String, write: (File) -> Boolean): Boolean {
        val target = File(dir, name)
        if (target.exists()) return false
        return runCatching {
            dir.mkdirs()
            val part = File(dir, "$name.part").apply { delete() }
            write(part) && part.renameTo(target).also { if (!it) part.delete() }
        }.getOrDefault(false)
    }

    /** The photos as a backup of format 2 holds them; null when there are none. */
    fun export(receiptNames: Collection<String>, contractNames: Map<String, Collection<String>>): JSONObject? {
        var left = MAX_BYTES
        fun put(into: JSONObject, f: File) {
            if (f.length() > left) return
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return
            left -= bytes.size
            into.put(f.name, Base64.getEncoder().encodeToString(bytes))
        }
        val r = JSONObject().apply { receiptFiles(receiptNames).forEach { put(this, it) } }
        val c = JSONObject().apply {
            contractFiles(contractNames).forEach { (id, files) ->
                val o = JSONObject().apply { files.forEach { put(this, it) } }
                if (o.length() > 0) put(id, o)
            }
        }
        if (r.length() == 0 && c.length() == 0) return null
        return JSONObject().apply {
            if (r.length() > 0) put("receipts", r)
            if (c.length() > 0) put("contracts", c)
        }
    }

    /** Writes the backup's photos that are not on the phone yet; how many were written. */
    fun restore(o: JSONObject?): Int {
        if (o == null) return 0
        var n = 0
        fun write(dir: File, names: JSONObject) {
            for (name in names.keys()) {
                if (!safe(name)) continue
                if (File(dir, name).exists()) continue
                val bytes = runCatching { Base64.getDecoder().decode(names.optString(name)) }.getOrNull() ?: continue
                if (place(dir, name) { it.writeBytes(bytes); true }) n++
            }
        }
        o.optJSONObject("receipts")?.let { write(receipts, it) }
        o.optJSONObject("contracts")?.let { c -> for (id in c.keys()) if (safe(id)) c.optJSONObject(id)?.let { write(contracts(id), it) } }
        return n
    }

    companion object {
        /** About 30 MB of photos (40 MB in the file); photos past it stay on the phone only. */
        const val MAX_BYTES = 30L * 1024 * 1024

        /** How many photos a backup file holds and about how big they are, for the summary before a restore. */
        fun count(o: JSONObject?): Size {
            if (o == null) return Size(0, 0)
            var count = 0
            var bytes = 0L
            fun add(names: JSONObject) { for (k in names.keys()) { count++; bytes += names.optString(k).length * 3L / 4 } }
            o.optJSONObject("receipts")?.let(::add)
            o.optJSONObject("contracts")?.let { c -> for (id in c.keys()) c.optJSONObject(id)?.let(::add) }
            return Size(count, bytes)
        }
    }
}
