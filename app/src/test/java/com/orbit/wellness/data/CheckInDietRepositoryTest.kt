package com.orbit.wellness.data

import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CheckInDietRepositoryTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)

    @Test
    fun everyStableSizeIsAccepted() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveDietSelections(
                checkInId = "ten",
                meals = CheckInMeal.SIZES.mapIndexed { index, size ->
                    meal("meal-$size", size, index)
                },
                reportedAt = atTen,
            )
        }
        assertEquals(
            CheckInMeal.SIZES.sorted(),
            runBlocking { repository.getMeals("ten") }.map { it.size }.sorted(),
        )
    }

    @Test
    fun unknownSizeIsRejected() {
        listOf("huge", "none", "healthy").forEach { size ->
            assertThrows(IllegalArgumentException::class.java) {
                CheckInMealSelections.validate(
                    meals = listOf(meal("one", size, 0)),
                    contextWindowExists = true,
                )
            }
        }
    }

    @Test
    fun mealWithoutFoodsIsAcceptedAndNamesAreTrimmed() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(
                    meal(
                        "lunch",
                        CheckInMeal.MEDIUM,
                        0,
                        food("rice", "  Rice  ", 0),
                    ),
                    meal("snack", CheckInMeal.SNACK, 1),
                ),
                reportedAt = atTen,
            )
        }
        val meals = runBlocking { repository.getMeals("ten") }
        assertEquals(listOf(CheckInMeal.MEDIUM, CheckInMeal.SNACK), meals.map { it.size })
        val foods = runBlocking { repository.getMealFoods("ten") }
        assertEquals(listOf("Rice"), foods.map { it.foodName })
        assertEquals("lunch", foods.single().mealId)
        assertEquals(CheckInContextStatus.ENTRIES, dietStatus(repository))
    }

    @Test
    fun blankFoodNameBlankOptionIdDuplicateOptionAndDuplicateMealAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckInMealSelections.validate(
                meals = listOf(meal("one", CheckInMeal.SMALL, 0, food("rice", "  ", 0))),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInMealSelections.validate(
                meals = listOf(meal("one", CheckInMeal.SMALL, 0, food(" ", "Rice", 0))),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInMealSelections.validate(
                meals = listOf(
                    meal(
                        "one",
                        CheckInMeal.SMALL,
                        0,
                        food("rice", "Rice", 0),
                        food("rice", "Rice again", 1),
                    ),
                ),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInMealSelections.validate(
                meals = listOf(
                    meal("same", CheckInMeal.SMALL, 0),
                    meal("same", CheckInMeal.LARGE, 1),
                ),
                contextWindowExists = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckInMealSelections.validate(
                meals = listOf(meal("one", CheckInMeal.SMALL, -1)),
                contextWindowExists = true,
            )
        }
    }

    @Test
    fun theSameFoodMayAppearInDifferentMeals() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(
                    meal("breakfast", CheckInMeal.SMALL, 0, food("rice", "Rice", 0)),
                    meal("dinner", CheckInMeal.LARGE, 1, food("rice", "Rice", 0)),
                ),
                reportedAt = atTen,
            )
        }
        val foods = runBlocking { repository.getMealFoods("ten") }
        assertEquals(listOf("breakfast", "dinner"), foods.map { it.mealId })
        assertEquals(listOf("rice", "rice"), foods.map { it.foodOptionId })
    }

    @Test
    fun missingContextIsRejected() {
        val repository = CheckInRepository(InMemoryCheckInDao())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveDietSelections(
                    checkInId = "missing",
                    meals = listOf(meal("one", CheckInMeal.SNACK, 0)),
                )
            }
        }
    }

    @Test
    fun explicitEmptySaveIsNoneWithoutAPlaceholder() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(meal("one", CheckInMeal.SNACK, 0, food("rice", "Rice", 0))),
                reportedAt = atTen,
            )
            repository.saveDietSelections(
                checkInId = "ten",
                meals = emptyList(),
                reportedAt = atTen + 1,
            )
        }
        assertTrue(runBlocking { repository.getMeals("ten") }.isEmpty())
        assertTrue(runBlocking { repository.getMealFoods("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, dietStatus(repository))
    }

    @Test
    fun replacementRemovesPreviousMealsAndFoodsAndKeepsOrder() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveActivitySelections(
                checkInId = "ten",
                selections = listOf(CheckInActivitySelection("walk", "Walk")),
                reportedAt = atTen,
            )
            repository.saveWorkoutSelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInWorkoutSelection("cardio", "Cardio", 30),
                ),
                reportedAt = atTen,
            )
            repository.saveUsageSelections(
                checkInId = "ten",
                selections = listOf(
                    CheckInUsageSelection("tea", "Tea", "SUPPORTIVE", ""),
                ),
                reportedAt = atTen,
            )
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(
                    meal("old", CheckInMeal.LARGE, 0, food("beans", "Beans", 0)),
                ),
                reportedAt = atTen,
            )
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(
                    meal("second", CheckInMeal.SMALL, 1, food("yogurt", "Yogurt", 1), food("fruit", "Fruit", 0)),
                    meal("first", CheckInMeal.SNACK, 0),
                ),
                reportedAt = atTen + 5,
            )
        }
        val meals = runBlocking { repository.getMeals("ten") }
        assertEquals(listOf("first", "second"), meals.map { it.id })
        assertEquals(listOf(atTen + 5, atTen + 5), meals.map { it.reportedAt })
        val foods = runBlocking { repository.getMealFoods("ten") }
        assertEquals(listOf("Fruit", "Yogurt"), foods.map { it.foodName })
        assertTrue(foods.none { it.foodName == "Beans" })
        assertEquals(CheckInContextStatus.ENTRIES, dietStatus(repository))
        assertEquals(listOf("Walk"), runBlocking { repository.getActivityItems("ten") }.map { it.activityName })
        assertEquals(listOf("Cardio"), runBlocking { repository.getWorkoutItems("ten") }.map { it.workoutName })
        assertEquals(listOf("Tea"), runBlocking { repository.getUsageItems("ten") }.map { it.name })
        val categories = runBlocking { repository.getContextCategories("ten") }
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.ACTIVITY }.status,
        )
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.WORKOUT }.status,
        )
        assertEquals(
            CheckInContextStatus.ENTRIES,
            categories.first { it.category == CheckInContextCategories.USAGE }.status,
        )
    }

    private fun food(optionId: String?, name: String, sortOrder: Int) = CheckInMealFoodSelection(
        foodOptionId = optionId,
        foodName = name,
        sortOrder = sortOrder,
    )

    private fun meal(
        id: String,
        size: String,
        sortOrder: Int,
        vararg foods: CheckInMealFoodSelection,
    ) = CheckInMealSelection(
        id = id,
        size = size,
        sortOrder = sortOrder,
        foods = foods.toList(),
    )

    private fun repositoryWithCheckIn(): CheckInRepository {
        val repository = CheckInRepository(InMemoryCheckInDao())
        runBlocking {
            repository.saveCheckIn(
                axisValues = listOf(CheckInAxisInput(DefaultCheckInAxes.ENERGY, 6)),
                trigger = "manual",
                recordedAt = atTen,
                localDate = localDate,
                id = "ten",
            )
        }
        return repository
    }

    private fun dietStatus(repository: CheckInRepository): String {
        return runBlocking { repository.getContextCategories("ten") }
            .first { it.category == CheckInContextCategories.DIET }
            .status
    }
}
