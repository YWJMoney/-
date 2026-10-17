package com.bamnat.app.sleep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.theme.BamnatTheme

/**
 * [SLP-08] What the app does with Health Connect sleep. Health Connect opens this from its
 * permission screen ("개인정보처리방침"), and refuses to ask for permission without it.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BamnatTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("수면 기록 읽기", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        for (line in LINES) {
                            Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = ::finish) { Text("확인") }
                    }
                }
            }
        }
    }

    private companion object {
        val LINES = listOf(
            "밤낮은 헬스 커넥트에서 최근 30일 수면 시간(잠든 시각과 깬 시각)만 읽어요.",
            "읽은 수면은 날짜 화면의 '최근 7일 수면'과 목표보다 부족한 시간을 계산하는 데만 써요.",
            "헬스 커넥트에 아무것도 쓰지 않고, 따로 저장하거나 백업 파일에 넣지 않으며, 휴대폰 밖으로 보내지 않아요.",
            "직접 누른 수면 기록과 겹치면 직접 기록을 써요.",
            "설정 > 수면에서 끄거나 헬스 커넥트 앱에서 허용을 거두면 바로 읽지 않아요.",
        )
    }
}
