package com.orbit.wellness.data

import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.OrbitMigrations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CheckInRepositoryTest {

    @Test
    fun saveCheckInRejectsBlankTrigger() {
        val dao = InMemoryCheckInDao()
        val repository = CheckInRepository(dao)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveCheckIn(
                    axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.MOOD, 4)),
                    trigger = "   ",
                    recordedAt = 0L,
                )
            }
        }
        assertTrue(error.message!!.contains("trigger"))
        assertNull(dao.inserted)
    }

    @Test
    fun saveCheckInAllowsFutureNonblankTriggerNames() {
        val dao = InMemoryCheckInDao()
        val repository = CheckInRepository(dao)
        val saved = runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 3)),
                trigger = "after_workout",
                recordedAt = 1_700_000_000_000L,
            )
        }
        assertEquals("after_workout", saved.record.trigger)
        assertEquals("after_workout", dao.inserted?.trigger)
    }

    @Test
    fun migration21To22IsRegistered() {
        val migration = OrbitMigrations.ALL.single { it.startVersion == 21 && it.endVersion == 22 }
        assertEquals(21, migration.startVersion)
        assertEquals(22, migration.endVersion)
    }

    @Test
    fun exportedSchema22AddsCheckInEmotionWithoutRewritingExistingRows() {
        val current = schemaFile("22.json").readText()
        val previous = schemaFile("21.json").readText()
        assertTrue(current.contains("\"version\": 22"))
        assertEquals(createSql(previous, "mood_samples"), createSql(current, "mood_samples"))
        assertEquals(createSql(previous, "check_in_axis_values"), createSql(current, "check_in_axis_values"))
        assertEquals(createSql(previous, "check_in_context_windows"), createSql(current, "check_in_context_windows"))
        val previousCheckIns = createSql(previous, "check_ins")
        val checkIns = createSql(current, "check_ins")
        assertTrue(!previousCheckIns.contains("`emotion`"))
        assertTrue(checkIns.contains("`emotion` TEXT NOT NULL"))
        assertTrue(checkIns.startsWith(previousCheckIns.removeSuffix("PRIMARY KEY(`id`))")))
    }

    @Test
    fun migration20To21IsRegistered() {
        val migration = OrbitMigrations.ALL.single { it.startVersion == 20 && it.endVersion == 21 }
        assertEquals(20, migration.startVersion)
        assertEquals(21, migration.endVersion)
    }

    @Test
    fun exportedSchema21AddsContextualMealsWithoutChangingMealEvents() {
        val current = schemaFile("21.json").readText()
        val previous = schemaFile("20.json").readText()
        assertTrue(current.contains("\"version\": 21"))
        assertEquals(createSql(previous, "meal_events"), createSql(current, "meal_events"))
        assertEquals(createSql(previous, "meal_items"), createSql(current, "meal_items"))
        assertEquals(createSql(previous, "check_in_usage_items"), createSql(current, "check_in_usage_items"))
        val meals = createSql(current, "check_in_meals")
        assertTrue(meals.contains("REFERENCES `check_in_context_windows`(`checkInId`)"))
        assertTrue(meals.contains("ON DELETE CASCADE"))
        assertTrue(meals.contains("`size` TEXT NOT NULL"))
        assertTrue(meals.contains("`sortOrder` INTEGER NOT NULL"))
        val foods = createSql(current, "check_in_meal_food_items")
        assertTrue(foods.contains("REFERENCES `check_in_meals`(`id`)"))
        assertTrue(foods.contains("ON DELETE CASCADE"))
        assertTrue(foods.contains("`foodName` TEXT NOT NULL"))
        assertTrue(foods.contains("`foodOptionId` TEXT"))
        assertTrue(!foods.contains("food_options"))
    }

    @Test
    fun migration19To20IsRegistered() {
        val migration = OrbitMigrations.ALL.single { it.startVersion == 19 && it.endVersion == 20 }
        assertEquals(19, migration.startVersion)
        assertEquals(20, migration.endVersion)
    }

    @Test
    fun exportedSchema20AddsContextualUsageWithoutChangingSubstanceEvents() {
        val current = schemaFile("20.json").readText()
        val previous = schemaFile("19.json").readText()
        assertTrue(current.contains("\"version\": 20"))
        assertEquals(createSql(previous, "substance_use_events"), createSql(current, "substance_use_events"))
        assertEquals(createSql(previous, "check_in_activity_items"), createSql(current, "check_in_activity_items"))
        assertEquals(createSql(previous, "check_in_workout_items"), createSql(current, "check_in_workout_items"))
        val items = createSql(current, "check_in_usage_items")
        assertTrue(items.contains("REFERENCES `check_in_context_windows`(`checkInId`)"))
        assertTrue(items.contains("ON DELETE CASCADE"))
        assertTrue(items.contains("`intent` TEXT NOT NULL"))
        assertTrue(items.contains("`amount` TEXT NOT NULL"))
        assertTrue(items.contains("`substanceOptionId` TEXT"))
        assertTrue(!items.contains("substance_options"))
    }

    @Test
    fun migration18To19IsRegistered() {
        val migration = OrbitMigrations.ALL.single { it.startVersion == 18 && it.endVersion == 19 }
        assertEquals(18, migration.startVersion)
        assertEquals(19, migration.endVersion)
    }

    @Test
    fun exportedSchema19AddsContextualWorkoutsWithoutChangingWorkoutEvents() {
        val current = schemaFile("19.json").readText()
        val previous = schemaFile("18.json").readText()
        assertTrue(current.contains("\"version\": 19"))
        assertEquals(createSql(previous, "workout_events"), createSql(current, "workout_events"))
        assertEquals(createSql(previous, "check_in_activity_items"), createSql(current, "check_in_activity_items"))
        val items = createSql(current, "check_in_workout_items")
        assertTrue(items.contains("REFERENCES `check_in_context_windows`(`checkInId`)"))
        assertTrue(items.contains("ON DELETE CASCADE"))
        assertTrue(items.contains("`durationMinutes` INTEGER NOT NULL"))
        assertTrue(!items.contains("workout_options"))
    }

    @Test
    fun migration17To18IsRegistered() {
        val migration = OrbitMigrations.ALL.single { it.startVersion == 17 && it.endVersion == 18 }
        assertEquals(17, migration.startVersion)
        assertEquals(18, migration.endVersion)
    }

    @Test
    fun exportedSchema18KeepsActivityEventsAndAddsContextForeignKeys() {
        val current = schemaFile("18.json").readText()
        val previous = schemaFile("17.json").readText()
        assertTrue(current.contains("\"version\": 18"))
        assertEquals(createSql(previous, "activity_events"), createSql(current, "activity_events"))
        assertEquals(createSql(previous, "check_ins"), createSql(current, "check_ins"))
        val window = createSql(current, "check_in_context_windows")
        assertTrue(window.contains("ON DELETE CASCADE"))
        assertTrue(window.contains("FOREIGN KEY(`previousCheckInId`) REFERENCES `check_ins`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"))
        val categories = createSql(current, "check_in_context_categories")
        assertTrue(categories.contains("REFERENCES `check_in_context_windows`(`checkInId`)"))
        assertTrue(categories.contains("ON DELETE CASCADE"))
        val items = createSql(current, "check_in_activity_items")
        assertTrue(items.contains("REFERENCES `check_in_context_windows`(`checkInId`)"))
        assertTrue(items.contains("ON DELETE CASCADE"))
        assertTrue(!items.contains("activity_options"))
    }

    private fun schemaFile(name: String): File {
        val relative = "schemas/com.orbit.wellness.data.local.OrbitDatabase/$name"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Missing exported schema $name")
    }

    private fun createSql(json: String, table: String): String {
        val marker = "\"tableName\": \"$table\""
        val tableAt = json.indexOf(marker)
        require(tableAt >= 0) { "Missing table $table" }
        val key = "\"createSql\": \""
        val start = json.indexOf(key, tableAt) + key.length
        val end = json.indexOf("\"", start)
        return json.substring(start, end)
    }
}
