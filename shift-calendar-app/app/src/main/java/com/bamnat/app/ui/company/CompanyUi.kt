package com.bamnat.app.ui.company

import com.bamnat.app.ui.components.labelWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.company.CompanyTerms
import com.bamnat.app.company.ReportStatus

/** What to say for an error word from the company server [COMM-04], with the field it was about. */
fun companyProblem(code: String, field: String? = null): String = when (code) {
    "offline" -> "인터넷에 연결되지 않았어요. 연결한 뒤 다시 해 주세요"
    "rate_limited" -> "오늘은 더 보낼 수 없어요. 내일 다시 보내 주세요"
    "amount", "phone", "email" -> when (field) {
        "note" -> when (code) {
            "amount" -> "한마디에 금액은 빼 주세요. ‘기본급의 10%’처럼 비율로 적으면 돼요"
            "phone" -> "한마디에 전화번호는 빼 주세요"
            else -> "한마디에 메일 주소는 빼 주세요"
        }
        "dept" -> "부서 이름에 숫자나 연락처처럼 보이는 글자를 빼 주세요"
        "company" -> "회사 이름에 숫자나 연락처처럼 보이는 글자를 빼 주세요"
        "pay", "items", "rules" -> "급여 설정에 금액이 남아 있어 보낼 수 없어요. 앱을 업데이트해 주세요"
        else -> "근무나 수당 이름에 금액·연락처처럼 보이는 글자가 있어요. 이름을 바꾼 뒤 보내 주세요"
    }
    "company" -> "회사 이름을 두 글자 이상, 40자 안으로 적어 주세요"
    "dept" -> "부서는 20자 안으로 적어 주세요"
    "note" -> if (field == "note") "한마디를 적어 주세요. 너무 길면 줄여 주세요" else "한마디가 너무 길어요"
    "setup", "setup_version", "version", "v" -> "이 설정은 보낼 수 없어요. 앱을 최신 버전으로 업데이트해 주세요"
    "too_large" -> "설정이 너무 커서 보낼 수 없어요"
    "not_found" -> "찾을 수 없어요. 이미 지워졌을 수 있어요"
    else -> "서버에 문제가 생겼어요. 잠시 뒤 다시 해 주세요"
}

/** "채택되면 프로 3개월을 드려요" when the developer gives a reward; null while there is none. */
fun rewardLine(terms: CompanyTerms?): String? = terms?.rewardMonths?.takeIf { it > 0 }?.let(::rewardText)

/**
 * [COMM-05] Until billing ships every feature is free, so the reward is said as what it is: months
 * of 프로 once paid features come, never something to have now.
 */
fun rewardText(months: Int): String =
    if (com.bamnat.app.tier.FeatureGate.BILLING_LIVE) "채택되면 프로 ${months}개월을 드려요"
    else "지금은 모든 기능이 무료예요. 유료 기능이 생기면 채택된 설정을 보낸 분께 프로 ${months}개월을 드려요"

/** The same in a few words, for a pill next to a button. */
fun rewardShort(terms: CompanyTerms?): String? = terms?.rewardMonths?.takeIf { it > 0 }?.let { "채택되면 프로 ${it}개월" }

/** A soft pill: 🎁 채택되면 프로 3개월. */
@Composable
fun RewardPill(text: String, modifier: Modifier = Modifier) {
    // Rounded rather than a full pill, so a narrow screen that wraps it into two lines still looks tidy.
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier) {
        Text(
            "🎁 $text",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Status of a shared setup or report as a small colored label. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.GOOD -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.WAIT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Tone.BAD -> scheme.errorContainer to scheme.onErrorContainer
        Tone.QUIET -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

enum class Tone { GOOD, WAIT, BAD, QUIET }

/** Points toward adoption: a bar and "2/3점". */
@Composable
fun PointsBar(points: Int, threshold: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { (points.toFloat() / threshold.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(10.dp))
        Text("${points.coerceAtMost(threshold)}/${threshold}점", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** A label and its text, one per line of a setup in plain words. */
@Composable
fun SetupLine(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.labelWidth(92.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A round icon on a tinted circle, for the top of a step. */
@Composable
fun HeroIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, container: Color = MaterialTheme.colorScheme.primaryContainer) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp)) }
    }
}

/** A small step of how it works: a number in a circle and what happens. */
@Composable
fun HowStep(n: Int, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(26.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A bordered card, quieter than a section. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (highlighted) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainerLowest,
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, if (highlighted) scheme.primary.copy(alpha = 0.6f) else scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

/** Where a report stands, in a few words and a tone [COMM-02][COMM-03][EXTRA-06]. */
fun ReportStatus.label(): Pair<String, Tone> = when {
    status == "ask" -> "개발자가 읽는 중" to Tone.WAIT
    status == "answered" -> "답이 왔어요" to Tone.GOOD
    status == "adopted" -> "채택됐어요" to Tone.GOOD
    status == "withdrawn" -> "내려졌어요" to Tone.QUIET
    status == "rejected" -> "채택되지 않았어요" to Tone.BAD
    status == "replaced" -> "새로 보낸 것으로 바뀜" to Tone.QUIET
    review -> "개발자 확인 중" to Tone.WAIT
    else -> "모으는 중 ${points.coerceAtMost(threshold)}/${threshold}점" to Tone.WAIT
}

/** The line under a report's status: what happens next, the reason or the answer, the reward. */
fun ReportStatus.detail(): String = when {
    status == "ask" -> "앱에 없는 방식이라 개발자가 직접 읽고 답을 드려요."
    status == "answered" -> reason ?: "개발자가 답을 남겼어요."
    status == "adopted" -> when {
        reward?.paid == true -> "고마워요! 보상(프로 ${reward.months}개월)을 드렸어요."
        reward?.owed == true -> "고마워요! 보상 프로 ${reward.months}개월이 지급을 기다리고 있어요. 유료 기능이 열리면 앱에서 받아요."
        else -> "고마워요! 같은 회사 분들이 이 설정을 바로 쓸 수 있어요."
    }
    status == "withdrawn" -> "회사 찾기에서 내려졌어요. 같은 회사 분들이 새로 알려 주면 다시 올라와요."
    status == "rejected" -> reason?.let { "이유: $it" } ?: "이번에는 채택되지 않았어요."
    status == "replaced" -> "같은 곳에 나중에 보낸 설정으로 바뀌었어요."
    review -> "같은 회사 분들이 충분히 모였어요. 개발자가 확인하면 채택돼요."
    persons <= 1 -> "같은 회사 분이 같은 설정을 보내거나 ‘맞았어요’를 누르면 점수가 쌓여요."
    else -> "같은 회사 ${persons}명이 모였어요. ${(threshold - points).coerceAtLeast(1)}점만 더 모이면 채택돼요."
}
