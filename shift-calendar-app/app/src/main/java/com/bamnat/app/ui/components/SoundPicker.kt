package com.bamnat.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.produceState
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.bamnat.app.data.AppSettings

object SoundNames {
    fun describe(context: Context, sound: String?, fallbackLabel: String): String {
        if (sound == null) return fallbackLabel
        if (sound == AppSettings.SILENT_SOUND) return "무음 (진동만)"
        val uri = runCatching { Uri.parse(sound) }.getOrNull() ?: return "알 수 없는 소리"
        displayName(context, uri)?.let { return it }
        return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
            ?: "사용자 지정 소리"
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.authority == Settings.AUTHORITY || uri.authority == "media") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
            }
        }.getOrNull()
    }
}

/**
 * Picks an alarm sound: any system alarm, ringtone or notification sound, or an audio file.
 * [onPicked] gets null for "default", [AppSettings.SILENT_SOUND] for silent, else a URI string.
 */
@Composable
fun SoundPickerRow(
    sound: String?,
    defaultLabel: String,
    onPicked: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowDefault: Boolean = true,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onPicked(uri.toString())
        }
    }

    val soundLabel = remember(sound, defaultLabel) { SoundNames.describe(context, sound, defaultLabel) }
    var previewing by remember { mutableStateOf<Ringtone?>(null) }
    DisposableEffect(Unit) {
        onDispose { previewing?.stop() }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("알람음", style = MaterialTheme.typography.bodyLarge)
                Text(
                    soundLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    val current = previewing
                    if (current != null) {
                        current.stop()
                        previewing = null
                    } else {
                        previewing = playPreview(context, sound)
                    }
                },
                enabled = sound != AppSettings.SILENT_SOUND,
            ) {
                Icon(
                    if (previewing != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (previewing != null) "미리듣기 정지" else "미리듣기",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                previewing?.stop()
                previewing = null
                choosing = true
            }) {
                Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                Text("  기본 소리")
            }
            OutlinedButton(onClick = {
                previewing?.stop()
                previewing = null
                runCatching { filePicker.launch(arrayOf("audio/*")) }
            }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Text("  내 음악 파일")
            }
        }
    }

    if (choosing) {
        SoundListDialog(
            current = sound,
            allowDefault = allowDefault,
            onDismiss = { choosing = false },
            onDone = {
                choosing = false
                onPicked(it)
            },
        )
    }
}

/** One sound offered in the list. [value] is what gets stored (see [SoundPickerRow]). */
private data class SoundChoice(val title: String, val value: String?)

private fun loadSounds(context: Context, allowDefault: Boolean): List<SoundChoice> {
    val list = mutableListOf<SoundChoice>()
    if (allowDefault) list += SoundChoice("휴대폰 기본 알람음", null)
    list += SoundChoice("무음 (진동만)", AppSettings.SILENT_SOUND)
    val seen = HashSet<String>()
    for (type in listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)) {
        runCatching {
            val manager = RingtoneManager(context).apply { setType(type) }
            val cursor = manager.cursor
            while (cursor.moveToNext()) {
                val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
                if (seen.add(uri)) list += SoundChoice(cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "소리", uri)
            }
        }
    }
    return list
}

/**
 * The app's own sound list: tap to hear a sound, then 완료 to keep it. Some phones' system pickers
 * return nothing when closed with their own buttons, so the choice is made here instead.
 */
@Composable
private fun SoundListDialog(
    current: String?,
    allowDefault: Boolean,
    onDismiss: () -> Unit,
    onDone: (String?) -> Unit,
) {
    val context = LocalContext.current
    val sounds by produceState<List<SoundChoice>?>(null) {
        value = withContext(Dispatchers.IO) { loadSounds(context, allowDefault) }
    }
    var chosen by remember { mutableStateOf(current) }
    var playing by remember { mutableStateOf<Ringtone?>(null) }
    DisposableEffect(Unit) { onDispose { playing?.stop() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("알람음 선택") },
        text = {
            val list = sounds
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.value ?: "default" }) { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    chosen = s.value
                                    playing?.stop()
                                    playing = playPreview(context, s.value)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == s.value, onClick = null)
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                playing?.stop()
                onDone(chosen)
            }) { Text("완료") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun playPreview(context: Context, sound: String?): Ringtone? {
    val uri = when (sound) {
        null -> RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        AppSettings.SILENT_SOUND -> return null
        else -> runCatching { Uri.parse(sound) }.getOrNull() ?: return null
    }
    return runCatching {
        RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
    }.getOrNull()
}
