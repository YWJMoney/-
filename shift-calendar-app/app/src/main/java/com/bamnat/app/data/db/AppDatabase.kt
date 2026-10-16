package com.bamnat.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShiftTypeEntity::class, PatternEntity::class, DayEntryEntity::class, MemoEntity::class,
        MoneyEntryEntity::class, JobEntity::class, DailySalesEntity::class,
        FixedCostEntity::class, CategoryRuleEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftTypeDao(): ShiftTypeDao
    abstract fun patternDao(): PatternDao
    abstract fun dayEntryDao(): DayEntryDao
    abstract fun memoDao(): MemoDao
    abstract fun moneyDao(): MoneyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bamnat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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

        /** Version 5: a second shift on one day [FEAT-02]. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_entries ADD COLUMN secondShiftTypeId INTEGER DEFAULT NULL")
            }
        }

        /** Version 6: when a shift's break starts, for exact night minutes [PAY-01]. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shift_types ADD COLUMN breakStartMinute INTEGER DEFAULT NULL")
            }
        }

        /** Version 7: household ledger, field jobs and shop sales [WORK-06][LEDG-01][WORK-02][WORK-03]. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `money_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `category` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entries_epochDay` ON `money_entries` (`epochDay`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, `client` TEXT NOT NULL, `place` TEXT NOT NULL, `startMinute` INTEGER, `endMinute` INTEGER, " +
                        "`amount` INTEGER NOT NULL, `deposit` INTEGER NOT NULL, `depositPaid` INTEGER NOT NULL, " +
                        "`balancePaid` INTEGER NOT NULL, `remindMinutes` INTEGER, `note` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_epochDay` ON `jobs` (`epochDay`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_sales` (`epochDay` INTEGER NOT NULL, `sales` INTEGER NOT NULL, " +
                        "`costs` INTEGER NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`epochDay`))",
                )
            }
        }

        /** Version 8: jobs over several days, job costs and 3.3% withholding [WORK-02]. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN endEpochDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN costs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN withholding INTEGER NOT NULL DEFAULT 0")
            }
        }
    
        /** Version 9: payment texts read into the ledger, fixed costs, learned categories [LEDG-02..05]. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE money_entries ADD COLUMN merchant TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN minuteOfDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN cardLast4 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fixed_costs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`amount` INTEGER NOT NULL, `day` INTEGER NOT NULL, `kind` TEXT NOT NULL, `category` TEXT NOT NULL, `remind` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `category_rules` (`merchant` TEXT NOT NULL, `category` TEXT NOT NULL, PRIMARY KEY(`merchant`))")
            }
        }
    }
}
