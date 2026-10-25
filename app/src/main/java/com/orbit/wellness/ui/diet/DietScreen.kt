package com.orbit.wellness.ui.diet

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.orbit.wellness.R
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.remote.scaleMacrosPer100g
import com.orbit.wellness.ui.history.EditMealDialog
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.LoggedAtField
import com.orbit.wellness.ui.util.LoggedEntry
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import com.orbit.wellness.util.VolumeUnits

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DietScreen(
    viewModel: DietViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }
    var editingMeal by remember { mutableStateOf<MealEvent?>(null) }
    val savedMessage = stringResource(R.string.meal_saved)
    val weightFocusRequester = remember { FocusRequester() }
    val hideKeyboard = rememberHideKeyboard()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showScanner = true
        }
    }

    CollectSavedFeedback(
        pulses = viewModel.savedFeedback,
        message = savedMessage,
        snackbarHostState = snackbarHostState,
    )

    LaunchedEffect(uiState.focusWeightInput) {
        if (uiState.focusWeightInput) {
            weightFocusRequester.requestFocus()
            viewModel.onWeightInputFocused()
        }
    }

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeScanned = { barcode ->
                showScanner = false
                viewModel.onBarcodeScanned(barcode)
            },
            onClose = { showScanner = false },
        )
        return
    }

    val amount = VolumeUnits.parseAmount(uiState.weightGrams) ?: 0.0
    val weightGrams = uiState.resolvedWeightGrams(amount)
    val previewCalories = when {
        uiState.useManualNutrients || uiState.caloriesInput.isNotBlank() ->
            uiState.caloriesInput.toDoubleOrNull()
        else -> scaleMacrosPer100g(uiState.caloriesPer100g, weightGrams)
    }
    val previewProtein = when {
        uiState.useManualNutrients || uiState.proteinInput.isNotBlank() ->
            uiState.proteinInput.toDoubleOrNull()
        else -> scaleMacrosPer100g(uiState.proteinPer100g, weightGrams)
    }
    val previewFat = when {
        uiState.useManualNutrients || uiState.fatInput.isNotBlank() ->
            uiState.fatInput.toDoubleOrNull()
        else -> scaleMacrosPer100g(uiState.fatPer100g, weightGrams)
    }
    val previewCarbs = when {
        uiState.useManualNutrients || uiState.carbsInput.isNotBlank() ->
            uiState.carbsInput.toDoubleOrNull()
        else -> scaleMacrosPer100g(uiState.carbsPer100g, weightGrams)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.diet_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.diet_subtitle_multi),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.draftItems.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.draft_meal_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    uiState.draftItems.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${item.foodName} · ${VolumeUnits.formatAmount(item.amountValue)} ${VolumeUnits.label(item.amountUnit)}" +
                                    (item.calories?.let { " · ${it.toInt()} kcal" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeDraftItem(item.localId) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_item),
                                )
                            }
                        }
                    }
                    uiState.draftCalories?.let { total ->
                        Text(
                            text = stringResource(
                                R.string.draft_meal_total,
                                total.toInt(),
                                String.format("%.1f", uiState.draftProtein ?: 0.0),
                                String.format("%.1f", uiState.draftFat ?: 0.0),
                                String.format("%.1f", uiState.draftCarbs ?: 0.0),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.add_food_item),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.quick_add),
            style = MaterialTheme.typography.labelLarge,
        )
        if (uiState.isLoading) {
            InlineLoading()
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.foodOptions.forEach { option ->
                    FilterChip(
                        selected = uiState.foodName == option.name,
                        onClick = { viewModel.applyQuickAdd(option) },
                        label = { Text(option.name) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = viewModel::openAddFoodDialog,
                    label = { Text(stringResource(R.string.add_activity)) },
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            showScanner = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !uiState.isLookingUp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (uiState.isLookingUp) {
                            stringResource(R.string.looking_up_barcode)
                        } else {
                            stringResource(R.string.scan_barcode)
                        },
                    )
                }

                OutlinedTextField(
                    value = uiState.foodName,
                    onValueChange = viewModel::onFoodNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.food_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            hideKeyboard()
                            viewModel.searchFoods()
                        },
                    ),
                )
                OutlinedButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.searchFoods()
                    },
                    enabled = !uiState.isLookingUp && !uiState.isSearching && uiState.foodName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (uiState.isSearching) {
                            stringResource(R.string.searching_food)
                        } else {
                            stringResource(R.string.search_food)
                        },
                    )
                }

                if (uiState.searchResults.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_results),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.searchResults.forEach { product ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.applySearchResult(product) },
                                label = { Text(product.name) },
                            )
                        }
                    }
                } else if (uiState.searchAttempted && !uiState.isSearching) {
                    Text(
                        text = stringResource(R.string.no_search_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.hasCatalogItem) {
                        FilterChip(
                            selected = uiState.amountMode == DietAmountMode.SERVING,
                            onClick = { viewModel.onAmountModeChanged(DietAmountMode.SERVING) },
                            label = { Text(stringResource(R.string.measure_servings)) },
                        )
                        FilterChip(
                            selected = uiState.amountMode == DietAmountMode.ABSOLUTE,
                            onClick = { viewModel.onAmountModeChanged(DietAmountMode.ABSOLUTE) },
                            label = { Text(stringResource(R.string.measure_weight)) },
                        )
                    }
                    if (uiState.amountMode == DietAmountMode.ABSOLUTE) {
                        FilterChip(
                            selected = uiState.amountUnit == VolumeUnits.UNIT_GRAMS,
                            onClick = { viewModel.onAmountUnitChanged(VolumeUnits.UNIT_GRAMS) },
                            label = { Text(stringResource(R.string.unit_grams)) },
                        )
                        FilterChip(
                            selected = uiState.amountUnit == VolumeUnits.UNIT_OZ,
                            onClick = { viewModel.onAmountUnitChanged(VolumeUnits.UNIT_OZ) },
                            label = { Text(stringResource(R.string.unit_ounces)) },
                        )
                        FilterChip(
                            selected = uiState.amountUnit == VolumeUnits.UNIT_FL_OZ,
                            onClick = { viewModel.onAmountUnitChanged(VolumeUnits.UNIT_FL_OZ) },
                            label = { Text(stringResource(R.string.unit_fl_oz)) },
                        )
                    }
                }
                if (uiState.hasCatalogItem && uiState.amountMode == DietAmountMode.SERVING) {
                    uiState.servingSizeLabel?.let { label ->
                        Text(
                            text = stringResource(R.string.serving_size_hint, label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = uiState.weightGrams,
                    onValueChange = viewModel::onWeightChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(weightFocusRequester),
                    label = {
                        Text(
                            when (uiState.amountUnit) {
                                VolumeUnits.UNIT_SERVING -> stringResource(R.string.amount_servings)
                                VolumeUnits.UNIT_OZ -> stringResource(R.string.amount_ounces)
                                VolumeUnits.UNIT_FL_OZ -> stringResource(R.string.amount_fl_oz)
                                else -> stringResource(R.string.weight_grams)
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { hideKeyboard() }),
                )

                if (uiState.showManualCalorieInput) {
                    Text(
                        text = stringResource(R.string.nutrients_optional_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.nutrients_optional_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = uiState.caloriesInput,
                        onValueChange = viewModel::onCaloriesInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.nutrient_calories)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.proteinInput,
                            onValueChange = viewModel::onProteinInputChanged,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.nutrient_protein)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = uiState.fatInput,
                            onValueChange = viewModel::onFatInputChanged,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.nutrient_fat)) },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = uiState.carbsInput,
                        onValueChange = viewModel::onCarbsInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.nutrient_carbs)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { hideKeyboard() }),
                    )
                    TextButton(
                        onClick = viewModel::clearNutrients,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.clear_nutrients))
                    }
                }

                if (previewCalories != null || previewProtein != null || previewFat != null || previewCarbs != null) {
                    Text(
                        text = stringResource(
                            R.string.macro_preview,
                            previewCalories?.toInt() ?: 0,
                            String.format("%.1f", previewProtein ?: 0.0),
                            String.format("%.1f", previewFat ?: 0.0),
                            String.format("%.1f", previewCarbs ?: 0.0),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.addItemToMeal()
                    },
                    enabled = !uiState.isLookingUp && !uiState.isSearching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_to_meal))
                }
            }
        }

        LoggedAtField(
            millis = uiState.loggedAtMillis,
            onChange = viewModel::onLoggedAtChanged,
        )

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveMeal()
            },
            enabled = !uiState.isSaving && uiState.draftItems.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_meal))
        }

        if (uiState.recentMeals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recent_meals),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.recentMeals.forEach { meal ->
                LoggedEntry(
                    timeLabel = DateUtils.shortDateTimeLabel(meal.timestamp),
                    titles = meal.summaryTitle
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    footer = stringResource(
                        R.string.history_meal_summary,
                        meal.itemCount,
                        meal.calories?.toInt() ?: 0,
                    ),
                    onClick = { editingMeal = meal },
                )
            }
        }
    }

    if (uiState.showAddFoodDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddFoodDialog,
            title = { Text(stringResource(R.string.add_food_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.newFoodName,
                        onValueChange = viewModel::onNewFoodNameChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.food_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.newFoodWeight,
                        onValueChange = viewModel::onNewFoodWeightChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.weight_grams)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.confirmAddFood()
                    },
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddFoodDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editingMeal?.let { meal ->
        EditMealDialog(
            event = meal,
            onConfirm = { label ->
                viewModel.updateMeal(meal, label)
                editingMeal = null
            },
            onDismiss = { editingMeal = null },
        )
    }
}
