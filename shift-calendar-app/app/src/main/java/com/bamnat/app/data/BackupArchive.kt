package com.bamnat.app.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [REL-15] 327 (검토 B1·F2): the backup file of format 3, a zip.
 *
 *     backup.json                          the backup as before, without the photos
 *     photos/receipts/<name>.jpg           each receipt photo as it is on the phone
 *     photos/contracts/<calendar>/<name>   each contract photo
 *     sha256.txt                           "<sha256>  <entry>" for every entry above, last
 *
 * The photos go in and come out as bytes, never as Base64 text inside the JSON (30 MB of photos took more than
 * 100 MB of memory that way). A file whose sums do not match is turned away before anything changes. Files of
 * format 2 (one JSON text) still open as before.
 */
object BackupArchive {
    const val JSON = "backup.json"
    const val SUMS = "sha256.txt"
    const val RECEIPTS = "photos/receipts/"
    const val CONTRACTS = "photos/contracts/"

    /** A JSON larger than this is not a backup (the biggest seen is a few MB). */
    private const val MAX_JSON = 64L * 1024 * 1024
    /** Photos past twice the backup's own limit are not ours either. */
    private const val MAX_PHOTOS = 2 * BackupPhotos.MAX_BYTES

    private val ZIP_HEAD = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun isZip(head: ByteArray) = head.size >= 4 && head.copyOf(4).contentEquals(ZIP_HEAD)

    /** One photo going in: where it sits in the zip and the file on the phone. */
    class Photo(val entry: String, val file: File)

    /** Writes the zip to [out] and finishes it; [out] stays open. */
    fun write(out: OutputStream, json: String, photos: List<Photo>) {
        val zip = ZipOutputStream(NotClosing(out))
        val sums = StringBuilder()
        fun entry(name: String, level: Int, body: (OutputStream) -> Unit) {
            zip.setLevel(level)
            zip.putNextEntry(ZipEntry(name))
            val md = MessageDigest.getInstance("SHA-256")
            body(object : OutputStream() {
                override fun write(b: Int) { md.update(b.toByte()); zip.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); zip.write(b, off, len) }
            })
            zip.closeEntry()
            sums.append(hex(md.digest())).append("  ").append(name).append('\n')
        }
        entry(JSON, Deflater.DEFAULT_COMPRESSION) { it.write(json.toByteArray()) }
        // JPEGs do not get smaller; stored as they are, they cost no time.
        for (p in photos) runCatching {
            p.file.inputStream().use { input -> entry(p.entry, Deflater.NO_COMPRESSION) { input.copyTo(it) } }
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(SUMS))
        zip.write(sums.toString().toByteArray())
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    /** What a zip held: the backup's JSON and its photos, waiting in [staging] until a restore moves them. */
    class Opened(val json: String, val photos: Map<String, File>)

    /**
     * Reads a zip [write] made. Photos land in [staging] under numbers, never under the zip's own paths.
     * Throws [BackupException] for a file that is cut, changed or not ours.
     */
    fun read(input: InputStream, staging: File): Opened {
        staging.deleteRecursively()
        staging.mkdirs()
        val zip = ZipInputStream(input)
        val got = HashMap<String, String>()
        var json: String? = null
        var sums: String? = null
        val photos = LinkedHashMap<String, File>()
        var photoBytes = 0L
        try {
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (e.isDirectory) continue
                when {
                    name == SUMS -> sums = zip.readBytes().decodeToString()
                    name == JSON -> {
                        val md = MessageDigest.getInstance("SHA-256")
                        val bytes = limited(zip, MAX_JSON, md)
                        got[name] = hex(md.digest())
                        json = bytes.decodeToString()
                    }
                    isPhoto(name) -> {
                        val md = MessageDigest.getInstance("SHA-256")
                        val target = File(staging, "${photos.size}.part")
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                photoBytes += n
                                if (photoBytes > MAX_PHOTOS) throw BackupException("백업 파일을 읽을 수 없어요")
                                md.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                        got[name] = hex(md.digest())
                        photos[name] = target
                    }
                    // Something a later version adds: left alone.
                    else -> Unit
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("백업 파일이 손상됐어요")
        }
        val expected = sums?.lines()?.filter { it.isNotBlank() }?.associate { line ->
            line.substringAfter("  ") to line.substringBefore("  ")
        } ?: throw BackupException("백업 파일이 손상됐어요")
        if (json == null || got.any { (name, sum) -> expected[name] != sum } || expected.keys.any { it !in got && (it == JSON || isPhoto(it)) }) {
            staging.deleteRecursively()
            throw BackupException("백업 파일이 손상됐어요")
        }
        return Opened(json!!, photos)
    }

    /** "photos/receipts/a.jpg" or "photos/contracts/<id>/a.jpg", with names a folder can hold. */
    fun isPhoto(name: String): Boolean = when {
        name.startsWith(RECEIPTS) -> safe(name.removePrefix(RECEIPTS))
        name.startsWith(CONTRACTS) -> name.removePrefix(CONTRACTS).split('/').let { it.size == 2 && it.all(::safe) }
        else -> false
    }

    fun safe(name: String) = name.isNotBlank() && !name.contains('/') && !name.contains("..") && !name.contains('\\')

    private fun limited(input: InputStream, max: Long, md: MessageDigest): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw BackupException("백업 파일을 읽을 수 없어요")
            md.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Lets a zip or a cipher finish without closing the file under it. */
    class NotClosing(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }
}
