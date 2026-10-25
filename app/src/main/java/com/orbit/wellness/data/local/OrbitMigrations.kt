package com.orbit.wellness.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object OrbitMigrations {
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE mood_samples ADD COLUMN emotion TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS emotion_options (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    isCustom INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Preserve existing grams as the displayed amount; unit defaults to g.
            db.execSQL(
                "ALTER TABLE meal_items ADD COLUMN amountValue INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE meal_items ADD COLUMN amountUnit TEXT NOT NULL DEFAULT 'g'",
            )
            db.execSQL(
                "UPDATE meal_items SET amountValue = weightGrams",
            )
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Decimal amounts for grams / fl oz (and derived weightGrams totals).
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS meal_items_new (
                    id TEXT NOT NULL,
                    mealId TEXT NOT NULL,
                    foodName TEXT NOT NULL,
                    weightGrams REAL NOT NULL,
                    amountValue REAL NOT NULL,
                    amountUnit TEXT NOT NULL,
                    calories REAL,
                    proteinGrams REAL,
                    fatGrams REAL,
                    carbsGrams REAL,
                    barcode TEXT,
                    source TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(mealId) REFERENCES meal_events(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO meal_items_new (
                    id, mealId, foodName, weightGrams, amountValue, amountUnit,
                    calories, proteinGrams, fatGrams, carbsGrams, barcode, source, sortOrder
                )
                SELECT
                    id, mealId, foodName, weightGrams, amountValue, amountUnit,
                    calories, proteinGrams, fatGrams, carbsGrams, barcode, source, sortOrder
                FROM meal_items
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE meal_items")
            db.execSQL("ALTER TABLE meal_items_new RENAME TO meal_items")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_meal_items_mealId ON meal_items(mealId)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS meal_events_new (
                    id TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    summaryTitle TEXT NOT NULL,
                    itemCount INTEGER NOT NULL,
                    totalWeightGrams REAL NOT NULL,
                    calories REAL,
                    proteinGrams REAL,
                    fatGrams REAL,
                    carbsGrams REAL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO meal_events_new (
                    id, timestamp, label, summaryTitle, itemCount,
                    totalWeightGrams, calories, proteinGrams, fatGrams, carbsGrams
                )
                SELECT
                    id, timestamp, label, summaryTitle, itemCount,
                    totalWeightGrams, calories, proteinGrams, fatGrams, carbsGrams
                FROM meal_events
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE meal_events")
            db.execSQL("ALTER TABLE meal_events_new RENAME TO meal_events")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_meal_events_timestamp ON meal_events(timestamp)",
            )
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE workout_events ADD COLUMN startTime INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE workout_events ADD COLUMN endTime INTEGER",
            )
            db.execSQL(
                "ALTER TABLE workout_events ADD COLUMN preMood INTEGER",
            )
            db.execSQL(
                "ALTER TABLE workout_events ADD COLUMN postMood INTEGER",
            )
            // Existing one-shot logs: treat timestamp as both start and end.
            db.execSQL(
                "UPDATE workout_events SET startTime = timestamp, endTime = timestamp",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_workout_events_startTime ON workout_events(startTime)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_workout_events_endTime ON workout_events(endTime)",
            )
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS biometric_snapshots (
                    id TEXT NOT NULL,
                    syncedAt INTEGER NOT NULL,
                    restingHeartRateBpm REAL,
                    stepsToday INTEGER,
                    sleepHoursLast REAL,
                    source TEXT NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_biometric_snapshots_syncedAt ON biometric_snapshots(syncedAt)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS state_vector_snapshots (
                    id TEXT NOT NULL,
                    recordedAt INTEGER NOT NULL,
                    moodCurrent REAL NOT NULL,
                    moodTrend1h REAL NOT NULL,
                    sleepHoursLast REAL NOT NULL,
                    rhrCurrent REAL NOT NULL,
                    stepsToday REAL NOT NULL,
                    activityLoad24h REAL NOT NULL,
                    workoutLoad24h REAL NOT NULL,
                    supportiveCount24h REAL NOT NULL,
                    copingCount24h REAL NOT NULL,
                    mealRegularity REAL NOT NULL,
                    presentMask INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_state_vector_snapshots_recordedAt ON state_vector_snapshots(recordedAt)",
            )
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS topology_nodes (
                    snapshotId TEXT NOT NULL,
                    recordedAt INTEGER NOT NULL,
                    coreDistance REAL NOT NULL,
                    clusterId TEXT,
                    isExhaust INTEGER NOT NULL,
                    PRIMARY KEY(snapshotId),
                    FOREIGN KEY(snapshotId) REFERENCES state_vector_snapshots(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_topology_nodes_recordedAt ON topology_nodes(recordedAt)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_topology_nodes_clusterId ON topology_nodes(clusterId)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS topology_edges (
                    id TEXT NOT NULL,
                    fromId TEXT NOT NULL,
                    toId TEXT NOT NULL,
                    distance REAL NOT NULL,
                    layer INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_topology_edges_fromId ON topology_edges(fromId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_topology_edges_toId ON topology_edges(toId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_topology_edges_layer ON topology_edges(layer)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cluster_meta (
                    id TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    moodCurrent REAL NOT NULL,
                    moodTrend1h REAL NOT NULL,
                    sleepHoursLast REAL NOT NULL,
                    rhrCurrent REAL NOT NULL,
                    stepsToday REAL NOT NULL,
                    activityLoad24h REAL NOT NULL,
                    workoutLoad24h REAL NOT NULL,
                    supportiveCount24h REAL NOT NULL,
                    copingCount24h REAL NOT NULL,
                    mealRegularity REAL NOT NULL,
                    lastUpdated INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS topology_state (
                    id TEXT NOT NULL,
                    lambdaCap REAL NOT NULL,
                    lastCompactedAt INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
    )
}
