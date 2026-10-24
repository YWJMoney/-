package com.bamnat.core.remote

import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PublicServantPay
import com.bamnat.core.schedule.PatternPresets
import com.bamnat.core.schedule.ShiftTypeSets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** [REMOTE-03] The presets file holds what the code held before 1.22, and a downloaded one is laid over it. */
class PresetFileTest {
    @After fun reset() = PresetBook.use(null)

    @Test fun builtInFileKeepsThe121Presets() {
        assertEquals(
            listOf("DDNNOO", "DNOO", "DDEENNOO", "DDDDDOOEEEEEOONNNNNOO", "DO", "DDDDDOO", "DDDNNNOOO", "DDDEEENNNOOO", "DDEENNOOOO",
                "DDNNOOOO", "DDOONNOO", "XB", "XBO", "XBXBOOOO", "DNBO", "DDDDDOONBNBNBNBNBNBNB"),
            PatternPresets.all.map { it.codes },
        )
        assertEquals("4조 3교대", PatternPresets.all[2].name)
        // [JOB-04] 경찰·소방 순서는 공개 자료가 있는 것만, 출처와 함께
        assertEquals(listOf("p-24h-3", "p-fire-3j2k-21"), PatternPresets.all.filter { "FIRE" in it.jobs }.map { it.id })
        assertEquals(listOf("p-police-4j2k"), PatternPresets.all.filter { "POLICE" in it.jobs }.map { it.id })
        assertTrue(PatternPresets.all.filter { it.jobs.isNotEmpty() }.all { it.source.contains("https://") })
        assertEquals(listOf("병원 3교대 (D/E/N)", "공장 3교대", "12시간 2교대", "24시간 맞교대"), ShiftTypeSets.all.map { it.name })
        val nurse = ShiftTypeSets.all[0].types
        assertEquals(listOf("D", "E", "N"), nurse.map { it.label })
        assertEquals(0xFF6C5CE7, nurse[2].colorArgb)
        assertEquals(LocalTime.of(23, 0), nurse[2].start)
        assertEquals(90, nurse[0].alarms.single().offsetMinutes)
        assertEquals(240, ShiftTypeSets.all[3].types.single().breakMinutes)
        assertEquals(Occupation.entries.toList(), PayPresets.all.map { it.occupation })
        val n = PayPresets.of(Occupation.NURSE)
        assertEquals(3_000_000L, n.baseHint)
        assertEquals(listOf(30_000L, 100_000L, 50_000L, 200_000L), n.items.map { it.suggestedAmount })
        assertEquals(listOf(false, true, true, false), n.items.map { it.ordinary })
        assertEquals(listOf(true, true, false, false), n.items.map { it.checked })
        assertEquals(Cadence.PER_NIGHT_SHIFT, n.items[0].cadence)
        assertEquals(10_320L, PayPresets.of(Occupation.PART_TIME).baseHint)
        assertEquals(PublicServantPay.NOTE, PayPresets.of(Occupation.POLICE).note)
        assertEquals(2_542_700L, PayPresets.of(Occupation.FIRE).baseHint)
        assertEquals(480, PayPresets.of(Occupation.CARE).dailyLimitMinutes)
        assertTrue(PayPresets.of(Occupation.CUSTOM).items.isEmpty())
    }

    private fun over(data: String, version: Long = PresetBook.bundledVersion + 1) = RemoteFiles.parse(
        """{"format":1,"kind":"presets","version":$version,"issued":"2026-11-01","minApp":33,"data":$data}""".toByteArray(),
    )

    @Test fun newPresetFileShowsWithoutAnUpdate() {
        PresetBook.use(over("""{
          "patterns": [
            {"id":"p-police-4j3k","name":"4조 3교대 (경찰)","description":"주주야야비휴휴휴","codes":"DDNNBOOO","jobs":["POLICE"],"source":"시험"},
            {"id":"p-4j2k","name":"4조 2교대","description":"고친 설명","codes":"DNOO"},
            {"id":"p-xb","removed":true},
            {"id":"p-24h-2","removed":true},
            {"id":"p-future","name":"미래","codes":"DZOO"}
          ],
          "pay": [{"occupation":"NURSE","wageType":"MONTHLY","baseHint":3100000,"items":[
            {"id":"night","name":"나이트 수당","cadence":"PER_NIGHT_SHIFT","amount":35000},
            {"id":"new","name":"새 수당","cadence":"SOMETHING_NEWER","amount":1}]},
            {"occupation":"ASTRONAUT","wageType":"MONTHLY","baseHint":1,"items":[]}]
        }"""))
        val all = PatternPresets.all
        assertEquals("p-police-4j3k", all.last().id)
        assertEquals(listOf("POLICE"), all.last().jobs)
        assertEquals("고친 설명", all.first { it.id == "p-4j2k" }.description)
        assertFalse(all.any { it.codes == "XB" })
        assertFalse(all.any { it.name == "미래" })
        assertEquals(16, all.size)
        assertEquals(3_100_000L, PayPresets.of(Occupation.NURSE).baseHint)
        assertEquals(listOf(35_000L), PayPresets.of(Occupation.NURSE).items.map { it.suggestedAmount })
        assertEquals(4, ShiftTypeSets.all.size)
        PresetBook.use(null)
        assertEquals(30_000L, PayPresets.of(Occupation.NURSE).items[0].suggestedAmount)
    }

    @Test fun anOlderOrSameFileChangesNothing() {
        PresetBook.use(over("""{"patterns":[{"id":"p-new","name":"새","codes":"DO"}]}""", version = PresetBook.bundledVersion))
        assertFalse(PatternPresets.all.any { it.id == "p-new" })
    }

    @Test fun missingFieldMakesTheFileWrong() {
        try {
            PresetFile.read(over("""{"patterns":[{"id":"p-new","codes":"DO"}]}"""))
            throw AssertionError("accepted")
        } catch (e: RemoteFileException) { }
        assertNull(PresetFile.read(over("""{"pay":[{"occupation":"CARE","wageType":"HOURLY","baseHint":1,"items":[],"dailyLimitMinutes":null}]}""")).pay.single().dailyLimitMinutes)
    }
}
