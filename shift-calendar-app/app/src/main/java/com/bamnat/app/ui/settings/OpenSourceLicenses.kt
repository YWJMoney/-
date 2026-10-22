package com.bamnat.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One library the app ships with and the terms it comes under [OPS-23]. */
data class OpenSourceNotice(val name: String, val owner: String, val license: String, val url: String)

/**
 * [OPS-23] Everything bundled into the app, from the dependencies in app/build.gradle.kts and
 * core/build.gradle.kts. The app draws its text in the phone's own font, so no font licence applies.
 * Adding a dependency means adding it here; OpenSourceNoticesTest checks the list against the build file.
 */
val OPEN_SOURCE_NOTICES: List<OpenSourceNotice> = listOf(
    OpenSourceNotice("Kotlin, kotlinx.coroutines", "JetBrains", APACHE_2, "https://github.com/JetBrains/kotlin"),
    OpenSourceNotice("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "The Android Open Source Project", APACHE_2,
        "https://developer.android.com/jetpack/androidx/releases/compose"),
    OpenSourceNotice("AndroidX Core, Activity, Lifecycle, Navigation, SplashScreen", "The Android Open Source Project", APACHE_2,
        "https://developer.android.com/jetpack/androidx"),
    OpenSourceNotice("AndroidX Room", "The Android Open Source Project", APACHE_2, "https://developer.android.com/jetpack/androidx/releases/room"),
    OpenSourceNotice("Jetpack Glance (홈 화면 위젯)", "The Android Open Source Project", APACHE_2,
        "https://developer.android.com/jetpack/androidx/releases/glance"),
    OpenSourceNotice("Health Connect client", "The Android Open Source Project", APACHE_2,
        "https://developer.android.com/jetpack/androidx/releases/health-connect"),
    OpenSourceNotice("ML Kit 글자 인식 (한국어)", "Google", "ML Kit 이용약관", "https://developers.google.com/ml-kit/terms"),
    OpenSourceNotice("Google Play 인앱 리뷰", "Google", "Play Core 소프트웨어 개발 키트 서비스 약관",
        "https://developer.android.com/guide/playcore/license"),
    // [DAYW-07][SHFT-08]
    OpenSourceNotice("Google Play 서비스 (위치, Wear OS 연결)", "Google", "Android 소프트웨어 개발 키트 라이선스",
        "https://developer.android.com/studio/terms"),
)

private const val APACHE_2 = "Apache License 2.0"

/** 설정 › 앱 정보 › 오픈소스 라이선스: names, owners and licences, each opening its page. */
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오픈소스 라이선스") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        "밤낮은 아래 소프트웨어를 써서 만들었어요. 항목을 누르면 라이선스 원문이 열려요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(OPEN_SOURCE_NOTICES, key = { it.name }) { n ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(n.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${n.owner} · ${n.license}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
