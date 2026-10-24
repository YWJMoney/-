package com.bamnat.app.ui.components

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * [INPUT-04] "복사한 글이 있어요 · 붙여 넣기", one line over a field that takes pasted words (명세서, 결제 문자, 받은
 * 링크, 모임 코드, 복구 코드; 계획/1.24-Q-개발계획.md 351, research/1.24-리뷰-직접입력.md Q6·Q7·Q10·Q11).
 *
 * Decision Q-D1: while the screen shows, only the clip's description is looked at (that it is text, and when it was
 * copied), which Android does not announce. The words are read when the user taps 붙여 넣기 (Android 12+ then says
 * so, as it should), checked with [fits], and handed to [onPaste]; a clip of another shape gets one line saying so.
 * "괜찮아요" or a clip of another shape is remembered by its copy time, so that copy is not offered again. Nothing
 * of the clip is kept: the remembered value is a time, on this phone only, and is left out of backups on purpose.
 */
@Composable
fun ClipboardHint(what: String, fits: (String) -> Boolean, onPaste: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val memory = remember { ClipHintMemory(context) }
    // The copy offered now, by its time; null when there is nothing new to offer.
    var offered by remember { mutableStateOf(memory.newClip()) }
    var notFit by remember { mutableStateOf(false) }
    // The clipboard can change while the app is away; look again whenever the screen comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { offered = memory.newClip() }
    val stamp = offered
    if (stamp == null && !notFit) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (notFit) "복사한 글이 $what 같지 않아요" else "복사한 글이 있어요",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (!notFit && stamp != null) TextButton(onClick = {
                val text = memory.read()
                memory.forget(stamp)
                offered = null
                if (text != null && fits(text)) onPaste(text) else notFit = true
            }) { Text("붙여 넣기") }
            IconButton(onClick = {
                stamp?.let(memory::forget)
                offered = null
                notFit = false
            }) { Icon(Icons.Filled.Close, contentDescription = if (notFit) "닫기" else "괜찮아요") }
        }
    }
}

/** [INPUT-04] The one copy time the hint has dealt with, in the app's own preferences on this phone. */
internal class ClipHintMemory(private val context: Context) {
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The time of a text clip not offered before, or null. Reads the description only, never the words. */
    fun newClip(): Long? {
        val d = runCatching { clipboard?.primaryClipDescription }.getOrNull() ?: return null
        if (!d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return null
        // A clip with no time (some keyboards) is still offered once, under 0.
        val stamp = d.timestamp
        return stamp.takeIf { it != prefs.getLong(KEY_DONE, Long.MIN_VALUE) }
    }

    fun read(): String? = runCatching {
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun forget(stamp: Long) = prefs.edit().putLong(KEY_DONE, stamp).apply()

    companion object {
        const val PREFS = "clip_hint"
        private const val KEY_DONE = "done_stamp"
    }
}
