package com.bamnat.app.ui.settings

import com.bamnat.app.ui.guide.settingsPage
import com.bamnat.core.consent.RecommendedFeature
import com.bamnat.core.consent.RecommendedPlan
import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.guide.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SET-02][SET-03][SIMPLE-03][SIMPLE-04] 1.22's settings: seven groups, each row in one place, every new
 * setting findable, and every "설정 › …" the app says naming a place that is there.
 */
class SettingsLayoutTest {
    /** The places a "설정 › …" may name: the groups and the rows of the settings list under them. */
    private val places = SettingsPage.entries.map { it.title }.toSet() + setOf("프로", "기능 켜고 끄기", "도움말과 문의", "회사 규칙 광장")

    private fun group(where: String): String? =
        where.removePrefix("설정 › ").takeIf { it != where }?.substringBefore(" ›")?.substringBefore(" (")?.trim()

    @Test fun sevenGroups() {
        assertEquals(
            listOf("내 근무", "알람", "알림과 잠", "급여와 연차", "달력 화면", "데이터", "개인정보·앱 정보"),
            SettingsPage.entries.map { it.title },
        )
        Place.entries.filter { it.isSettings }.forEach { assertNotNull(it.name, it.settingsPage()) }
    }

    @Test fun aRowIsInOnePlace() {
        val rows = SettingsIndex.entries().mapNotNull { it.place as? SettingPlace.Row }.map { it.row }
        assertEquals(rows.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), rows.size, rows.toSet().size)
        // What moved out of a group is found where it lives now.
        val where = SettingsIndex.entries().associate { it.title to it.where }
        assertEquals("내 근무", where["듀티표 넣기"])
        assertEquals("내 근무", where["근태 엑셀 가져오기"])
        assertEquals("달력 › 도구", where["근무표 공유"])
        assertEquals("알람", where["알람 점검"])
        assertEquals("알림과 잠", where["근무표 점검 기준"])
        assertEquals("개인정보·앱 정보", where["이용약관"])
    }

    @Test fun everySettingsPlaceTheAppNamesIsThere() {
        for (f in FeatureRegistry.all) group(f.where)?.let { assertTrue("${f.id}: ${f.where}", it in places) }
        for (e in SettingsIndex.entries()) group(e.where)?.let { assertTrue("${e.title}: ${e.where}", it in places) }
        RecommendedFeature.entries.forEach { assertEquals(it.title, "설정 › ${SettingsPage.NOTICES.title}", it.offAt) }
        assertEquals("설정 › ${SettingsPage.NOTICES.title}", RecommendedPlan.TURN_OFF)
    }

    /** [SET-03] Each setting the other 1.22 bundles added is found by its own words. */
    @Test fun everyNewSettingIsFound() {
        fun found(query: String) = SettingsIndex.search(query).map { it.title }
        listOf(
            "사용 통계 보내기" to "사용 통계", "광장 소식" to "광장 알림", "가린 사람" to "차단", "근무표 점검 기준" to "나오데",
            "연차 사용 촉진 알림" to "연차 소멸 안내", "위젯에 메모 보이기" to "위젯 메모", "사진도 백업" to "영수증 사진",
            "광장 시세와 내 단가 견주기" to "시세", "급여 계산" to "호봉", "급여 계산" to "부양가족", "추천 기능 한 번에 켜기" to "끄는 법",
        ).forEach { (title, query) -> assertTrue("$query → $title: ${found(query)}", title in found(query)) }
    }

    @Test fun helpForTheMergedGroupsStartsWithItsAnswers() {
        val ids = HelpFaq.about("설정 > ${SettingsPage.NOTICES.title}").map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("sleep", "restRules", "turnOff")))
    }
}
