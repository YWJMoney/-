package com.bamnat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftIcon

/** Drawable for each shift icon [FEAT-01]. */
val ShiftIcon.vector: ImageVector
    get() = when (this) {
        ShiftIcon.SUN -> Icons.Filled.WbSunny
        ShiftIcon.SUNRISE -> Icons.Filled.WbTwilight
        ShiftIcon.EVENING -> Icons.Filled.Brightness4
        ShiftIcon.MOON -> Icons.Filled.Bedtime
        ShiftIcon.CLOCK24 -> Icons.Filled.Timelapse
        ShiftIcon.BED -> Icons.Filled.Hotel
        ShiftIcon.COFFEE -> Icons.Filled.LocalCafe
        ShiftIcon.HOSPITAL -> Icons.Filled.LocalHospital
        ShiftIcon.FACTORY -> Icons.Filled.Factory
        ShiftIcon.STORE -> Icons.Filled.Storefront
        ShiftIcon.CAR -> Icons.Filled.DirectionsCar
        ShiftIcon.SHIELD -> Icons.Filled.Shield
        ShiftIcon.SCHOOL -> Icons.Filled.School
        ShiftIcon.FLIGHT -> Icons.Filled.Flight
        ShiftIcon.HOME -> Icons.Filled.Home
        ShiftIcon.STAR -> Icons.Filled.Star
    }

/** Colour of leave marks [FEAT-10]: violet for paid, grey for unpaid, teal for 보상휴가 [PAYX-18]. */
fun leaveColor(kind: LeaveKind): Color = when (kind) {
    LeaveKind.ANNUAL -> Color(0xFF7048E8)
    LeaveKind.PAID -> Color(0xFF1C7ED6)
    LeaveKind.UNPAID -> Color(0xFF868E96)
    LeaveKind.COMP -> Color(0xFF0C8599)
}

/** Small square with 연/반/¼, like the 잔·특·대 marks. */
@Composable
fun LeaveMark(leave: Leave, size: Dp = 16.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(leaveColor(leave.kind)),
        contentAlignment = Alignment.Center,
    ) {
        // Sized to the square, not the font setting, so a big font cannot spill out of it.
        val fs = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.6f).toSp() }
        Text(leave.short, color = contentColorOn(leaveColor(leave.kind)), fontSize = fs,
            fontWeight = FontWeight.Bold, lineHeight = fs, maxLines = 1)
    }
}
