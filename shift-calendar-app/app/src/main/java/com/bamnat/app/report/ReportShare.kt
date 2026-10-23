package com.bamnat.app.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bamnat.core.report.ReportDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [PROV-01][PROV-05] A report made on the phone and handed to the share sheet, where it can be saved to
 * Drive or Files, printed or sent. Nothing goes anywhere else; the copy sits in the app's cache and is
 * replaced by the next report.
 */
object ReportShare {
    const val PDF = "application/pdf"
    const val CSV = "text/csv"

    suspend fun pdf(context: Context, doc: ReportDoc, name: String) =
        share(context, name, PDF, doc.title) { file -> file.outputStream().use { ReportPdf.write(doc, it) } }

    suspend fun csv(context: Context, csv: String, name: String, subject: String) =
        share(context, name, CSV, subject) { file -> file.writeText(csv, Charsets.UTF_8) }

    const val ZIP = "application/zip"

    /** [PAYX-45] A bundle of files a person reads, zipped. */
    suspend fun zip(context: Context, bytes: ByteArray, name: String, subject: String) =
        share(context, name, ZIP, subject) { file -> file.writeBytes(bytes) }

    /** [doc] as PDF bytes, for a bundle. */
    fun pdfBytes(doc: ReportDoc): ByteArray = java.io.ByteArrayOutputStream().also { ReportPdf.write(doc, it) }.toByteArray()

    /** The file for [name] in the app's cache, written by [write]; older reports are removed. */
    suspend fun file(context: Context, name: String, write: (File) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share/report").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, name).also(write)
    }

    private suspend fun share(context: Context, name: String, mime: String, subject: String, write: (File) -> Unit) {
        val file = file(context, name, write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
