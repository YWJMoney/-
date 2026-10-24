package com.bamnat.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [CAL-47] G P24: a 밤낮 file opened from a messenger or a file app (회사 근무 파일, 백업). The app is chosen by the
 * file's type (application/json, or a name ending .bamnat), so [read] only keeps the file; what it is decided by
 * reading it, as 파일로 시작하기 and 설정 > 데이터 > 파일 불러오기 do. Before the first setup it opens there, after it
 * in 설정 > 데이터; nothing is changed until the user agrees on the dialog that follows.
 */
object IncomingFiles {
    private val _file = MutableStateFlow<Uri?>(null)
    val file: StateFlow<Uri?> = _file.asStateFlow()

    /** Keeps the file [intent] brings, if any; true when it brought one. */
    fun read(intent: Intent?): Boolean {
        val uri = uriOf(intent) ?: return false
        _file.value = uri
        return true
    }

    /** The waiting file, once: the screen that opens it takes it. */
    fun take(): Uri? = _file.value.also { _file.value = null }

    internal fun uriOf(intent: Intent?): Uri? {
        intent ?: return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            Intent.ACTION_SEND -> stream(intent)
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}

/**
 * [ROST-09] A roster shared to 밤낮 from the gallery or a messenger (리뷰 N2, 결정 D65): a photo (image/…) or text
 * (text/plain). 한 달 근무 넣기 opens with it and reads it on the phone; nothing is saved until the user puts the days
 * in. Kept apart from [IncomingFiles], so a shared photo never opens the backup dialog.
 */
object IncomingRoster {
    sealed interface Shared {
        data class Photo(val uri: Uri) : Shared
        data class Text(val text: String) : Shared
    }

    private val _shared = MutableStateFlow<Shared?>(null)
    val shared: StateFlow<Shared?> = _shared.asStateFlow()

    /** Keeps the roster [intent] shares, if any; true when it shared one. */
    fun read(intent: Intent?): Boolean {
        val s = sharedOf(intent) ?: return false
        _shared.value = s
        return true
    }

    /** The waiting roster, once. */
    fun take(): Shared? = _shared.value.also { _shared.value = null }

    internal fun sharedOf(intent: Intent?): Shared? {
        intent ?: return null
        if (intent.action != Intent.ACTION_SEND || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val type = intent.type.orEmpty()
        return when {
            type.startsWith("image/") -> IncomingFiles.uriOf(intent)?.let { Shared.Photo(it) }
            type.startsWith("text/plain") -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.take(MAX_TEXT)?.let { Shared.Text(it) }
            else -> null
        }
    }

    /** A month's roster is a few kilobytes; a long message is cut, never kept whole. */
    private const val MAX_TEXT = 20_000
}
