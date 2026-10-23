package com.bamnat.app.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.core.content.FileProvider
import com.bamnat.app.alarm.PhotoPrint
import com.bamnat.app.alarm.PhotoReferences
import com.bamnat.core.alarm.ImageHash
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** [ALRM-04] The row's title, which settings search points at. */
const val PHOTO_ROW = "증명 사진 기준"

/**
 * [ALRM-04] 증명 사진's reference photos under 끄기 미션: take one with the camera app, a second for the other
 * light, take them again or delete them. Only the fingerprint stays; the photo is deleted once it is made.
 * [wanted] is set when 증명 사진 was picked before there was a reference: the first one then picks it.
 */
@Composable
internal fun PhotoMissionRows(
    references: List<PhotoReferences.Reference>,
    wanted: Boolean,
    chosen: Boolean,
    onTaken: (hash: Long, place: String, replace: Boolean) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by rememberSaveable { mutableStateOf(references.lastOrNull()?.place.orEmpty()) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok && PhotoPrint.file(context).length() == 0L) {
            PhotoPrint.clear(context)
            return@rememberLauncherForActivityResult
        }
        reading = true
        scope.launch {
            val hash = PhotoPrint.take(context, Uri.fromFile(PhotoPrint.file(context)))
            reading = false
            if (hash == null) error = "사진을 읽지 못했어요. 다시 찍어 주세요."
            else { error = null; onTaken(hash, place, replace) }
        }
    }
    fun shoot(over: Boolean) {
        replace = over
        try {
            take.launch(FileProvider.getUriForFile(context, "${context.packageName}.share", PhotoPrint.file(context)))
        } catch (e: ActivityNotFoundException) {
            error = "카메라 앱을 찾지 못했어요. 증명 사진 대신 다른 미션을 골라 주세요."
        } catch (e: IllegalArgumentException) {
            error = "사진을 둘 곳을 만들지 못했어요. 잠시 뒤 다시 해 주세요."
        }
    }
    Column(Modifier.focusMark(PHOTO_ROW).fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(PHOTO_ROW, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (references.isEmpty()) "일어나면 꼭 가는 곳(욕실 거울, 현관)을 찍어 두면 ‘증명 사진’ 미션을 고를 수 있어요. " +
                "사진은 휴대폰 안에서 흑백 지문만 만들고 바로 지워요"
            else "알람 때 이곳을 찍어야 꺼져요. 밤과 낮의 불빛이 다르면 한 장 더 찍어 두세요(가까운 쪽으로 견줘요)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (wanted && references.isEmpty()) {
            Text("증명 사진은 기준 사진을 먼저 찍어야 고를 수 있어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (chosen && references.isEmpty()) {
            Text("기준 사진이 없어서 알람 때 계산 문제로 바뀌어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        references.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val day = Instant.ofEpochMilli(r.takenAt).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("${r.place.ifBlank { "기준 ${i + 1}" }} · ${day.monthValue}월 ${day.dayOfMonth}일 찍음",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(i) }) { Text("지우기") }
            }
        }
        OutlinedTextField(
            value = place,
            onValueChange = { place = it.take(PhotoReferences.PLACE_MAX) },
            label = { Text("곳 이름 (예: 욕실 거울)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { shoot(over = true) }, enabled = !reading, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (references.isEmpty()) "기준 사진 찍기" else "다시 찍기")
            }
            if (references.isNotEmpty() && references.size < ImageHash.MAX_REFERENCES) {
                OutlinedButton(onClick = { shoot(over = false) }, enabled = !reading, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("지금 한 장 더")
                }
            }
        }
        if (reading) Text("휴대폰 안에서 지문을 만드는 중…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
