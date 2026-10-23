package com.bamnat.app.imports

import android.content.Context
import android.net.Uri
import com.bamnat.app.pay.OcrModel
import com.bamnat.core.imports.RosterWord
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SHFT-06] Reads the words of a duty roster photo on the phone with ML Kit's Korean model, which Google
 * Play services keeps on the phone ([OcrModel]): the photo and its text never leave the device. Each word keeps
 * its box so a code can be put under the day it sits beneath ([com.bamnat.core.imports.DutyRoster.read]).
 */
object RosterScanner {
    suspend fun read(context: Context, uri: Uri): List<RosterWord> = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        // Words (elements), not lines: a table's cells close together come back as one line.
        OcrModel.read(context, image).textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
            val box = e.boundingBox ?: return@mapNotNull null
            RosterWord(e.text, box.left, box.top, box.right, box.bottom)
        }
    }
}
