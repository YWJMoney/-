package com.bamnat.app.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ThemeMode
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.app.ui.theme.BamnatTheme
import kotlinx.coroutines.delay
import java.time.LocalTime

/** Full-screen ringing screen, shown over the lock screen. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val snoozeMinutes = BootAlarms.ringSettings(this).snoozeMinutes

        setContent {
            BamnatTheme(themeMode = ThemeMode.DARK) {
                val ring by AlarmService.ringing.collectAsStateWithLifecycle()
                // Leave as soon as the alarm stops, whether from here, the notification or the timeout.
                LaunchedEffect(ring) {
                    if (ring == null) finish()
                }
                // Back must not silently leave an alarm ringing; it snoozes instead.
                BackHandler {
                    AlarmService.snooze(this@AlarmActivity)
                }
                val current = ring
                if (current != null) {
                    RingingScreen(
                        title = current.label ?: if (current.shiftName.isBlank()) "알람" else "${current.shiftName} 근무",
                        subtitle = AlarmService.describe(current),
                        snoozeMinutes = snoozeMinutes,
                        onSnooze = { AlarmService.snooze(this@AlarmActivity) },
                        onDismiss = { AlarmService.dismiss(this@AlarmActivity) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RingingScreen(
    title: String,
    subtitle: String,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1D2B6B), Color(0xFF0F1220))))
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                Fmt.time(now),
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                style = NumberStyle,
            )
            Spacer(Modifier.height(12.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Icon(Icons.Filled.Snooze, contentDescription = null)
                Text("  ${snoozeMinutes}분 뒤 다시 울림", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B5E), contentColor = Color.White),
            ) {
                Text("알람 끄기", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
