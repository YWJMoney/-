package com.bamnat.app.pay

import android.content.Context
import android.net.Uri
import com.bamnat.core.pay.OcrLine
import com.bamnat.core.pay.OcrRows
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PAYS-05][PAYX-04] Reads the text of a payslip or a page of rules from a photo, on the phone: ML Kit's Korean
 * model ships inside the app, so the photo and its text never leave the device. Lines are put back on their rows
 * ([OcrRows]) so a table reads name by amount.
 */
object PayslipScanner {
    /** Photos taken for reading go here and are deleted once read (FileProvider path "scan"). */
    fun scanFile(context: Context): File = File(context.cacheDir, "scan").apply { mkdirs() }.resolve("payslip.jpg")

    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                            val box = l.boundingBox ?: return@mapNotNull null
                            OcrLine(l.text, box.left, box.top, box.right, box.bottom)
                        }
                        cont.resume(if (lines.isEmpty()) text.text else OcrRows.join(lines))
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    /** Deletes a photo taken for reading. */
    fun clear(context: Context) {
        runCatching { scanFile(context).delete() }
    }
}
