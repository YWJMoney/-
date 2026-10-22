package com.bamnat.app.company

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * [SRV-06] A photo or screenshot for 문의하기, made ready on the phone: at most 1600px on the long
 * side and drawn again as a JPEG, so nothing of the original file goes along (no Exif: where or when
 * it was taken, the phone model). The server checks the same again and takes at most two of 700KB.
 */
object InquiryPhotos {
    const val MAX = 2
    const val LONG_SIDE = 1600
    const val MAX_BYTES = 700 * 1024

    fun prepare(context: Context, uri: Uri): ByteArray? = runCatching {
        val bitmap = decode(context, uri) ?: return null
        var quality = 80
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().use { s -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, s); s.toByteArray() }
            quality -= 15
        } while (out.size > MAX_BYTES && quality >= 35)
        bitmap.recycle()
        out.takeIf { it.size <= MAX_BYTES }
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1.0, LONG_SIDE.toDouble() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val scale = minOf(1.0, LONG_SIDE.toDouble() / maxOf(raw.width, raw.height))
        if (scale >= 1.0) return raw
        return Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true).also { if (it !== raw) raw.recycle() }
    }

    /** What the asker is told when the photos did not go with the question. */
    fun skipped(code: String?): String? = when (code) {
        "paused" -> "사진 받기를 잠시 쉬고 있어서 글만 보냈어요."
        "full" -> "지금은 사진을 더 받을 수 없어서 글만 보냈어요."
        else -> null
    }
}
