package com.bamnat.app.ui.calendar

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.BuildConfig
import com.bamnat.app.appContainer
import com.bamnat.core.ops.AppNotice
import com.bamnat.core.ops.AppNotices

/**
 * [SRV-04] Which notices the user closed. On this phone only and not in the backup: a notice is about
 * this app, now, and a new phone may as well see it again.
 */
class ClosedNotices(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_notices", Context.MODE_PRIVATE)
    private val _closed = kotlinx.coroutines.flow.MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val closed: kotlinx.coroutines.flow.StateFlow<Set<String>> = _closed

    fun close(id: String, all: List<AppNotice>) {
        val next = AppNotices.keepDismissed(_closed.value + id, all) + id
        _closed.value = next
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private companion object {
        const val KEY = "closed"
    }
}

/** [SRV-04] The developer's notice as one line over the calendar; a tap opens it, ✕ closes it for good. */
@Composable
fun AppNoticeLine(modifier: Modifier = Modifier) {
    val c = LocalContext.current.appContainer
    val config by c.remoteConfig.config.collectAsStateWithLifecycle()
    val closed by c.closedNotices.closed.collectAsStateWithLifecycle()
    val notice = remember(config.notices, closed) {
        AppNotices.showing(config.notices, System.currentTimeMillis(), BuildConfig.VERSION_CODE, closed)
    } ?: return
    var open by remember(notice.id) { mutableStateOf(false) }
    val tone = if (notice.important) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onTone = if (notice.important) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = tone, contentColor = onTone, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.clickable { open = true }.heightIn(min = 44.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Campaign, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(notice.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { c.closedNotices.close(notice.id, config.notices) }) { Icon(Icons.Filled.Close, "공지 닫기", Modifier.size(18.dp)) }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(notice.title) },
            text = { Text(notice.body) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { open = false; c.closedNotices.close(notice.id, config.notices) }) { Text("다시 보지 않기") } },
        )
    }
}
