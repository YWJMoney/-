package com.bamnat.app.imports

import android.content.Context
import android.net.Uri
import com.bamnat.core.imports.RosterWord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SHFT-06] Reads the words of a duty roster photo on the phone with ML Kit's Korean model, which ships
 * inside the app: the photo and its text never leave the device. Each word keeps its box so a code can be
 * put under the day it sits beneath ([com.bamnat.core.imports.DutyRoster.read]).
 */
object RosterScanner {
    suspend fun read(context: Context, uri: Uri): List<RosterWord> = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        // Words (elements), not lines: a table's cells close together come back as one line.
                        val words = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
                            val box = e.boundingBox ?: return@mapNotNull null
                            RosterWord(e.text, box.left, box.top, box.right, box.bottom)
                        }
                        cont.resume(words)
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }
}
