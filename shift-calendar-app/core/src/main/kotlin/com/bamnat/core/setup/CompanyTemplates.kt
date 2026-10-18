package com.bamnat.core.setup

import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.BundleShift
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftBundles
import com.bamnat.core.schedule.ShiftRole
import com.bamnat.core.work.WorkStyle
import java.time.LocalTime

/**
 * [COMM-05] One kind of workplace as a starting point when the company is not found: the usual
 * shifts and order of that kind of work, never a real company's. [order] is in preset codes (empty
 * for a roster that comes every month or for days that are not fixed); [wage] is how such work is
 * usually paid ("MONTHLY", "HOURLY" or "DAILY"), asked again in the pay setup.
 */
data class CompanyTemplate(
    val id: String,
    val name: String,
    val styles: Set<WorkStyle>,
    val shifts: List<BundleShift>,
    val order: String = "",
    val roster: Boolean = false,
    val wage: String = "MONTHLY",
) {
    /** "주간 07~19 · 야간 19~07 · 8일 순서" */
    val summary: String
        get() = buildList {
            shifts.filter { !it.role.isOff }.forEach { s ->
                add("${s.type.name} ${hour(s.type.start)}~${hour(s.type.end)}")
            }
            when {
                order.isNotEmpty() -> add("${order.length}일 순서")
                roster -> add("매달 근무표")
            }
        }.joinToString(" · ")

    /** The order as the shifts' names, null a day off without a shift; empty when there is none. */
    val orderNames: List<String?>
        get() {
            val byCode = shifts.groupBy { it.role.code }.mapValues { it.value.first().type.name }
            return order.map { c -> byCode[c] ?: if (c == 'B') byCode['O'] else null }
        }

    private fun hour(t: LocalTime?): String = when {
        t == null -> "--"
        t.minute == 0 -> "%02d".format(t.hour)
        else -> "%02d:%02d".format(t.hour, t.minute)
    }
}

/** [COMM-05] The 9 kinds of workplace offered before a company is found. Generic: no company, no amount, no date. */
object CompanyTemplates {
    /** Shown with every template, so no one mistakes it for a company's own setup. */
    const val BADGE = "업종 일반형 · 실제 회사 정보가 아니에요"

    private fun shift(role: ShiftRole, name: String, label: String, color: Long, start: Int, end: Int, breakMinutes: Int) =
        BundleShift(role, ShiftType(0, name, label, color, LocalTime.of(start, 0), LocalTime.of(end, 0), breakMinutes = breakMinutes))

    private fun bundle(key: String) = ShiftBundles.byKey(key)!!.shifts

    val all: List<CompanyTemplate> = listOf(
        CompanyTemplate("nurse3", "병원 간호 3교대", setOf(WorkStyle.SHIFT), bundle(ShiftBundles.HOSPITAL), roster = true),
        CompanyTemplate("two12", "4조 2교대 12시간", setOf(WorkStyle.SHIFT), bundle(ShiftBundles.TWO), order = "DDNNOOOO"),
        CompanyTemplate("three2", "3조 2교대", setOf(WorkStyle.SHIFT), bundle(ShiftBundles.TWO), order = "DDNNOO"),
        CompanyTemplate("three3", "3조 3교대", setOf(WorkStyle.SHIFT), bundle(ShiftBundles.THREE), order = "DDDDDOOEEEEEOONNNNNOO"),
        CompanyTemplate("guard24", "24시간 격일제 경비", setOf(WorkStyle.SHIFT),
            bundle(ShiftBundles.DUTY).filter { it.role != ShiftRole.OFF }, order = "XB"),
        CompanyTemplate("care2", "요양시설 2교대", setOf(WorkStyle.SHIFT), listOf(
            shift(ShiftRole.DAY, "주간", "주", 0xFF2F80ED, 8, 20, 60),
            shift(ShiftRole.NIGHT, "야간", "야", 0xFF6C5CE7, 20, 8, 60),
            bundle(ShiftBundles.TWO).last(),
        ), order = "DNOO"),
        CompanyTemplate("office", "평일 일근", setOf(WorkStyle.DAY), ShiftBundles.weekday),
        CompanyTemplate("parttime", "시급 아르바이트", setOf(WorkStyle.HOURLY), ShiftBundles.hourly, wage = "HOURLY"),
        CompanyTemplate("daily", "일용직 일당", setOf(WorkStyle.HOURLY, WorkStyle.DAY),
            listOf(shift(ShiftRole.DAY, "일당 근무", "일", 0xFF00A88F, 8, 17, 60)), wage = "DAILY"),
    )

    fun byId(id: String?): CompanyTemplate? = all.firstOrNull { it.id == id }

    /** The templates for [style], most common first. */
    fun forStyle(style: WorkStyle?): List<CompanyTemplate> = all.filter { style in it.styles }

    /** Every order a template uses is one of the presets, so it can be shown by name. */
    fun preset(t: CompanyTemplate) = PatternPresets.all.firstOrNull { it.codes == t.order }
}
