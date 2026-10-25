package com.orbit.wellness.ui.use

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.rememberHideKeyboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyListsScreen(
    viewModel: MyListsViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val hideKeyboard = rememberHideKeyboard()

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    val columnModifier = if (embedded) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    }

    Column(
        modifier = columnModifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
            Text(
                text = stringResource(R.string.my_lists_title),
                style = if (embedded) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
            )
        }
        Text(
            text = stringResource(R.string.my_lists_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.my_lists_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.add_to_list),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.addToCategory == ListCategory.SUPPORTIVE,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.SUPPORTIVE) },
                label = { Text(stringResource(R.string.intent_supportive)) },
            )
            FilterChip(
                selected = uiState.addToCategory == ListCategory.COPING,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.COPING) },
                label = { Text(stringResource(R.string.intent_coping)) },
            )
            FilterChip(
                selected = uiState.addToCategory == ListCategory.DIET,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.DIET) },
                label = { Text(stringResource(R.string.diet_title)) },
            )
            FilterChip(
                selected = uiState.addToCategory == ListCategory.WORKOUT,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.WORKOUT) },
                label = { Text(stringResource(R.string.workout_type)) },
            )
            FilterChip(
                selected = uiState.addToCategory == ListCategory.EMOTION,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.EMOTION) },
                label = { Text(stringResource(R.string.category_emotion)) },
            )
            FilterChip(
                selected = uiState.addToCategory == ListCategory.ACTIVITY,
                onClick = { viewModel.onAddToCategorySelected(ListCategory.ACTIVITY) },
                label = { Text(stringResource(R.string.category_activity)) },
            )
        }
        OutlinedTextField(
            value = uiState.newName,
            onValueChange = viewModel::onNewNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.use_name)) },
            singleLine = true,
        )
        if (uiState.addToCategory != ListCategory.ACTIVITY &&
            uiState.addToCategory != ListCategory.WORKOUT &&
            uiState.addToCategory != ListCategory.EMOTION
        ) {
            OutlinedTextField(
                value = uiState.newAmount,
                onValueChange = viewModel::onNewAmountChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (uiState.addToCategory == ListCategory.DIET) {
                            stringResource(R.string.weight_grams)
                        } else {
                            stringResource(R.string.default_amount)
                        },
                    )
                },
                singleLine = true,
            )
        }
        Button(
            onClick = {
                hideKeyboard()
                viewModel.addItem()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_list_item))
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.isLoading) {
            InlineLoading()
        } else {
            SubstanceListSection(
                title = stringResource(R.string.intent_supportive),
                emptyText = stringResource(R.string.lists_empty_supportive),
                items = uiState.supportive.map { ListItemData(it.id, it.name, it.defaultAmount) },
                onDelete = viewModel::deleteSubstanceItem,
            )
            SubstanceListSection(
                title = stringResource(R.string.intent_coping),
                emptyText = stringResource(R.string.lists_empty_coping),
                items = uiState.coping.map { ListItemData(it.id, it.name, it.defaultAmount) },
                onDelete = viewModel::deleteSubstanceItem,
            )
            SubstanceListSection(
                title = stringResource(R.string.diet_title),
                emptyText = stringResource(R.string.lists_empty_diet),
                items = uiState.foodOptions.map { ListItemData(it.id, it.name, "${it.defaultWeightGrams}g") },
                onDelete = viewModel::deleteFoodItem,
            )
            SubstanceListSection(
                title = stringResource(R.string.workout_type),
                emptyText = stringResource(R.string.lists_empty_workout_type),
                items = uiState.workoutOptions.map { ListItemData(it.id, it.name, "") },
                onDelete = viewModel::deleteWorkoutItem,
            )
            SubstanceListSection(
                title = stringResource(R.string.category_emotion),
                emptyText = stringResource(R.string.lists_empty_emotion),
                items = uiState.emotionOptions.map { ListItemData(it.id, it.name, "") },
                onDelete = viewModel::deleteEmotionItem,
            )
            SubstanceListSection(
                title = stringResource(R.string.category_activity),
                emptyText = stringResource(R.string.lists_empty_activity),
                items = uiState.activityOptions.map { ListItemData(it.id, it.name, "") },
                onDelete = viewModel::deleteActivityItem,
            )
        }
    }
}

private data class ListItemData(
    val id: String,
    val name: String,
    val subtitle: String,
)

@Composable
private fun SubstanceListSection(
    title: String,
    emptyText: String,
    items: List<ListItemData>,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { onDelete(item.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}
