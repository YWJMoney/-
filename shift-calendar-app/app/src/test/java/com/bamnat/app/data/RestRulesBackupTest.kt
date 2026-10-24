package com.bamnat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.core.schedule.RestRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** [REST-01] The 근무표 점검 기준 is kept in the backup, and the old '더 엄격하게' reads as its preset. */
@RunWith(AndroidJUnit4::class)
class RestRulesBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    @After fun close() = db.close()

    @Test fun rulesSurviveTheBackup() = runBlocking {
        val settings = SettingsRepository(context)
        val backups = BackupManager(db, settings)
        ScheduleRepository(db).ensureDefaults()
        val mine = RestRules(minRestHours = 12, offsAfterNights = 1, maxNightsInRow = 4, maxWorkDaysInRow = 5, maxNightsPerMonth = 10)
        settings.update { it.copy(restRules = mine.encode(), restCheck = false) }
        val json = backups.export()
        settings.update { it.copy(restRules = "", restCheck = true) }
        backups.restore(json)
        assertEquals(mine.copy(on = false), settings.current.rules)
        assertFalse(settings.current.rules.on)
    }

    @Test fun oldStrictBecomesThePreset() {
        assertEquals(RestRules.Preset.STRICT, AppSettings(strictRest = true).rules.preset)
        assertEquals(RestRules.Preset.BASIC, AppSettings().rules.preset)
    }
}
