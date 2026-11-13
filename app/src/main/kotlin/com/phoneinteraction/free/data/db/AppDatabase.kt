package com.phoneinteraction.free.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phoneinteraction.free.data.model.ActionEntity
import com.phoneinteraction.free.data.model.ActionQueueEntity
import com.phoneinteraction.free.data.model.ActionStepEntity
import com.phoneinteraction.free.data.model.ExecutionRecordEntity
import com.phoneinteraction.free.data.model.QueueItemEntity
import com.phoneinteraction.free.data.model.QueueRunStateEntity
import com.phoneinteraction.free.data.model.RuleEntity

@Database(
    entities = [
        ActionEntity::class,
        ActionStepEntity::class,
        ActionQueueEntity::class,
        QueueItemEntity::class,
        RuleEntity::class,
        ExecutionRecordEntity::class,
        QueueRunStateEntity::class
    ],
    version = 4,
    // Export schema for compile-time validation with room.schemaLocation
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun actionDao(): ActionDao
    abstract fun queueDao(): QueueDao
    abstract fun ruleDao(): RuleDao
    abstract fun executionRecordDao(): ExecutionRecordDao
    abstract fun queueRunStateDao(): QueueRunStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v2: add action.repeatCount, backfill 1 for existing data
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE action ADD COLUMN repeatCount INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        // v3: add queue-level loopCount and startDelay
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE action_queue ADD COLUMN loopCount INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE action_queue ADD COLUMN startDelay INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v4: index executionTime for efficient trim-to-N queries
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_execution_record_executionTime " +
                        "ON execution_record (executionTime)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phone_interaction.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
