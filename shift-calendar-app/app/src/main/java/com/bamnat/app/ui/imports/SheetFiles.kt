package com.bamnat.app.ui.imports

import android.content.Context
import android.net.Uri
import com.bamnat.core.imports.Sheets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [HRLY-02][IMPORT-03] A spreadsheet the user picked, read on the phone and never kept. */
internal object SheetFiles {
    /** The types the file picker offers: .xlsx, CSV, and what some apps label them as. */
    val TYPES = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values",
        "text/plain", "application/vnd.ms-excel", "application/octet-stream",
    )

    /** The file's name and rows; throws [Sheets.SheetException] for one too big or unreadable. */
    suspend fun read(context: Context, uri: Uri): Pair<String, List<List<String>>> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > Sheets.MAX_BYTES) throw Sheets.SheetException("파일이 너무 커요 (8MB까지)")
            }
            out.toByteArray()
        } ?: throw Sheets.SheetException("파일을 열지 못했어요")
        name to Sheets.read(bytes)
    }
}
