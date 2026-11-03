package com.orbit.wellness.ui.context

import com.orbit.wellness.data.CheckInAxisInput
import com.orbit.wellness.data.CheckInMealSelection
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.InMemoryCheckInDao
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.data.local.CheckInContextStatus
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.data.local.CheckInMealFoodItem
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.FoodOption
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContextualDietReviewTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val localDate = DateUtils.formatLocalDate(day)
    private val atTen = DateUtils.atTimeMillis(day, 10, 0)
    private val rice = FoodOption(
        id = "rice",
        name = "Rice",
        sortOrder = 0,
        isCustom = false,
        caloriesPer100g = null,
        proteinPer100g = null,
        fatPer100g = null,
        carbsPer100g = null,
    )
    private val chicken = FoodOption(
        id = "chicken",
        name = "Chicken",
        sortOrder = 1,
        isCustom = false,
        caloriesPer100g = null,
        proteinPer100g = null,
        fatPer100g = null,
        carbsPer100g = null,
    )

    @Test
    fun reopeningPreloadsMealsSizesFoodsAndOrderOnce() {
        val draft = ContextualDietDraft()
        draft.onSaved(
            listOf(
                savedMeal("lunch", CheckInMeal.MEDIUM, 1),
                savedMeal("breakfast", CheckInMeal.SMALL, 0),
            ),
            listOf(
                savedFood("lunch", "chicken", "Chicken", 1),
                savedFood("lunch", "rice", "Rice", 0),
            ),
        )
        assertTrue(draft.meals.isEmpty())
        draft.onOptions(listOf(rice, chicken))
        assertEquals(listOf("breakfast", "lunch"), draft.meals.map { it.id })
        assertEquals(listOf(CheckInMeal.SMALL, CheckInMeal.MEDIUM), draft.meals.map { it.size })
        assertEquals(listOf("Rice", "Chicken"), draft.meals[1].foods.map { it.foodName })

        draft.remove("breakfast")
        draft.onSaved(
            listOf(savedMeal("lunch", CheckInMeal.MEDIUM, 1)),
            listOf(savedFood("lunch", "rice", "Rice", 0)),
        )
        draft.onOptions(listOf(rice, chicken))
        assertEquals(listOf("lunch"), draft.meals.map { it.id })
        assertEquals(listOf("Rice", "Chicken"), draft.meals.single().foods.map { it.foodName })
    }

    @Test
    fun choosingFoodsDoesNotWriteUntilTheMealIsSaved() {
        val repository = repositoryWithCheckIn()
        val draft = readyDraft()
        draft.selectSize(CheckInMeal.MEDIUM)
        draft.toggleFood(rice)
        runBlocking { commitDietReview(repository, "ten", draft.commitForBack()) }
        assertTrue(runBlocking { repository.getMeals("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, dietStatus(repository))

        assertNull(draft.addOrUpdate())
        runBlocking { commitDietReview(repository, "ten", draft.commitForSave()) }
        val meal = runBlocking { repository.getMeals("ten") }.single()
        assertEquals(CheckInMeal.MEDIUM, meal.size)
        assertEquals(listOf("Rice"), runBlocking { repository.getMealFoods("ten") }.map { it.foodName })
        assertEquals(CheckInContextStatus.ENTRIES, dietStatus(repository))
    }

    @Test
    fun backPerformsNoWrite() {
        val repository = repositoryWithCheckIn()
        val draft = readyDraft()
        draft.selectSize(CheckInMeal.SNACK)
        assertNull(draft.addOrUpdate())
        runBlocking { commitDietReview(repository, "ten", draft.commitForBack()) }
        assertTrue(runBlocking { repository.getMeals("ten") }.isEmpty())
        assertEquals(CheckInContextStatus.NONE, dietStatus(repository))
        assertEquals(listOf(CheckInMeal.SNACK), draft.meals.map { it.size })
    }

    @Test
    fun saveFailurePreservesTheDraftAndThePreviousMeals() {
        val repository = repositoryWithCheckIn()
        runBlocking {
            repository.saveDietSelections(
                checkInId = "ten",
                meals = listOf(
                    CheckInMealSelection(
                        id = "breakfast",
                        size = CheckInMeal.SMALL,
                        sortOrder = 0,
                    ),
                ),
                reportedAt = atTen,
            )
        }
        val draft = ContextualDietDraft()
        draft.onSaved(runBlocking { repository.getMeals("ten") }, emptyList())
        draft.onOptions(listOf(rice))
        draft.selectSize(CheckInMeal.LARGE)
        assertNull(draft.addOrUpdate())
        val before = draft.meals.map { it.id to it.size }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                commitDietReview(repository, "missing", draft.commitForSave())
            }
        }
        assertEquals(before, draft.meals.map { it.id to it.size })
        assertEquals(listOf("breakfast"), runBlocking { repository.getMeals("ten") }.map { it.id })
        assertEquals(CheckInContextStatus.ENTRIES, dietStatus(repository))
    }

    private fun readyDraft(): ContextualDietDraft {
        val draft = ContextualDietDraft()
        draft.onSaved(emptyList(), emptyList())
        draft.onOptions(listOf(rice, chicken))
        return draft
    }

    private fun savedMeal(id: String, size: String, sortOrder: Int) = CheckInMeal(
        id = id,
        checkInId = "ten",
        size = size,
        sortOrder = sortOrder,
        reportedAt = atTen,
    )

    private fun savedFood(mealId: String, optionId: String, name: String, sortOrder: Int) = CheckInMealFoodItem(
        mealId = mealId,
        foodOptionId = optionId,
        foodName = name,
        sortOrder = sortOrder,
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
