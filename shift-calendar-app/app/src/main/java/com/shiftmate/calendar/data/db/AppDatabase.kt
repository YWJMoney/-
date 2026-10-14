package com.shiftmate.calendar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShiftTypeEntity::class, PatternEntity::class, DayEntryEntity::class, MemoEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftTypeDao(): ShiftTypeDao
    abstract fun patternDao(): PatternDao
    abstract fun dayEntryDao(): DayEntryDao
    abstract fun memoDao(): MemoDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "shiftmate.db")
                .addMigrations(MIGRATION_1_2)
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
    }
}
