package com.orbit.wellness.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.DietRepository
import com.orbit.wellness.data.MealItemInput
import com.orbit.wellness.data.local.FoodOption
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.local.MealItem
import com.orbit.wellness.data.remote.OpenFoodFactsProduct
import com.orbit.wellness.data.remote.scaleMacrosPer100g
import com.orbit.wellness.util.VolumeUnits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class DietAmountMode {
    SERVING,
    ABSOLUTE,
}

data class DraftMealItem(
    val localId: String = UUID.randomUUID().toString(),
    val foodName: String,
    val weightGrams: Double,
    val amountValue: Double,
    val amountUnit: String,
    val calories: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbsGrams: Double?,
    val barcode: String?,
    val source: String,
)

data class DietUiState(
    val foodName: String = "",
    val weightGrams: String = "",
    val amountUnit: String = VolumeUnits.UNIT_GRAMS,
    val amountMode: DietAmountMode = DietAmountMode.ABSOLUTE,
    val servingGrams: Double? = null,
    val servingSizeLabel: String? = null,
    val caloriesPer100g: Double? = null,
    val proteinPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    /** Absolute nutrients for this serving — optional; leave blank to skip. */
    val caloriesInput: String = "",
    val proteinInput: String = "",
    val fatInput: String = "",
    val carbsInput: String = "",
    val useManualNutrients: Boolean = false,
    val barcode: String? = null,
    val draftSource: String = MealItem.SOURCE_MANUAL,
    val draftItems: List<DraftMealItem> = emptyList(),
    val foodOptions: List<FoodOption> = emptyList(),
    val recentMeals: List<MealEvent> = emptyList(),
    val searchResults: List<OpenFoodFactsProduct> = emptyList(),
    val searchAttempted: Boolean = false,
    val isLoading: Boolean = true,
    val isLookingUp: Boolean = false,
    val isSearching: Boolean = false,
    val isSaving: Boolean = false,
    val focusWeightInput: Boolean = false,
    val showAddFoodDialog: Boolean = false,
    val newFoodName: String = "",
    val newFoodWeight: String = "100",
    val loggedAtMillis: Long = System.currentTimeMillis(),
) {
    val draftCalories: Double? get() = draftItems.mapNotNull { it.calories }.takeIf { it.isNotEmpty() }?.sum()
    val draftProtein: Double? get() = draftItems.mapNotNull { it.proteinGrams }.takeIf { it.isNotEmpty() }?.sum()
    val draftFat: Double? get() = draftItems.mapNotNull { it.fatGrams }.takeIf { it.isNotEmpty() }?.sum()
    val draftCarbs: Double? get() = draftItems.mapNotNull { it.carbsGrams }.takeIf { it.isNotEmpty() }?.sum()

    val hasCatalogItem: Boolean
        get() = !useManualNutrients && servingGrams != null

    /** Manual calorie/macros only when catalog search or barcode lookup failed. */
    val showManualCalorieInput: Boolean
        get() = !hasCatalogItem && (useManualNutrients || (searchAttempted && searchResults.isEmpty()))

    fun resolvedWeightGrams(amount: Double = VolumeUnits.parseAmount(weightGrams) ?: 0.0): Double {
        return VolumeUnits.toGrams(amount, amountUnit, servingGrams ?: 100.0)
    }
}

class DietViewModel(
    private val repository: DietRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DietUiState())
    val uiState: StateFlow<DietUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeFoodOptions().collect { options ->
                _uiState.update { it.copy(foodOptions = options, isLoading = false) }
            }
        }
        viewModelScope.launch {
            repository.observeMeals().collect { meals ->
                _uiState.update { it.copy(recentMeals = meals.take(8), isLoading = false) }
            }
        }
    }

    fun onFoodNameChanged(value: String) {
        _uiState.update {
            it.copy(
                foodName = value,
                draftSource = MealItem.SOURCE_MANUAL,
                barcode = null,
                searchResults = emptyList(),
                searchAttempted = false,
                caloriesPer100g = null,
                proteinPer100g = null,
                fatPer100g = null,
                carbsPer100g = null,
                servingGrams = null,
                servingSizeLabel = null,
                amountMode = DietAmountMode.ABSOLUTE,
                amountUnit = VolumeUnits.UNIT_GRAMS,
                useManualNutrients = false,
                caloriesInput = "",
                proteinInput = "",
                fatInput = "",
                carbsInput = "",
            )
        }
    }

    fun onWeightChanged(value: String) {
        _uiState.update { it.copy(weightGrams = VolumeUnits.filterDecimalInput(value)) }
    }

    fun onLoggedAtChanged(millis: Long) {
        _uiState.update { it.copy(loggedAtMillis = millis) }
    }

    fun onAmountModeChanged(mode: DietAmountMode) {
        _uiState.update { state ->
            if (state.amountMode == mode) return@update state
            val amount = VolumeUnits.parseAmount(state.weightGrams)
            val gramsNow = amount?.let { state.resolvedWeightGrams(it) }
            val serving = state.servingGrams ?: 100.0
            if (mode == DietAmountMode.SERVING) {
                val servings = gramsNow?.let { VolumeUnits.fromGrams(it, VolumeUnits.UNIT_SERVING, serving) } ?: 1.0
                state.copy(
                    amountMode = mode,
                    amountUnit = VolumeUnits.UNIT_SERVING,
                    weightGrams = VolumeUnits.formatAmount(servings),
                )
            } else {
                state.copy(
                    amountMode = mode,
                    amountUnit = VolumeUnits.UNIT_GRAMS,
                    weightGrams = gramsNow?.let { VolumeUnits.formatAmount(it) }.orEmpty(),
                )
            }
        }
    }

    fun onAmountUnitChanged(unit: String) {
        _uiState.update { state ->
            val amount = VolumeUnits.parseAmount(state.weightGrams)
            val gramsNow = amount?.let { state.resolvedWeightGrams(it) }
            state.copy(
                amountMode = DietAmountMode.ABSOLUTE,
                amountUnit = unit,
                weightGrams = gramsNow?.let { VolumeUnits.formatAmount(VolumeUnits.fromGrams(it, unit)) }
                    ?: state.weightGrams,
            )
        }
    }

    fun onCaloriesInputChanged(value: String) {
        _uiState.update {
            it.copy(
                caloriesInput = VolumeUnits.filterDecimalInput(value),
                useManualNutrients = true,
            )
        }
    }

    fun onProteinInputChanged(value: String) {
        _uiState.update {
            it.copy(
                proteinInput = VolumeUnits.filterDecimalInput(value),
                useManualNutrients = true,
            )
        }
    }

    fun onFatInputChanged(value: String) {
        _uiState.update {
            it.copy(
                fatInput = VolumeUnits.filterDecimalInput(value),
                useManualNutrients = true,
            )
        }
    }

    fun onCarbsInputChanged(value: String) {
        _uiState.update {
            it.copy(
                carbsInput = VolumeUnits.filterDecimalInput(value),
                useManualNutrients = true,
            )
        }
    }

    fun clearNutrients() {
        _uiState.update {
            it.copy(
                caloriesInput = "",
                proteinInput = "",
                fatInput = "",
                carbsInput = "",
                caloriesPer100g = null,
                proteinPer100g = null,
                fatPer100g = null,
                carbsPer100g = null,
                servingGrams = null,
                servingSizeLabel = null,
                amountMode = DietAmountMode.ABSOLUTE,
                amountUnit = VolumeUnits.UNIT_GRAMS,
                useManualNutrients = false,
            )
        }
    }

    fun searchFoods() {
        val query = _uiState.value.foodName.trim()
        if (query.isEmpty()) {
            viewModelScope.launch { _messages.trySend("Enter a food name to search.") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isSearching = true, searchAttempted = false, searchResults = emptyList())
            }
            repository.searchFoods(query)
                .onSuccess { products ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchAttempted = true,
                            searchResults = products,
                        )
                    }
                    if (products.isEmpty()) {
                        _messages.trySend("No matches — enter amount and optional nutrients manually.")
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isSearching = false, searchAttempted = true, searchResults = emptyList())
                    }
                    _messages.trySend(error.message ?: "Search failed. You can still enter nutrients manually.")
                }
        }
    }

    fun applySearchResult(product: OpenFoodFactsProduct) {
        applyProduct(product, MealItem.SOURCE_SEARCH)
    }

    fun applyQuickAdd(option: FoodOption) {
        val serving = option.defaultWeightGrams.coerceAtLeast(1).toDouble()
        _uiState.update {
            it.copy(
                foodName = option.name,
                weightGrams = "1",
                amountMode = DietAmountMode.SERVING,
                amountUnit = VolumeUnits.UNIT_SERVING,
                servingGrams = serving,
                servingSizeLabel = "${option.defaultWeightGrams} g",
                caloriesPer100g = option.caloriesPer100g,
                proteinPer100g = option.proteinPer100g,
                fatPer100g = option.fatPer100g,
                carbsPer100g = option.carbsPer100g,
                useManualNutrients = false,
                caloriesInput = "",
                proteinInput = "",
                fatInput = "",
                carbsInput = "",
                barcode = null,
                draftSource = MealItem.SOURCE_QUICK_ADD,
                searchResults = emptyList(),
                searchAttempted = false,
                focusWeightInput = true,
            )
        }
    }

    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLookingUp = true) }
            repository.lookupBarcode(barcode)
                .onSuccess { product -> applyProduct(product, MealItem.SOURCE_BARCODE) }
                .onFailure { error ->
                    _messages.trySend(
                        (error.message ?: "Could not look up barcode.") +
                            " Enter name, amount, and optional nutrients manually.",
                    )
                    _uiState.update {
                        it.copy(
                            barcode = barcode,
                            draftSource = MealItem.SOURCE_MANUAL,
                            searchAttempted = true,
                            useManualNutrients = true,
                        )
                    }
                }
            _uiState.update { it.copy(isLookingUp = false) }
        }
    }

    private fun applyProduct(product: OpenFoodFactsProduct, source: String) {
        _uiState.update {
            it.copy(
                foodName = product.name,
                barcode = product.barcode.ifBlank { null },
                caloriesPer100g = product.caloriesPer100g,
                proteinPer100g = product.proteinPer100g,
                fatPer100g = product.fatPer100g,
                carbsPer100g = product.carbsPer100g,
                servingGrams = product.servingGrams,
                servingSizeLabel = product.servingSizeLabel,
                amountMode = DietAmountMode.SERVING,
                amountUnit = VolumeUnits.UNIT_SERVING,
                weightGrams = "1",
                useManualNutrients = false,
                caloriesInput = "",
                proteinInput = "",
                fatInput = "",
                carbsInput = "",
                draftSource = source,
                searchResults = emptyList(),
                searchAttempted = false,
                focusWeightInput = true,
            )
        }
    }

    fun onWeightInputFocused() {
        _uiState.update { it.copy(focusWeightInput = false) }
    }

    fun addItemToMeal() {
        val current = _uiState.value
        val name = current.foodName.trim()
        val amount = VolumeUnits.parseAmount(current.weightGrams)
        if (name.isEmpty() || amount == null) {
            viewModelScope.launch {
                _messages.trySend("Enter a food name and amount (weight or volume).")
            }
            return
        }
        val grams = current.resolvedWeightGrams(amount)
        val (calories, protein, fat, carbs) = resolveNutrients(current, grams)
        val draft = DraftMealItem(
            foodName = name,
            weightGrams = grams,
            amountValue = amount,
            amountUnit = current.amountUnit,
            calories = calories,
            proteinGrams = protein,
            fatGrams = fat,
            carbsGrams = carbs,
            barcode = current.barcode,
            source = when {
                current.barcode != null && current.draftSource == MealItem.SOURCE_BARCODE ->
                    MealItem.SOURCE_BARCODE
                else -> current.draftSource
            },
        )
        _uiState.update {
            it.copy(
                draftItems = it.draftItems + draft,
                foodName = "",
                weightGrams = "",
                caloriesPer100g = null,
                proteinPer100g = null,
                fatPer100g = null,
                carbsPer100g = null,
                caloriesInput = "",
                proteinInput = "",
                fatInput = "",
                carbsInput = "",
                useManualNutrients = false,
                servingGrams = null,
                servingSizeLabel = null,
                amountMode = DietAmountMode.ABSOLUTE,
                amountUnit = VolumeUnits.UNIT_GRAMS,
                barcode = null,
                draftSource = MealItem.SOURCE_MANUAL,
                searchResults = emptyList(),
                searchAttempted = false,
                focusWeightInput = false,
            )
        }
    }

    private fun resolveNutrients(
        state: DietUiState,
        grams: Double,
    ): NutrientSet {
        if (state.useManualNutrients ||
            listOf(state.caloriesInput, state.proteinInput, state.fatInput, state.carbsInput)
                .any { it.isNotBlank() }
        ) {
            return NutrientSet(
                calories = state.caloriesInput.toDoubleOrNull(),
                protein = state.proteinInput.toDoubleOrNull(),
                fat = state.fatInput.toDoubleOrNull(),
                carbs = state.carbsInput.toDoubleOrNull(),
            )
        }
        return NutrientSet(
            calories = scaleMacrosPer100g(state.caloriesPer100g, grams),
            protein = scaleMacrosPer100g(state.proteinPer100g, grams),
            fat = scaleMacrosPer100g(state.fatPer100g, grams),
            carbs = scaleMacrosPer100g(state.carbsPer100g, grams),
        )
    }

    fun removeDraftItem(localId: String) {
        _uiState.update { state ->
            state.copy(draftItems = state.draftItems.filterNot { it.localId == localId })
        }
    }

    fun openAddFoodDialog() {
        _uiState.update { it.copy(showAddFoodDialog = true, newFoodName = "", newFoodWeight = "100") }
    }

    fun dismissAddFoodDialog() {
        _uiState.update { it.copy(showAddFoodDialog = false) }
    }

    fun onNewFoodNameChanged(value: String) {
        _uiState.update { it.copy(newFoodName = value) }
    }

    fun onNewFoodWeightChanged(value: String) {
        _uiState.update { it.copy(newFoodWeight = VolumeUnits.filterDecimalInput(value).take(5)) }
    }

    fun confirmAddFood() {
        val current = _uiState.value
        val name = current.newFoodName.trim()
        val weight = current.newFoodWeight.toDoubleOrNull()?.toInt() ?: 100
        if (name.isEmpty()) {
            viewModelScope.launch { _messages.trySend("Enter a food name.") }
            return
        }
        viewModelScope.launch {
            repository.addFoodOption(name = name, defaultWeightGrams = weight.coerceAtLeast(1))
                .onSuccess {
                    _uiState.update { it.copy(showAddFoodDialog = false, newFoodName = "", newFoodWeight = "100") }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add food option.")
                }
        }
    }

    fun saveMeal() {
        val current = _uiState.value
        if (current.draftItems.isEmpty()) {
            viewModelScope.launch {
                _messages.trySend("Add at least one item to the meal.")
            }
            return
        }
        if (current.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            repository.logMeal(
                items = current.draftItems.map { draft ->
                    MealItemInput(
                        foodName = draft.foodName,
                        weightGrams = draft.weightGrams,
                        amountValue = draft.amountValue,
                        amountUnit = draft.amountUnit,
                        calories = draft.calories,
                        proteinGrams = draft.proteinGrams,
                        fatGrams = draft.fatGrams,
                        carbsGrams = draft.carbsGrams,
                        barcode = draft.barcode,
                        source = draft.source,
                    )
                },
                timestamp = current.loggedAtMillis,
            )
            _uiState.update {
                it.copy(
                    isSaving = false,
                    draftItems = emptyList(),
                    foodName = "",
                    weightGrams = "",
                    caloriesPer100g = null,
                    proteinPer100g = null,
                    fatPer100g = null,
                    carbsPer100g = null,
                    caloriesInput = "",
                    proteinInput = "",
                    fatInput = "",
                    carbsInput = "",
                    useManualNutrients = false,
                    servingGrams = null,
                    servingSizeLabel = null,
                    amountMode = DietAmountMode.ABSOLUTE,
                    amountUnit = VolumeUnits.UNIT_GRAMS,
                    barcode = null,
                    draftSource = MealItem.SOURCE_MANUAL,
                    searchResults = emptyList(),
                    searchAttempted = false,
                    loggedAtMillis = System.currentTimeMillis(),
                )
            }
            _savedFeedback.trySend(Unit)
        }
    }

    fun updateMeal(event: MealEvent, newLabel: String) {
        viewModelScope.launch {
            repository.updateMeal(event, newLabel)
        }
    }

    private data class NutrientSet(
        val calories: Double?,
        val protein: Double?,
        val fat: Double?,
        val carbs: Double?,
    )

    class Factory(
        private val repository: DietRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DietViewModel(repository) as T
        }
    }
}
