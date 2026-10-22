package com.bamnat.app.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.alarm.WakeCheck
import com.bamnat.app.alarm.hasMotionSensor
import com.bamnat.app.data.AppSettings
import com.bamnat.app.tier.Feature
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.pro.openPro
import com.bamnat.core.alarm.AlarmMission
import com.bamnat.core.alarm.Missions
import com.bamnat.core.model.ShiftType

/** [PROV-06] The rows' titles, which settings search points at. */
const val MISSION_ROW = "끄기 미션"
const val WAKE_CHECK_ROW = "일어났는지 확인"

/**
 * [PROV-06][PROV-07] 확실하게 깨우기 (프로): what 끄기 asks for, each shift's own if it differs, and
 * 일어났나요? a few minutes after. Locked rows open 프로.
 */
@Composable
internal fun SureAlarmCard(vm: SettingsViewModel, settings: AppSettings, ringing: List<ShiftType>) {
    val context = LocalContext.current
    val gate by vm.gate.collectAsStateWithLifecycle()
    val open = gate.allows(Feature.SURE_ALARM)
    val shake = remember { hasMotionSensor(context) }
    val missions = AlarmMission.entries.filter { it != AlarmMission.SHAKE || shake }
    var perShift by rememberSaveable { mutableStateOf(settings.shiftMissions.isNotEmpty()) }
    SectionCard(title = "확실하게 깨우기" + if (open) "" else " (프로)") {
        val chosen = if (open) AlarmMission.of(settings.alarmMission) else AlarmMission.NONE
        ChoiceRow(
            MISSION_ROW,
            "알람을 끄려면 풀어야 해요. ${chosen.hint}. ${Missions.GIVE_UP_AFTER.toMinutes()}분이 지나면 미션 없이도 끌 수 있어요",
            missions.map { it to it.label }, chosen,
        ) { m -> if (!open) openPro() else vm.update { it.copy(alarmMission = m.key) } }
        if (ringing.isNotEmpty()) {
            HorizontalDivider()
            SwitchRow("근무마다 다르게", "예: 야간 뒤 아침 근무만 계산 문제", perShift || settings.shiftMissions.isNotEmpty()) { on ->
                if (!open) { openPro(); return@SwitchRow }
                perShift = on
                if (!on) vm.update { it.copy(shiftMissions = emptyMap()) }
            }
            if (open && (perShift || settings.shiftMissions.isNotEmpty())) {
                for (t in ringing) {
                    val own = settings.shiftMissions[t.id]?.let(AlarmMission::of)
                    ChoiceRow<AlarmMission?>(t.name, null,
                        listOf<Pair<AlarmMission?, String>>(null to "위와 같게") + missions.map { it to it.label }, own) { m ->
                        vm.update { s -> s.copy(shiftMissions = if (m == null) s.shiftMissions - t.id else s.shiftMissions + (t.id to m.key)) }
                    }
                }
            }
        }
        HorizontalDivider()
        val on = open && settings.wakeCheckMinutes > 0
        SwitchRow(
            WAKE_CHECK_ROW,
            "알람을 끄고 ${if (on) settings.wakeCheckMinutes else DEFAULT_WAKE_MINUTES}분 뒤 ‘일어났나요?’를 물어요. " +
                "${WakeCheck.ANSWER_SECONDS}초 안에 ‘일어났어요’를 누르지 않으면 알람이 한 번 더 울려요",
            on,
        ) { want ->
            if (!open) openPro() else vm.update { it.copy(wakeCheckMinutes = if (want) DEFAULT_WAKE_MINUTES else 0) }
        }
        if (on) {
            ChoiceRow("묻는 때", null, listOf(3, 5, 10, 15).map { it to "${it}분 뒤" }, settings.wakeCheckMinutes) { m ->
                vm.update { it.copy(wakeCheckMinutes = m) }
            }
        }
    }
}

private const val DEFAULT_WAKE_MINUTES = 5
