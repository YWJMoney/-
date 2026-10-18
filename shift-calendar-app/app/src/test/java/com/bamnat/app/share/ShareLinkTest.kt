package com.bamnat.app.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** [MKT-09] What a link sends, and (when a test server is running) the whole round trip. */
@RunWith(AndroidJUnit4::class)
class ShareLinkTest {
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(2, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 1)
    private val snapshot = ScheduleSnapshot(
        listOf(day, night, off),
        WorkPattern.Rotation(start, listOf(1L, 1L, 2L, 2L, 3L, 3L)),
        listOf(
            DayEntry(start, leave = Leave(LeaveKind.ANNUAL, 2)),
            DayEntry(start.plusDays(2), leave = Leave(LeaveKind.UNPAID)),
            // [EXTRA-01] A 대근 of the night shift on a day off shows as its second shift.
            DayEntry(start.plusDays(6), extras = listOf(ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(19, 0), LocalTime.of(7, 0), coveredShiftId = 2L),
                ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 60))),
        ),
    )

    @Test fun payloadHasOnlyWhatThePageShows() {
        val p = ShareLinkPayload.build(snapshot, start, " 민지 ", 30)
        assertEquals(1, p.getInt("v"))
        assertEquals("민지", p.getString("title"))
        val days = p.getJSONArray("days")
        assertEquals(ShareLinkPayload.DAYS, days.length())
        assertEquals("반차", days.getJSONObject(0).getString("leave"))
        // The kind of leave other than 연차 stays on the phone.
        assertEquals("휴가", days.getJSONObject(2).getString("leave"))
        assertEquals(2, days.getJSONObject(6).getInt("second"))
        val types = p.getJSONArray("types")
        assertEquals("#1E88E5", types.getJSONObject(0).getString("color"))
        assertEquals("07:00-19:00", types.getJSONObject(0).getString("time"))
        assertTrue(types.getJSONObject(2).getBoolean("off"))
        assertFalse(p.toString().contains("무급"))
        // Saved for the worker's own validator test in server/share-worker.
        File("build/share-payload.json").writeText(p.toString())
    }

    /** [SHR-04] Days with no work at all say so, for the receiver's 같이 쉬는 날; the day off with a 대근 does not. */
    @Test fun restDaysAreMarked() {
        val days = ShareLinkPayload.build(snapshot, start, "", 30).getJSONArray("days")
        val rest = (0 until days.length()).map { days.getJSONObject(it) }.associate { it.getString("date") to it.optBoolean("rest") }
        assertEquals(false, rest.getValue(start.toString()))  // 반차 on a 주간 day
        assertEquals(true, rest.getValue(start.plusDays(2).toString()))  // a whole day of leave
        assertEquals(true, rest.getValue(start.plusDays(4).toString()))  // 휴무
        assertEquals(false, rest.getValue(start.plusDays(6).toString()))  // 대근 on top
    }

    /**
     * [SHR-04] Rest marks follow only what the link shows: 특근 on a day off stays private and the day reads
     * as off until 연장·휴무일 근무 is ticked, and a 대근 on a day with no shift goes in as that shift.
     */
    @Test fun restFollowsWhatTheLinkShows() {
        fun dayOf(p: org.json.JSONObject, d: LocalDate) = p.getJSONArray("days").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("date") == d.toString() }
        }
        val offDay = start.plusDays(4)
        val special = DayEntry(offDay, extras = listOf(ExtraEntry(ExtraKind.SPECIAL_ID, LocalTime.of(9, 0), LocalTime.of(13, 0))))
        val withSpecial = ScheduleSnapshot(listOf(day, night, off), WorkPattern.Rotation(start, listOf(1L, 1L, 2L, 2L, 3L, 3L)), listOf(special))
        val plain = dayOf(ShareLinkPayload.build(withSpecial, start, "", 30), offDay)
        assertTrue(plain.getBoolean("rest"))
        assertFalse(plain.has("extras"))
        assertFalse(dayOf(ShareLinkPayload.build(withSpecial, start, "", 30, ShareOptions(extras = true)), offDay).getBoolean("rest"))

        val empty = start.plusDays(3)
        val cover = DayEntry(empty, extras = listOf(ExtraEntry(ExtraKind.COVER_ID, LocalTime.of(19, 0), LocalTime.of(7, 0), coveredShiftId = 2L)))
        val p = ShareLinkPayload.build(ScheduleSnapshot(listOf(day, night, off), null as WorkPattern?, listOf(cover)), start, "", 30)
        assertEquals(1, p.getInt("v"))
        val covered = dayOf(p, empty)
        assertFalse(covered.has("type"))
        assertEquals(2, covered.getInt("second"))
        assertFalse(covered.getBoolean("rest"))
        assertEquals(false, SharedSchedule.parse(p).isRest(empty))
    }

    /** [SHR-01] Leave keeps its whole name when 휴가 종류 is ticked, and otherwise only how much of the day is off. */
    @Test fun leaveLabels() {
        val leaves = listOf(Leave(LeaveKind.ANNUAL, 3), Leave(LeaveKind.PAID, 2), Leave(LeaveKind.UNPAID, 1), Leave(LeaveKind.UNPAID, 3), Leave(LeaveKind.PAID))
        assertEquals(listOf("0.75일 연차", "반차", "반반차", "0.75일 휴가", "휴가"), leaves.map { ShareLinkPayload.leaveLabel(it, kinds = false) })
        assertEquals(listOf("0.75일 연차", "유급 휴가 반차", "무급 휴가 반반차", "0.75일 무급 휴가", "유급 휴가"),
            leaves.map { ShareLinkPayload.leaveLabel(it, kinds = true) })
        // Every label fits whole in what the server takes.
        assertTrue(LeaveKind.entries.flatMap { k -> (1..4).map { q -> Leave(k, q).label } }.all { it.length <= ShareLinkPayload.LEAVE_CHARS })
    }

    /** [SHR-01] What the sharer ticks goes along; what they leave unticked stays on the phone. */
    @Test fun tickedPartsGoAlong() {
        val memoDay = start.plusDays(1)
        val all = ShareOptions(leaveKinds = true, extras = true, memos = true, pay = true)
        val pay = listOf(SharedPay(java.time.YearMonth.of(2026, 11), 3_412_000, "세전 예상"))
        val p = ShareLinkPayload.build(snapshot, start, "민지", 30, all, mapOf(memoDay to "치과 2시"), pay)
        assertEquals(2, p.getInt("v"))
        assertEquals(listOf("leaveKinds", "extras", "memos", "pay"), (0 until 4).map { p.getJSONArray("shows").getString(it) })
        val days = p.getJSONArray("days")
        assertEquals("치과 2시", days.getJSONObject(1).getString("memo"))
        assertEquals("무급 휴가", days.getJSONObject(2).getString("leave"))
        val extras = days.getJSONObject(6).getJSONArray("extras")
        assertEquals(2, extras.length())
        assertEquals("야간 대근", extras.getJSONObject(0).getString("name"))
        assertEquals("19:00-07:00", extras.getJSONObject(0).getString("time"))
        assertEquals(60, extras.getJSONObject(1).getInt("minutes"))
        assertEquals(3_412_000L, p.getJSONArray("pay").getJSONObject(0).getLong("amount"))

        // Only memos ticked: no pay, no extras, the kind of leave still private, the memo there.
        val memosOnly = ShareLinkPayload.build(snapshot, start, "민지", 30, ShareOptions(memos = true), mapOf(memoDay to "치과 2시"), pay)
        assertEquals(listOf("memos"), (0 until memosOnly.getJSONArray("shows").length()).map { memosOnly.getJSONArray("shows").getString(it) })
        assertFalse(memosOnly.has("pay"))
        assertFalse(memosOnly.toString().contains("extras"))
        assertFalse(memosOnly.toString().contains("무급"))
        assertTrue(memosOnly.toString().contains("치과 2시"))
        // Memos given but not ticked never go out.
        assertFalse(ShareLinkPayload.build(snapshot, start, "", 30, ShareOptions(extras = true), mapOf(memoDay to "치과 2시")).toString().contains("치과"))
        File("build/share-payload-v2.json").writeText(p.toString())
    }

    /** [SHR-01] A day with nothing but a memo goes only into a link with memos ticked. */
    @Test fun dayWithOnlyAMemo() {
        val noPattern = ScheduleSnapshot(listOf(day, night, off), null as WorkPattern?, listOf(DayEntry(start.plusDays(3), ShiftOverride.Shift(1L))))
        val memos = mapOf(start.plusDays(5) to "이사")
        assertEquals(1, ShareLinkPayload.build(noPattern, start, "", 30, ShareOptions(), memos).getJSONArray("days").length())
        val days = ShareLinkPayload.build(noPattern, start, "", 30, ShareOptions(memos = true), memos).getJSONArray("days")
        assertEquals(2, days.length())
        assertFalse(days.getJSONObject(1).has("type"))
        assertEquals("이사", days.getJSONObject(1).getString("memo"))
    }

    /**
     * [SHR-11] A freelancer with no shifts: the days' jobs go by title and time only, a job is work for
     * 같이 쉬는 날, and the money goes as 일감 수입. A shop sends only its sales, with no days at all.
     */
    @Test fun fieldAndShopLinks() {
        val empty = ScheduleSnapshot(emptyList(), null as WorkPattern?, emptyList())
        val jobDay = start.plusDays(2)
        val jobs = mapOf(jobDay to listOf(SharedJob("욕실 시공 가나다아파트 101동", "09:00-17:00"), SharedJob(" ", null)))
        val income = listOf(SharedPay(java.time.YearMonth.of(2026, 11), 870_300, "일감 1건"))
        val field = ShareLinkPayload.build(empty, start, "준호", 30, ShareOptions(jobs = true, pay = true), pay = income,
            payKind = com.bamnat.core.work.MoneyShare.JOBS, jobs = jobs)
        assertEquals(listOf("pay", "jobs"), (0 until 2).map { field.getJSONArray("shows").getString(it) })
        assertEquals("jobs", field.getString("payKind"))
        val days = field.getJSONArray("days")
        assertEquals(1, days.length())
        val booked = days.getJSONObject(0).getJSONArray("jobs")
        assertEquals("욕실 시공 가나다아파트", booked.getJSONObject(0).getString("name"))
        assertEquals(ShareLinkPayload.JOB_CHARS, booked.getJSONObject(0).getString("name").length)
        assertEquals("일감", booked.getJSONObject(1).getString("name"))
        assertFalse(days.getJSONObject(0).getBoolean("rest"))
        assertTrue(ShareLinkPayload.hasContent(field))
        // Jobs given but not ticked never go out, and then the link has nothing to show.
        val unticked = ShareLinkPayload.build(empty, start, "", 30, ShareOptions(), jobs = jobs)
        assertFalse(unticked.toString().contains("욕실"))
        assertFalse(ShareLinkPayload.hasContent(unticked))
        File("build/share-payload-field.json").writeText(field.toString())

        val shop = ShareLinkPayload.build(empty, start, "가나다식당", 30, ShareOptions(pay = true),
            pay = listOf(SharedPay(java.time.YearMonth.of(2026, 11), 18_450_000, "매출")), payKind = com.bamnat.core.work.MoneyShare.SALES)
        assertEquals(0, shop.getJSONArray("days").length())
        assertEquals("sales", shop.getString("payKind"))
        assertTrue(ShareLinkPayload.hasContent(shop))
        val read = SharedSchedule.parse(shop)
        assertEquals(com.bamnat.core.work.MoneyShare.SALES, read.payKind)
        assertEquals(18_450_000L, read.pay.single().amount)
        // The receiver sees the jobs where extra work shows.
        assertEquals(listOf("욕실 시공 가나다아파트", "일감"), SharedSchedule.parse(field).days.getValue(jobDay).extras.map { it.name })
        assertEquals(false, SharedSchedule.parse(field).isRest(jobDay))
    }

    @Test fun memoLineJoinsAndClips() {
        val a = com.bamnat.core.memo.Memo(date = start, title = "치과", body = "2시 예약")
        val b = com.bamnat.core.memo.Memo(date = start, body = "  저녁   약속\n7시 ")
        val blank = com.bamnat.core.memo.Memo(date = start)
        assertEquals("치과 · 저녁 약속", ShareLinkPayload.memoLine(listOf(a, b, blank)))
        assertEquals(null, ShareLinkPayload.memoLine(listOf(blank)))
        // A checklist sends its first item only, not every line on it.
        val list = com.bamnat.core.memo.Memo(date = start, isChecklist = true, checklist = listOf(
            com.bamnat.core.memo.ChecklistItem(" "), com.bamnat.core.memo.ChecklistItem("산부인과 검진"), com.bamnat.core.memo.ChecklistItem("대출 상환 300만원")))
        assertEquals("산부인과 검진", ShareLinkPayload.memoLine(listOf(list)))
        val long = ShareLinkPayload.memoLine(listOf(com.bamnat.core.memo.Memo(date = start, title = "가".repeat(100))))!!
        assertEquals(ShareLinkPayload.MEMO_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun pagesMonthsCoverTheLink() {
        assertEquals(listOf("2026-11", "2026-12", "2027-01"), ShareLinkPayload.months(start).map { it.toString() })
    }

    /** [SHR-02] The link id in what people paste: the message the app sends, the page address, or the app button's. */
    @Test fun findsTheLinkId() {
        val id = "Ab3dEfGh5JkLmNoPqRsTuv"
        assertEquals(id, ShareLinkId.find("민지의 근무표\nhttps://bamnat-share.example.workers.dev/s/$id"))
        assertEquals(id, ShareLinkId.find("https://bamnat-share.example.workers.dev/s/$id?x=1"))
        assertEquals(id, ShareLinkId.find("bamnat://s/$id"))
        assertEquals(null, ShareLinkId.find("https://bamnat-share.example.workers.dev/s/short"))
        assertEquals(null, ShareLinkId.find("https://bamnat-share.example.workers.dev/s/${id}${id}"))
        assertEquals(null, ShareLinkId.find("https://example.com/x/$id"))
        assertEquals(null, ShareLinkId.find(null))
    }

    /** [SHR-02][SHR-04] What a received link reads as: its shifts, what came with it, and the days off. */
    @Test fun receivedScheduleReadsBack() {
        val all = ShareOptions(leaveKinds = true, extras = true, memos = true, pay = true)
        val p = ShareLinkPayload.build(snapshot, start, "민지", 30, all, mapOf(start.plusDays(1) to "치과 2시"),
            listOf(SharedPay(java.time.YearMonth.of(2026, 11), 3_412_000, "세전 예상")))
        val s = SharedSchedule.parse(p)
        assertEquals("민지", s.title)
        assertEquals(setOf("leaveKinds", "extras", "memos", "pay"), s.shows)
        assertEquals(start, s.first)
        assertEquals(start.plusDays(ShareLinkPayload.DAYS - 1L), s.last)
        assertEquals("주간", s.type(s.days.getValue(start).type)?.name)
        assertEquals("치과 2시", s.days.getValue(start.plusDays(1)).memo)
        assertEquals("야간", s.type(s.days.getValue(start.plusDays(6)).second)?.name)
        assertEquals(listOf("야간 대근", "잔업"), s.days.getValue(start.plusDays(6)).extras.map { it.name })
        assertEquals(3_412_000L, s.pay.single().amount)
        assertEquals(false, s.isRest(start))
        assertEquals(true, s.isRest(start.plusDays(2)))
        assertEquals(true, s.isRest(start.plusDays(4)))
        assertEquals(false, s.isRest(start.plusDays(6)))
        assertEquals(null, s.isRest(start.minusDays(1)))
        val shift = s.type(1)!!.asShiftType()
        assertEquals(LocalTime.of(7, 0), shift.start)
        assertTrue(shift.id < 0)
    }

    /** [SHR-04] A link made before 1.10 has no rest marks: days off are worked out from the shifts. */
    @Test fun olderLinksWorkOutDaysOff() {
        val o = org.json.JSONObject("""{"v":1,"title":"","types":[{"id":1,"name":"주간","short":"주","color":"#1E88E5","time":"07:00-19:00","off":false},
            {"id":3,"name":"휴무","short":"휴","color":"#9E9E9E","time":null,"off":true}],
            "days":[{"date":"2026-11-01","type":1,"second":null,"leave":null},{"date":"2026-11-02","type":1,"second":null,"leave":"연차"},
            {"date":"2026-11-03","type":1,"second":null,"leave":"반차"},{"date":"2026-11-04","type":3,"second":1,"leave":null},
            {"date":"2026-11-06","type":3,"second":null,"leave":null}]}""")
        val s = SharedSchedule.parse(o)
        assertEquals(false, s.isRest(start))
        assertEquals(true, s.isRest(start.plusDays(1)))
        assertEquals(false, s.isRest(start.plusDays(2)))
        assertEquals(false, s.isRest(start.plusDays(3)))
        // A day inside the link with no entry at all had no shift.
        assertEquals(true, s.isRest(start.plusDays(4)))
        assertEquals(true, s.isRest(start.plusDays(5)))
        assertEquals(null, s.isRest(start.plusDays(6)))
        assertTrue(s.shows.isEmpty())
    }

    /** Runs against `npx wrangler dev` or server/share-worker/test/local-server.mjs when SHARE_TEST_URL points at it; skipped otherwise. */
    @Test fun roundTripAgainstTestServer() = runBlocking {
        val base = System.getenv("SHARE_TEST_URL").orEmpty()
        assumeTrue(base.isNotBlank())
        val client = ShareLinkClient(base)
        val made = client.create(ShareLinkPayload.build(snapshot, start, "민지", 7))
        assertTrue(made.url.startsWith(base.trimEnd('/') + "/s/"))
        val page = java.net.URL(made.url).readText()
        assertTrue(page.contains("민지의 근무표"))
        val updated = client.update(made, ShareLinkPayload.build(snapshot, start, "민지2", 30))
        assertTrue(java.net.URL(updated.url).readText().contains("민지2의 근무표"))
        // [SHR-02] The app reads the same link back for 받은 달력, with only what was ticked.
        val read = client.read(made.id)
        assertEquals("민지2", read.schedule.getString("title"))
        assertTrue(read.expiresAt.isAfter(java.time.Instant.now()))
        val v2 = client.update(updated, ShareLinkPayload.build(snapshot, start, "민지3", 30, ShareOptions(memos = true), mapOf(start to "치과")))
        assertEquals("치과", SharedSchedule.parse(client.read(v2.id).schedule).days.getValue(start).memo)
        client.delete(updated)
        client.delete(updated) // already gone: still fine
        assertEquals("not_found", runCatching { client.read(made.id) }.exceptionOrNull()?.message)
        val gone = (java.net.URL(made.url).openConnection() as java.net.HttpURLConnection).responseCode
        assertEquals(404, gone)
    }
}
