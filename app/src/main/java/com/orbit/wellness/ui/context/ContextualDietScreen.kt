package com.orbit.wellness.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.CheckInMeal
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.rememberHideKeyboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextualDietScreen(
    viewModel: ContextualDietViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val hideKeyboard = rememberHideKeyboard()

    LaunchedEffect(viewModel) {
        viewModel.finished.collect { onFinished() }
    }
    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = stringResource(R.string.meals_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (!uiState.loaded) return@Column

        if (uiState.missing) {
            Text(
                text = ContextOverviewCopy.MISSING,
                style = MaterialTheme.typography.headlineSmall,
            )
            return@Column
        }

        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        uiState.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.meal_context_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.meal_size),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MealSizeChips.forEach { (size, label) ->
                FilterChip(
                    selected = uiState.selectedSize == size,
                    onClick = { viewModel.selectSize(size) },
                    enabled = !uiState.isSaving,
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Text(
            text = stringResource(R.string.meal_foods_optional),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.options.forEach { option ->
                FilterChip(
                    selected = uiState.selectedFoods.any { it.foodOptionId == option.id },
                    onClick = { viewModel.toggleFood(option) },
                    enabled = !uiState.isSaving,
                    label = { Text(option.name) },
                )
            }
            FilterChip(
                selected = false,
                onClick = viewModel::openAddDialog,
                enabled = !uiState.isSaving,
                label = { Text(stringResource(R.string.add_activity)) },
            )
        }
        uiState.selectedFoods.filter { food ->
            uiState.options.none { it.id == food.foodOptionId }
        }.forEach { food ->
            TextButton(
                onClick = { viewModel.removeFood(food.key) },
                enabled = !uiState.isSaving,
            ) {
                Text("${food.foodName} · ${stringResource(R.string.remove_item)}")
            }
        }

        Button(
            onClick = {
                hideKeyboard()
                viewModel.addOrUpdate()
            },
            enabled = !uiState.isSaving && uiState.selectedSize != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (uiState.editingId == null) R.string.add_meal else R.string.update_meal,
                ),
            )
        }

        uiState.meals.forEach { meal ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = ContextOverviewCopy.mealSizeLabel(meal.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (meal.foods.isEmpty()) {
                        stringResource(R.string.no_foods_specified)
                    } else {
                        meal.foods.joinToString(", ") { it.foodName }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(
                        onClick = { viewModel.beginEdit(meal.id) },
                        enabled = !uiState.isSaving,
                    ) {
                        Text(stringResource(R.string.edit))
                    }
                    TextButton(
                        onClick = { viewModel.remove(meal.id) },
                        enabled = !uiState.isSaving,
                    ) {
                        Text(stringResource(R.string.remove_item))
                    }
                }
            }
        }

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveMeals()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_meals))
        }
    }

    if (uiState.showAddDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddDialog,
            title = { Text(stringResource(R.string.add_food)) },
            text = {
                OutlinedTextField(
                    value = uiState.newFoodName,
                    onValueChange = viewModel::onNewFoodNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.food_name)) },
                    singleLine = true,
                )
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
                TextButton(onClick = viewModel::dismissAddDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private val MealSizeChips = listOf(
    CheckInMeal.SNACK to R.string.meal_snack,
    CheckInMeal.SMALL to R.string.meal_small,
    CheckInMeal.MEDIUM to R.string.meal_medium,
    CheckInMeal.LARGE to R.string.meal_large,
)
