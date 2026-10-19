package com.bamnat.app.ui.money

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LEDG-12] The ledger as a CSV file, made on the phone and handed to the share sheet or saved where
 * the user picks. Nothing goes anywhere else; the shared copy sits in the app's cache and is
 * replaced by the next one.
 */
object LedgerExport {
    const val MIME = "text/csv"

    suspend fun share(context: Context, csv: String, name: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share/ledger").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, name).apply { writeText(csv, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".csv"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "가계부 내보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes [csv] to a file the user picked with the system's save screen; false when it could not. */
    suspend fun save(context: Context, uri: Uri, csv: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    }
}
