package com.bamnat.app.alarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.bamnat.core.alarm.ImageHash
import com.bamnat.core.alarm.Missions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [ALRM-04] 증명 사진's reference photos, kept only as fingerprints: up to [ImageHash.MAX_REFERENCES] of
 * them (day and night light), each with when it was taken and the place's name the user typed.
 *
 * Its own preferences file, which the backup rules leave out (they take settings.xml only) and 밤낮's backup
 * file never reads: a fingerprint belongs to this phone's camera and this home, so a new phone takes it again.
 */
class PhotoReferences(context: Context) {
    data class Reference(val hash: Long, val takenAt: Long, val place: String)

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Reference>> = _all.asStateFlow()
    val hashes: List<Long> get() = _all.value.map { it.hash }

    /** Adds one; past [ImageHash.MAX_REFERENCES] the oldest goes. [replace] starts over with this one alone. */
    fun add(hash: Long, place: String, at: Long = System.currentTimeMillis(), replace: Boolean = false) {
        val name = place.trim().take(PLACE_MAX).ifEmpty { _all.value.lastOrNull()?.place.orEmpty() }
        val kept = if (replace) emptyList() else _all.value
        write((kept + Reference(hash, at, name)).takeLast(ImageHash.MAX_REFERENCES))
    }

    fun remove(index: Int) = write(_all.value.filterIndexed { i, _ -> i != index })

    fun clear() = write(emptyList())

    private fun write(list: List<Reference>) {
        val a = JSONArray()
        for (r in list) a.put(JSONObject().put("hash", java.lang.Long.toHexString(r.hash)).put("at", r.takenAt).put("place", r.place))
        prefs.edit().putString(KEY, a.toString()).apply()
        _all.value = list
    }

    private fun read(): List<Reference> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reference(java.lang.Long.parseUnsignedLong(o.getString("hash"), 16), o.optLong("at"), o.optString("place"))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val FILE = "photo_mission"
        private const val KEY = "references"
        const val PLACE_MAX = 20

        /** What the alarm compares with; nothing when the phone has not been unlocked since it started. */
        fun hashesOrEmpty(context: Context): List<Long> = runCatching { PhotoReferences(context).hashes }.getOrDefault(emptyList())
    }
}

/** [ALRM-04] A photo from the camera app turned into its fingerprint, the photo itself deleted at once. */
object PhotoPrint {
    /** The camera app writes here, through the `scan` FileProvider path that 명세서 사진 uses too. */
    fun file(context: Context): File = File(context.cacheDir, "scan").apply { mkdirs() }.resolve("mission.jpg")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** The fingerprint of the photo at [uri], or null if it cannot be read. Deletes [file] in every case. */
    suspend fun take(context: Context, uri: Uri): Long? = withContext(Dispatchers.Default) {
        try {
            runCatching { grey(context, uri)?.let { ImageHash.of(it) } }.getOrNull()
        } finally {
            clear(context)
        }
    }

    /** The photo shrunk to 32×32 grey, turned the way it was held (EXIF), or null. */
    private fun grey(context: Context, uri: Uri): IntArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Only the size is read here; decodeStream itself gives null for that.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = resolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val upright = if (degrees == 0f) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        return grey(upright)
    }

    /** [bitmap] shrunk to [ImageHash.SIZE]² grey values. */
    fun grey(bitmap: Bitmap): IntArray {
        val n = ImageHash.SIZE
        val small = Bitmap.createScaledBitmap(bitmap, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        return IntArray(n * n) { ImageHash.grey(px[it]) }
    }

    private const val DECODE_SIDE = 256
}

/**
 * [ALRM-04] The alarm's photo mission, apart from the screen: each photo is the place or not, and after
 * [Missions.PHOTO_TRIES] misses, or when the camera app fails, it is sums from then on.
 */
class PhotoAttempts(private val references: List<Long>, wrong: Int = 0, failed: Boolean = false) {
    sealed interface Outcome {
        data object Done : Outcome
        data class Again(val left: Int) : Outcome
        data object Sums : Outcome
    }

    var wrong: Int = wrong
        private set
    var failed: Boolean = failed
        private set

    /** A photo's fingerprint, or null when the camera app came back without one. */
    fun taken(hash: Long?): Outcome {
        if (hash == null) {
            failed = true
            return Outcome.Sums
        }
        if (ImageHash.samePlace(hash, references)) return Outcome.Done
        wrong++
        return if (wrong >= Missions.PHOTO_TRIES) Outcome.Sums else Outcome.Again(Missions.PHOTO_TRIES - wrong)
    }

    fun cameraFailed() {
        failed = true
    }
}
