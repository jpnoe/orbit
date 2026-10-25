package com.orbit.wellness.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MoodSample::class,
        EmotionOption::class,
        DailyJournal::class,
        ActivityEvent::class,
        ActivityOption::class,
        MealEvent::class,
        MealItem::class,
        FoodOption::class,
        WorkoutEvent::class,
        WorkoutOption::class,
        SubstanceUseEvent::class,
        SubstanceOption::class,
        SleepEvent::class,
        TrustedContact::class,
        StatusRequest::class,
        ShareEvent::class,
        BiometricSnapshot::class,
        StateVectorSnapshot::class,
        TopologyNode::class,
        TopologyEdge::class,
        ClusterMeta::class,
        TopologyState::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class OrbitDatabase : RoomDatabase() {
    abstract fun moodSampleDao(): MoodSampleDao
    abstract fun emotionOptionDao(): EmotionOptionDao
    abstract fun dailyJournalDao(): DailyJournalDao
    abstract fun activityEventDao(): ActivityEventDao
    abstract fun activityOptionDao(): ActivityOptionDao
    abstract fun mealEventDao(): MealEventDao
    abstract fun mealItemDao(): MealItemDao
    abstract fun foodOptionDao(): FoodOptionDao
    abstract fun workoutEventDao(): WorkoutEventDao
    abstract fun workoutOptionDao(): WorkoutOptionDao
    abstract fun substanceUseEventDao(): SubstanceUseEventDao
    abstract fun substanceOptionDao(): SubstanceOptionDao
    abstract fun sleepEventDao(): SleepEventDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun statusRequestDao(): StatusRequestDao
    abstract fun shareEventDao(): ShareEventDao
    abstract fun biometricSnapshotDao(): BiometricSnapshotDao
    abstract fun stateVectorSnapshotDao(): StateVectorSnapshotDao
    abstract fun topologyNodeDao(): TopologyNodeDao
    abstract fun topologyEdgeDao(): TopologyEdgeDao
    abstract fun clusterMetaDao(): ClusterMetaDao
    abstract fun topologyStateDao(): TopologyStateDao

    companion object {
        private const val DB_NAME = "orbit_encrypted.db"

        private val DEFAULT_ACTIVITIES = listOf(
            "Walk",
            "Work",
            "Read",
            "Rest",
            "Social",
        )

        private val DEFAULT_FOODS = listOf(
            FoodSeed("Chicken", 165.0, 31.0, 3.6, 0.0, 150),
            FoodSeed("Rice", 130.0, 2.7, 0.3, 28.0, 150),
            FoodSeed("Eggs", 143.0, 13.0, 9.5, 0.7, 100),
            FoodSeed("Oatmeal", 389.0, 17.0, 7.0, 66.0, 40),
            FoodSeed("Salad", 25.0, 1.5, 0.2, 5.0, 100),
            FoodSeed("Banana", 89.0, 1.1, 0.3, 23.0, 118),
            FoodSeed("Apple", 52.0, 0.3, 0.2, 14.0, 182),
            FoodSeed("Berries", 57.0, 0.7, 0.3, 14.0, 100),
        )

        // Coping starters only — supportive meds are personal (My lists).
        private val DEFAULT_COPING = listOf(
            SubstanceSeed("Cigarettes", "1"),
            SubstanceSeed("Weed", ""),
            SubstanceSeed("Alcohol", "1 drink"),
        )

        fun create(context: Context, keyManager: DatabaseKeyManager): OrbitDatabase {
            System.loadLibrary("sqlcipher")

            val passphrase = keyManager.getPassphrase()
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                OrbitDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(factory)
                .addMigrations(*OrbitMigrations.ALL)
                // Only wipe if somehow still on a pre-v10 schema we no longer migrate.
                // Never wipe 10→current — preserves logging during active use.
                .fallbackToDestructiveMigrationFrom(
                    dropAllTables = true,
                    1, 2, 3, 4, 5, 6, 7, 8, 9,
                )
                .build()
        }

        suspend fun ensureDefaultActivities(dao: ActivityOptionDao) {
            if (dao.count() > 0) return
            val options = DEFAULT_ACTIVITIES.mapIndexed { index, name ->
                ActivityOption(
                    name = name,
                    sortOrder = index,
                    isCustom = false,
                )
            }
            dao.insertAll(options)
        }

        suspend fun ensureDefaultFoods(dao: FoodOptionDao) {
            if (dao.count() > 0) return
            val options = DEFAULT_FOODS.mapIndexed { index, seed ->
                FoodOption(
                    name = seed.name,
                    sortOrder = index,
                    isCustom = false,
                    caloriesPer100g = seed.calories,
                    proteinPer100g = seed.protein,
                    fatPer100g = seed.fat,
                    carbsPer100g = seed.carbs,
                    defaultWeightGrams = seed.defaultWeight,
                )
            }
            dao.insertAll(options)
        }

        suspend fun ensureDefaultSubstances(dao: SubstanceOptionDao) {
            if (dao.count() > 0) return
            val options = DEFAULT_COPING.mapIndexed { index, seed ->
                SubstanceOption(
                    name = seed.name,
                    intent = SubstanceIntent.COPING.name,
                    defaultAmount = seed.defaultAmount,
                    sortOrder = index,
                    isCustom = false,
                )
            }
            dao.insertAll(options)
        }

        private val DEFAULT_WORKOUT_TYPES = listOf(
            "Cardio",
            "Strength",
            "Yoga / Balance",
        )

        suspend fun ensureDefaultWorkoutTypes(dao: WorkoutOptionDao) {
            if (dao.count() > 0) return
            val options = DEFAULT_WORKOUT_TYPES.mapIndexed { index, name ->
                WorkoutOption(
                    name = name,
                    sortOrder = index,
                    isCustom = false,
                )
            }
            dao.insertAll(options)
        }

        private val DEFAULT_EMOTIONS = listOf(
            "Calm",
            "Grateful",
            "Joyful",
            "Anxious",
            "Overwhelmed",
            "Sad",
            "Frustrated",
            "Content",
            "Hopeful",
        )

        suspend fun ensureDefaultEmotions(dao: EmotionOptionDao) {
            if (dao.count() > 0) return
            val options = DEFAULT_EMOTIONS.mapIndexed { index, name ->
                EmotionOption(
                    name = name,
                    sortOrder = index,
                    isCustom = false,
                )
            }
            dao.insertAll(options)
        }

        private data class FoodSeed(
            val name: String,
            val calories: Double,
            val protein: Double,
            val fat: Double,
            val carbs: Double,
            val defaultWeight: Int,
        )

        private data class SubstanceSeed(
            val name: String,
            val defaultAmount: String,
        )
    }
}
