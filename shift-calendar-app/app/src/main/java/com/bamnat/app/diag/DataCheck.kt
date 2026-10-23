package com.bamnat.app.diag

import com.bamnat.app.AppContainer
import com.bamnat.app.data.BackupArchive
import com.bamnat.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [QA-07] 330 (검토 4-5): 알람 점검 › 데이터 점검. The checks the release list's "휴대폰 옮기기" asks for, run by the
 * person on their own phone: nothing is changed, nothing leaves the phone.
 *
 * 1. Each database (내 달력's and every added calendar's) passes SQLite's own integrity check.
 * 2. No changed day points at a kind of work that is gone.
 * 3. Every added calendar in the list has its database file.
 * 4. A backup made now reads back the same (in memory and the cache folder, never written to a file the person sees).
 */
object DataCheck {
    data class Line(val ok: Boolean, val text: String)

    suspend fun run(c: AppContainer): List<Line> = withContext(Dispatchers.IO) {
        val out = ArrayList<Line>()
        val added = runCatching { c.calendars.added() }.getOrDefault(emptyList())
        val dbs = listOf("내 달력" to c.database) + added.mapNotNull { cal -> runCatching { cal.name to c.calendars.database(cal.id) }.getOrNull() }
        for ((name, db) in dbs) {
            val integrity = runCatching { integrity(db) }.getOrElse { "읽을 수 없음" }
            out += Line(integrity == "ok", "$name 데이터베이스: " + if (integrity == "ok") "이상 없음" else "문제 있음 ($integrity)")
            val orphans = runCatching { orphanDays(db) }.getOrDefault(-1)
            out += when {
                orphans == 0 -> Line(true, "$name 바뀐 날: 모두 있는 근무를 가리켜요")
                orphans > 0 -> Line(false, "$name 바뀐 날 ${orphans}개가 지운 근무를 가리켜요. 그날을 다시 정해 주세요")
                else -> Line(false, "$name 바뀐 날을 읽을 수 없어요")
            }
        }
        val missing = added.filter { cal -> !c.appContext.getDatabasePath(com.bamnat.app.data.CalendarStorage.fileName(cal.id)).isFile }
        out += if (missing.isEmpty()) {
            Line(true, "추가 달력 ${added.size}개: 파일이 모두 있어요")
        } else {
            Line(false, "추가 달력 파일이 없어요: " + missing.joinToString { it.name })
        }
        out += backupRoundTrip(c)
        out
    }

    private fun integrity(db: AppDatabase): String =
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cur -> if (cur.moveToFirst()) cur.getString(0) else "?" }

    /** Days set to a kind of work (override 2) whose kind is not in the list any more. */
    private fun orphanDays(db: AppDatabase): Int = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM day_entries WHERE overrideShiftTypeId IS NOT NULL AND overrideShiftTypeId NOT IN (SELECT id FROM shift_types)",
    ).use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }

    private suspend fun backupRoundTrip(c: AppContainer): Line = runCatching {
        val json = c.backupManager.export()
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, json, emptyList()) }.toByteArray()
        val dir = File(c.appContext.cacheDir, "data-check")
        val back = try {
            BackupArchive.read(bytes.inputStream(), dir).json
        } finally {
            dir.deleteRecursively()
        }
        fun body(s: String) = JSONObject(s).apply { remove("exportedAt") }.toString()
        val again = c.backupManager.export()
        if (body(back) == body(json) && body(again) == body(json)) Line(true, "백업 만들기·읽기: 이상 없음 (${bytes.size / 1024}KB)")
        else Line(false, "백업을 다시 읽으니 내용이 달라요. 문의로 알려 주세요")
    }.getOrElse { Line(false, "백업을 만들지 못했어요 (${it.javaClass.simpleName})") }
}
