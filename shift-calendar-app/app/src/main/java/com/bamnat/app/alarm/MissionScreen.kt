package com.bamnat.app.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.alarm.AlarmMission
import com.bamnat.core.alarm.Missions
import com.bamnat.core.alarm.ShakeCounter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import com.bamnat.core.time.Today

/**
 * [PROV-06] The mission before the alarm goes off: [Missions.PROBLEMS] sums on a number pad, or
 * [Missions.SHAKES] shakes. 다시 울림 is always there, and after [Missions.GIVE_UP_AFTER] of ringing
 * 미션 없이 끄기 too, so no alarm is ever impossible to stop. A phone without a motion sensor gets sums.
 * [ALRM-04] 증명 사진 compares a photo of the place with [references]; without them, after
 * [Missions.PHOTO_TRIES] wrong photos or when the camera app fails, it becomes sums ([Missions.shown]).
 */
@Composable
internal fun MissionScreen(
    mission: AlarmMission,
    since: Long,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onDone: (String) -> Unit,
    references: List<Long> = emptyList(),
    place: String = "",
    wrongBefore: Int = 0,
) {
    val context = LocalContext.current
    val shakeOk = remember { hasMotionSensor(context) }
    // Kept across the camera app taking the screen (the activity may be made again behind it).
    var wrongPhotos by rememberSaveable { mutableIntStateOf(wrongBefore) }
    var cameraFailed by rememberSaveable { mutableStateOf(false) }
    val kind = Missions.shown(mission, references.isNotEmpty(), wrongPhotos, cameraFailed, shakeOk)
    val now by produceState(Today.millis()) {
        while (true) {
            value = Today.millis()
            delay(1_000)
        }
    }
    val canSkip = Missions.canSkip(Instant.ofEpochMilli(since), Instant.ofEpochMilli(now))
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1D2B6B), Color(0xFF0F1220))))
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().widthIn(max = 480.dp).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mission == AlarmMission.PHOTO && kind != AlarmMission.PHOTO) {
                val why = when {
                    references.isEmpty() -> "기준 사진이 없어서"
                    cameraFailed -> "카메라를 열지 못해서"
                    else -> "사진이 ${Missions.PHOTO_TRIES}번 맞지 않아서"
                }
                Text("$why 계산 문제로 바꿨어요", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
            }
            when (kind) {
                AlarmMission.SHAKE -> ShakeMission { onDone("mission") }
                AlarmMission.PHOTO -> PhotoMission(references, place, wrongPhotos,
                    onWrong = { wrongPhotos++ }, onFailed = { cameraFailed = true }) { onDone("mission") }
                else -> MathMission { onDone("mission") }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (canSkip) {
                OutlinedButton(onClick = { onDone("skip") }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("미션 없이 끄기", color = Color.White)
                }
            } else {
                val left = (since + Missions.GIVE_UP_AFTER.toMillis() - now).coerceAtLeast(0) / 1_000
                Text("%d:%02d 뒤에는 미션 없이도 끌 수 있어요".format(left / 60, left % 60),
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("${snoozeMinutes}분 뒤 다시 울림", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MathMission(onSolved: () -> Unit) {
    var solved by rememberSaveable { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(Missions.problem()) }
    var typed by rememberSaveable { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Text("계산 문제 ${solved + 1}/${Missions.PROBLEMS}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
    Text("${problem.text} = ?", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium, style = NumberStyle)
    Text(typed.ifEmpty { " " }, color = if (wrong) Color(0xFFFF8A80) else Color.White, fontSize = 40.sp, style = NumberStyle,
        modifier = Modifier.semantics { contentDescription = "넣은 답 ${typed.ifEmpty { "없음" }}" })
    if (wrong) Text("다시 해 보세요", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyMedium)
    fun check() {
        if (typed.toIntOrNull() == problem.answer) {
            wrong = false
            typed = ""
            if (solved + 1 >= Missions.PROBLEMS) onSolved() else { solved++; problem = Missions.problem() }
        } else {
            wrong = true
            typed = ""
        }
    }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "지우기", "0", "확인")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { k ->
                    val action = k == "확인"
                    Button(
                        onClick = {
                            when (k) {
                                "지우기" -> typed = typed.dropLast(1)
                                "확인" -> check()
                                else -> if (typed.length < 4) { typed += k; wrong = false }
                            }
                        },
                        enabled = !action || typed.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(60.dp),
                        // Readable before anything is typed too, so the key is found in the dark.
                        colors = if (action) ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B5E), contentColor = com.bamnat.app.ui.components.contentColorOn(Color(0xFFFF6B5E)),
                            disabledContainerColor = Color(0xFFFF6B5E).copy(alpha = 0.35f), disabledContentColor = Color.White.copy(alpha = 0.7f))
                        else ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                    ) { Text(k, style = if (k.length == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@Composable
private fun ShakeMission(onSolved: () -> Unit) {
    val context = LocalContext.current
    var count by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(count) { if (count >= Missions.SHAKES) onSolved() }
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val linear = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val counter = ShakeCounter()
        val gravity = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                var (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                if (linear == null) {
                    // Gravity out, the way Android's own guide does it: a slow average taken away.
                    for (i in 0..2) gravity[i] = 0.8f * gravity[i] + 0.2f * e.values[i]
                    x -= gravity[0]; y -= gravity[1]; z -= gravity[2]
                }
                if (counter.add(x, y, z, e.timestamp / 1_000_000)) count++
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(listener) }
    }
    Text("흔들기", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
    Text("${count.coerceAtMost(Missions.SHAKES)} / ${Missions.SHAKES}", color = Color.White, fontSize = 56.sp, style = NumberStyle)
    LinearProgressIndicator(progress = { count.coerceAtMost(Missions.SHAKES) / Missions.SHAKES.toFloat() },
        modifier = Modifier.fillMaxWidth().height(10.dp))
    Spacer(Modifier.height(8.dp))
    Text("휴대폰을 손에 꼭 쥐고 크게 흔들어 주세요", color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

/**
 * [ALRM-04] 증명 사진: the camera app (no camera permission, as 명세서 사진) writes a photo, its fingerprint is
 * compared and the photo deleted. Over the lock screen the phone is unlocked first, so the camera app can open.
 */
@Composable
private fun PhotoMission(references: List<Long>, place: String, wrong: Int, onWrong: () -> Unit, onFailed: () -> Unit, onSolved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // Some camera apps say no and still write the photo; an empty file is no photo.
        if (!ok && PhotoPrint.file(context).length() == 0L) {
            PhotoPrint.clear(context)
            onFailed()
            return@rememberLauncherForActivityResult
        }
        reading = true
        scope.launch {
            val hash = PhotoPrint.take(context, Uri.fromFile(PhotoPrint.file(context)))
            reading = false
            when (val r = PhotoAttempts(references, wrong).taken(hash)) {
                PhotoAttempts.Outcome.Done -> onSolved()
                is PhotoAttempts.Outcome.Again -> onWrong()
                PhotoAttempts.Outcome.Sums -> if (hash == null) onFailed() else onWrong()
            }
        }
    }
    fun open() {
        val go = {
            try {
                take.launch(FileProvider.getUriForFile(context, "${context.packageName}.share", PhotoPrint.file(context)))
            } catch (e: ActivityNotFoundException) {
                onFailed()
            } catch (e: RuntimeException) {
                // No FileProvider path or no right to the camera app: sums, never stuck.
                onFailed()
            }
        }
        val activity = context.activity()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (activity != null && keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
                override fun onDismissError() = onFailed()
                // Not unlocked this time: the button stays, and 다시 울림 too.
            })
        } else go()
    }
    Text("증명 사진", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium)
    Text(place.ifBlank { "정해 둔 곳" }, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    Text("기준 사진을 찍은 자리에서 같은 곳을 찍으면 꺼져요", color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    if (wrong > 0) {
        Text("조금 다르게 보여요. 같은 자리에서 다시 찍어 주세요 · ${Missions.PHOTO_TRIES - wrong}번 남음",
            color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = ::open,
        enabled = !reading,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B5E), contentColor = com.bamnat.app.ui.components.contentColorOn(Color(0xFFFF6B5E))),
    ) { Text(if (reading) "사진을 맞춰 보는 중…" else "같은 곳 찍기", style = MaterialTheme.typography.titleMedium) }
    Text("사진은 휴대폰 안에서 흑백 지문만 만들어 견주고 바로 지워요", color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** [PROV-06] Whether 흔들기 can be offered: a phone without a motion sensor is given sums instead. */
fun hasMotionSensor(context: Context): Boolean = context.getSystemService(SensorManager::class.java)?.let {
    it.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null || it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
} ?: false
