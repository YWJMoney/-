package com.bamnat.app.pay

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PERF-04] 결정 D52: ML Kit's Korean text model is not inside the app any more. Google Play services
 * downloads it to the phone once (asked at install by the manifest's com.google.mlkit.vision.DEPENDENCIES,
 * and again here when it is still missing), and the photo is read on the phone as before: nothing is sent.
 * Until the model is there a read ends in [NotReady], whose message says to try again in a moment.
 */
object OcrModel {
    class NotReady : Exception("글자 인식 준비물을 휴대폰에 받는 중이에요. 잠시 뒤(와이파이에서 1~2분) 다시 해 주세요.")

    /** The words of [image], read on the phone. */
    suspend fun read(context: Context, image: InputImage): Text {
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            if (!available(context, recognizer)) throw NotReady()
            return try {
                await(recognizer.process(image))
            } catch (e: MlKitException) {
                if (e.errorCode == MlKitException.UNAVAILABLE) {
                    install(context, recognizer)
                    throw NotReady()
                }
                throw e
            }
        } finally {
            recognizer.close()
        }
    }

    /** True when Play services has the model; when not, asks for it and says no. A phone without Play services goes on to try. */
    private suspend fun available(context: Context, recognizer: TextRecognizer): Boolean {
        val client = ModuleInstall.getClient(context)
        val there = runCatching { await(client.areModulesAvailable(recognizer)).areModulesAvailable() }.getOrDefault(true)
        if (!there) install(context, recognizer)
        return there
    }

    private fun install(context: Context, recognizer: TextRecognizer) {
        runCatching { ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()) }
    }

    private suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
    }
}
