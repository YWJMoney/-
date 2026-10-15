package com.bamnat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShiftTypeEntity::class, PatternEntity::class, DayEntryEntity::class, MemoEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftTypeDao(): ShiftTypeDao
    abstract fun patternDao(): PatternDao
    abstract fun dayEntryDao(): DayEntryDao
    abstract fun memoDao(): MemoDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bamnat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** Version 2: several alarms per shift, hand-set alarms per day, memo trash. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shift_types ADD COLUMN alarmOffsets TEXT NOT NULL DEFAULT ''")
                // The single alarm of version 1 becomes the first item of the list.
                db.execSQL("UPDATE shift_types SET alarmOffsets = alarmOffsetMinutes || ':1'")
                db.execSQL("ALTER TABLE day_entries ADD COLUMN customAlarms TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memos ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memos_deletedAt ON memos (deletedAt)")
            }
        }

        /** Version 3: pattern history across team changes, and 잔업/특근/대근 on a day. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pattern ADD COLUMN anchorEpochDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE day_entries ADD COLUMN extraWork INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 4: shift icons [FEAT-01] and 연차·반차·반반차 on a day [FEAT-10]. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shift_types ADD COLUMN icon TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE day_entries ADD COLUMN leave INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
