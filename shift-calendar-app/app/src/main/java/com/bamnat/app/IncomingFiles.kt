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
