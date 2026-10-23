package com.bamnat.app.alarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import java.time.Instant

/**
 * [PROV-06] The mission before the alarm goes off: [Missions.PROBLEMS] sums on a number pad, or
 * [Missions.SHAKES] shakes. 다시 울림 is always there, and after [Missions.GIVE_UP_AFTER] of ringing
 * 미션 없이 끄기 too, so no alarm is ever impossible to stop. A phone without a motion sensor gets sums.
 */
@Composable
internal fun MissionScreen(
    mission: AlarmMission,
    since: Long,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val shakeOk = remember { hasMotionSensor(context) }
    val kind = if (mission == AlarmMission.SHAKE && !shakeOk) AlarmMission.MATH else mission
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
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
            when (kind) {
                AlarmMission.SHAKE -> ShakeMission { onDone("mission") }
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

/** [PROV-06] Whether 흔들기 can be offered: a phone without a motion sensor is given sums instead. */
fun hasMotionSensor(context: Context): Boolean = context.getSystemService(SensorManager::class.java)?.let {
    it.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null || it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
} ?: false
