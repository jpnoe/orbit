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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.CheckInWorkoutItem
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.rememberHideKeyboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextualWorkoutScreen(
    viewModel: ContextualWorkoutViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val hideKeyboard = rememberHideKeyboard()
    val duration = uiState.resolvedDuration
    val canAdd = uiState.selectedOptionId != null &&
        duration != null &&
        duration in CheckInWorkoutItem.MIN_DURATION_MINUTES..CheckInWorkoutItem.MAX_DURATION_MINUTES

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
                text = "Workout",
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
            text = "Add each workout that belonged to this stretch. Duration is approximate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.workout_type),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.options.forEach { option ->
                FilterChip(
                    selected = option.id == uiState.selectedOptionId,
                    onClick = { viewModel.selectOption(option.id) },
                    label = { Text(option.name) },
                )
            }
            FilterChip(
                selected = false,
                onClick = viewModel::openAddDialog,
                label = { Text(stringResource(R.string.add_workout_type)) },
            )
        }

        Text(
            text = "Approximate duration",
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CONTEXTUAL_WORKOUT_DURATION_PRESETS.forEach { minutes ->
                FilterChip(
                    selected = uiState.presetMinutes == minutes && uiState.customDurationText.isEmpty(),
                    onClick = { viewModel.selectPreset(minutes) },
                    label = { Text("$minutes") },
                )
            }
        }
        OutlinedTextField(
            value = uiState.customDurationText,
            onValueChange = viewModel::onCustomDurationChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.duration_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Button(
            onClick = {
                hideKeyboard()
                viewModel.addOrUpdate()
            },
            enabled = !uiState.isSaving && canAdd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.editingId == null) "Add workout" else "Update workout")
        }

        if (uiState.entries.isNotEmpty()) {
            Text(
                text = "Added",
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${entry.workoutName} · ${entry.durationMinutes} min",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.beginEdit(entry.id) },
                        enabled = !uiState.isSaving,
                    ) {
                        Text("Edit")
                    }
                    TextButton(
                        onClick = { viewModel.remove(entry.id) },
                        enabled = !uiState.isSaving,
                    ) {
                        Text("Remove")
                    }
                }
            }
        }

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveWorkouts()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save workouts")
        }
    }

    if (uiState.showAddDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddDialog,
            title = { Text(stringResource(R.string.add_workout_type_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.newWorkoutName,
                    onValueChange = viewModel::onNewWorkoutNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workout_type)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.confirmAddWorkoutType()
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
