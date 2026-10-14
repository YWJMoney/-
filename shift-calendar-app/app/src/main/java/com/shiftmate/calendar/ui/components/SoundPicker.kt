package com.shiftmate.calendar.ui.components

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
import com.shiftmate.calendar.data.AppSettings

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
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val picked = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        onPicked(
            when {
                picked == null -> AppSettings.SILENT_SOUND
                allowDefault && picked == Settings.System.DEFAULT_ALARM_ALERT_URI -> null
                else -> picked.toString()
            },
        )
    }
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
                val existing = when (sound) {
                    null -> Settings.System.DEFAULT_ALARM_ALERT_URI
                    AppSettings.SILENT_SOUND -> null
                    else -> runCatching { Uri.parse(sound) }.getOrNull()
                }
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "알람음 선택")
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, allowDefault)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                runCatching { ringtonePicker.launch(intent) }
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
