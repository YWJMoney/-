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
        FixedCostEntity::class, CategoryRuleEntity::class, DayChangeEntity::class, SleepLogEntity::class,
        DayExtraEntity::class, ReceivableEntity::class, ReceivablePaymentEntity::class, ImportedEventEntity::class,
        MoneyAccountEntity::class, MoneyBudgetEntity::class, MoneyCategoryEntity::class,
        // [INV-01] Version 18: the investor calendar.
        InvestAccountEntity::class, InvestTradeEntity::class, InvestCashEntity::class, InvestQuoteEntity::class,
        InvestJournalEntity::class, InvestEventEntity::class, InvestAlarmEntity::class, InvestSnapshotEntity::class,
        InvestSettingEntity::class,
        // [LEAVE-01] Version 19: 연차 원장.
        LeaveLedgerEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shiftTypeDao(): ShiftTypeDao
    abstract fun patternDao(): PatternDao
    abstract fun dayEntryDao(): DayEntryDao
    abstract fun memoDao(): MemoDao
    abstract fun moneyDao(): MoneyDao
    abstract fun dayChangeDao(): DayChangeDao
    abstract fun sleepDao(): SleepDao
    abstract fun dayExtraDao(): DayExtraDao
    abstract fun receivableDao(): ReceivableDao
    abstract fun importedEventDao(): ImportedEventDao
    abstract fun investDao(): InvestDao
    abstract fun leaveLedgerDao(): LeaveLedgerDao

    companion object {
        /** [MCAL-01] Each added calendar is a database of its own with the same tables; only its schedule ones are used. */
        fun create(context: Context, name: String = "bamnat.db"): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()

        /**
         * [REL-01] Every step from version 1 on, in order: the app and the migration tests read this one
         * list, so a new version's migration added here is tested from every older version at once.
         */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
            )

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

        /** Version 10: what changed on each day and when [MKT-08]. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_changes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`atMillis` INTEGER NOT NULL, `text` TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_changes_epochDay` ON `day_changes` (`epochDay`)")
            }
        }

        /** Version 11: the sleep log [SLP-06]. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sleep_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_logs_startMillis` ON `sleep_logs` (`startMillis`)")
            }
        }

        /** Version 12: extra work as pieces with their own times [EXTRA-01, EXTRA-05]. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_extras` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`kindId` INTEGER NOT NULL, `startMinute` INTEGER, `endMinute` INTEGER, `breakMinutes` INTEGER NOT NULL DEFAULT 0, " +
                        "`breakStartMinute` INTEGER, `minutes` INTEGER NOT NULL DEFAULT 0, `coveredShiftTypeId` INTEGER, " +
                        "`needsReview` INTEGER NOT NULL DEFAULT 0, `position` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_day_extras_epochDay` ON `day_extras` (`epochDay`)")
                moveOldExtraWork(db)
            }
        }

        /** Version 13: a memo can be a schedule that rings or notifies [SCHED-01]. Old memos stay plain. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN remindAt TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE memos ADD COLUMN remindRing INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memos ADD COLUMN remindNotify INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memos ADD COLUMN remindAsked TEXT DEFAULT NULL")
            }
        }

        /** Version 14: money others owe the user [RECV-01] and events brought in from the phone's calendar [IMPORT-01]. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `receivables` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `who` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `amount` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `dueEpochDay` INTEGER, " +
                        "`kind` TEXT NOT NULL, `note` TEXT NOT NULL, `countAsIncome` INTEGER NOT NULL, `closedEpochDay` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `receivable_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`receivableId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `entryId` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_receivable_payments_receivableId` ON `receivable_payments` (`receivableId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `imported_events` (`eventKey` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                        "`memoId` INTEGER, `importedAt` INTEGER NOT NULL, PRIMARY KEY(`eventKey`))",
                )
            }
        }

        /**
         * Version 15: the ledger grows up [LEDG-06]..[LEDG-14]. Lines get an account, a transfer
         * target, a memo, tags, split parts, installments and the repeating line they came from;
         * repeating lines get weekly and yearly repeats, an account, writing themselves down and a
         * first and last day; accounts, budgets and the user's categories get tables of their own.
         * Card payments read before keep their installments, which were only in the note.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE money_entries ADD COLUMN accountId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN toAccountId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN memo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN parts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN installments INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN fixedId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN repeat TEXT NOT NULL DEFAULT 'MONTH'")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN monthOfYear INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN accountId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN startEpochDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE fixed_costs ADD COLUMN endEpochDay INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `money_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `last4` TEXT NOT NULL, `payDay` INTEGER, `closeDay` INTEGER, `payFromId` INTEGER, " +
                        "`opening` INTEGER NOT NULL, `color` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, " +
                        "`remind` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `money_budgets` (`category` TEXT NOT NULL, `amount` INTEGER NOT NULL, PRIMARY KEY(`category`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `money_categories` (`kind` TEXT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`hidden` INTEGER NOT NULL, PRIMARY KEY(`kind`, `name`))",
                )
                // "3개월 할부" was written into the note by the payment reader before version 15.
                db.query("SELECT id, note FROM money_entries WHERE note LIKE '%개월 할부%'").use { c ->
                    while (c.moveToNext()) {
                        val months = INSTALLMENTS.find(c.getString(1) ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: continue
                        if (months > 1) db.execSQL("UPDATE money_entries SET installments = ? WHERE id = ?", arrayOf<Any>(months.coerceAtMost(60), c.getLong(0)))
                    }
                }
            }
        }

        /**
         * Version 16 [NOTE-01]: a memo names the calendars it shows on. Every memo written before
         * gets '' and belongs to 내 달력 alone ([com.bamnat.core.memo.Memo.owners]), where it always was.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN calendars TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Version 17: what the research for each kind of work asked for — attendance and swaps on days,
         * a kind of day's own premium, a job's status, 공수 and due day, a shop's day by payment method,
         * cash count and card settlement, proof and receipt photo on a line, and repeating memos.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_entries ADD COLUMN attendance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE day_entries ADD COLUMN swapWith TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shift_types ADD COLUMN premiumPercent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN status INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE jobs ADD COLUMN units REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN dueEpochDay INTEGER DEFAULT NULL")
                for (c in listOf("card", "cash", "transfer", "delivery")) {
                    db.execSQL("ALTER TABLE daily_sales ADD COLUMN $c INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE daily_sales ADD COLUMN cashCounted INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE daily_sales ADD COLUMN cardSettled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN proof TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE money_entries ADD COLUMN receipt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memos ADD COLUMN repeat TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Version 18 [INV-01]: the investor calendar's tables. Only new tables: every row of version 17
         * keeps its meaning, and a calendar of another kind leaves them empty.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                INVEST_TABLES.forEach(db::execSQL)
            }
        }

        /**
         * Version 19 [LEAVE-01][PAYX-18]: the 연차 원장 table, and a mark on extra work paid in 보상휴가. Every row
         * of version 18 keeps its meaning (no piece is 보상휴가 until the user says so). The 연차 the pay setup held
         * moves to the new table once, from the app ([com.bamnat.app.leave.LeaveRepository]), since it lives in a file.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `leave_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `days` REAL NOT NULL, `expiresEpochDay` INTEGER, `yearStartEpochDay` INTEGER, " +
                    "`note` TEXT NOT NULL DEFAULT '', `pool` TEXT NOT NULL DEFAULT 'ANNUAL', `createdAt` INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_leave_ledger_epochDay` ON `leave_ledger` (`epochDay`)")
                db.execSQL("ALTER TABLE day_extras ADD COLUMN compLeave INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** As Room writes them in schemas/18.json, so a migrated database matches a fresh one. */
        private val INVEST_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `invest_accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `broker` TEXT NOT NULL, `feeRate` REAL, `sortOrder` INTEGER NOT NULL, `hidden` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `invest_trades` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `minuteOfDay` INTEGER, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `market` TEXT NOT NULL, `side` TEXT NOT NULL, `quantity` REAL NOT NULL, `price` REAL NOT NULL, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, `fee` REAL NOT NULL, `tax` REAL NOT NULL, `reason` TEXT NOT NULL, `emotions` TEXT NOT NULL, `tags` TEXT NOT NULL, `followedRules` INTEGER, `memo` TEXT NOT NULL, `alarmId` INTEGER, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_invest_trades_epochDay` ON `invest_trades` (`epochDay`)",
            "CREATE TABLE IF NOT EXISTS `invest_cash` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `minuteOfDay` INTEGER, `kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `tax` INTEGER NOT NULL, `symbol` TEXT NOT NULL, `memo` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_invest_cash_epochDay` ON `invest_cash` (`epochDay`)",
            "CREATE TABLE IF NOT EXISTS `invest_quotes` (`symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `market` TEXT NOT NULL, `price` REAL NOT NULL, `epochDay` INTEGER NOT NULL, PRIMARY KEY(`symbol`))",
            "CREATE TABLE IF NOT EXISTS `invest_journal` (`epochDay` INTEGER NOT NULL, `market` TEXT NOT NULL, `review` TEXT NOT NULL, `checked` TEXT NOT NULL, `grade` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
            "CREATE TABLE IF NOT EXISTS `invest_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epochDay` INTEGER NOT NULL, `kind` TEXT NOT NULL, `symbol` TEXT NOT NULL, `title` TEXT NOT NULL, `memo` TEXT NOT NULL, `remind` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_invest_events_epochDay` ON `invest_events` (`epochDay`)",
            "CREATE TABLE IF NOT EXISTS `invest_alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `minuteOfDay` INTEGER NOT NULL, `weekdays` TEXT NOT NULL, `monthDay` INTEGER, `onceEpochDay` INTEGER, `skipHolidays` INTEGER NOT NULL, `symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `market` TEXT NOT NULL, `accountId` INTEGER, `side` TEXT NOT NULL, `quantity` REAL, `amount` INTEGER, `targetPrice` REAL, `enabled` INTEGER NOT NULL, `ring` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `invest_snapshots` (`epochDay` INTEGER NOT NULL, `value` INTEGER NOT NULL, `principal` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
            "CREATE TABLE IF NOT EXISTS `invest_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )

        private val INSTALLMENTS = Regex("(\\d{1,2})개월 할부")

        /**
         * [EXTRA-05] Moves version 11 extra work (still in day_entries) into day_extras; also run on a
         * restored backup from 1.8 or earlier. A second shift becomes a piece with that shift's hours
         * (대근 unless marked otherwise), copied so a later change to the type leaves the day as it was;
         * minutes on a working day and on a day off become pieces of their length; a mark with no hours
         * becomes an empty piece to check. Pieces that may hold hours nobody meant (a chip's default
         * hours next to a second shift) are marked for review. The old columns are 0 afterwards.
         */
        fun moveOldExtraWork(db: SupportSQLiteDatabase) {
            val kind = { fallback: Int -> "CASE WHEN d.extraWork BETWEEN 1 AND 3 THEN d.extraWork ELSE $fallback END" }
            db.execSQL(
                "INSERT INTO day_extras (epochDay, kindId, startMinute, endMinute, breakMinutes, breakStartMinute, coveredShiftTypeId, position) " +
                    "SELECT d.epochDay, ${kind(3)}, s.startMinute, s.endMinute, s.breakMinutes, s.breakStartMinute, d.secondShiftTypeId, 0 " +
                    "FROM day_entries d JOIN shift_types s ON s.id = d.secondShiftTypeId " +
                    "WHERE s.isOff = 0 AND s.startMinute IS NOT NULL AND s.endMinute IS NOT NULL",
            )
            db.execSQL(
                "INSERT INTO day_extras (epochDay, kindId, minutes, needsReview, position) " +
                    "SELECT d.epochDay, ${kind(1)}, d.overtimeMinutes, " +
                    "CASE WHEN d.secondShiftTypeId IS NOT NULL AND d.extraWork IN (2, 3) THEN 1 ELSE 0 END, 1 " +
                    "FROM day_entries d WHERE d.overtimeMinutes > 0",
            )
            db.execSQL(
                "INSERT INTO day_extras (epochDay, kindId, minutes, needsReview, position) " +
                    "SELECT d.epochDay, ${kind(2)}, d.holidayWorkMinutes, " +
                    "CASE WHEN d.secondShiftTypeId IS NOT NULL THEN 1 ELSE 0 END, 2 " +
                    "FROM day_entries d WHERE d.holidayWorkMinutes > 0",
            )
            // A mark with no hours at all: kept so the calendar still shows it, and asked about once.
            db.execSQL(
                "INSERT INTO day_extras (epochDay, kindId, minutes, needsReview, position) " +
                    "SELECT d.epochDay, d.extraWork, 0, 1, 0 FROM day_entries d " +
                    "WHERE d.extraWork BETWEEN 1 AND 3 AND d.epochDay NOT IN (SELECT epochDay FROM day_extras)",
            )
            db.execSQL(
                "UPDATE day_entries SET overtimeMinutes = 0, holidayWorkMinutes = 0, extraWork = 0, secondShiftTypeId = NULL " +
                    "WHERE overtimeMinutes != 0 OR holidayWorkMinutes != 0 OR extraWork != 0 OR secondShiftTypeId IS NOT NULL",
            )
            // Rows that held nothing but extra work are now empty; their work lives in day_extras.
            db.execSQL(
                "DELETE FROM day_entries WHERE overrideMode = 0 AND customStartMinute IS NULL AND customEndMinute IS NULL " +
                    "AND customBreakMinutes IS NULL AND alarmSkipped = 0 AND customAlarms IS NULL AND leave = 0",
            )
        }
    }
}
