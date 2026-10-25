package com.orbit.wellness.ui.exercise

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.ui.checkin.moodDescriptorResId
import com.orbit.wellness.ui.history.EditWorkoutDialog
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.LoggedAtField
import com.orbit.wellness.ui.util.LoggedEntry
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import kotlin.math.roundToInt

private val DEFAULT_CARDIO_ACTIVITIES = listOf(
    "Walking",
    "Running",
    "Biking",
    "Swimming",
)

private val MUSCLE_GROUPS = listOf(
    "Chest",
    "Back",
    "Legs",
    "Core",
    "Arms",
    "Shoulders",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(
    viewModel: ExerciseViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedMessage = stringResource(R.string.workout_saved)
    val hideKeyboard = rememberHideKeyboard()
    val haptic = LocalHapticFeedback.current
    val moodInt = uiState.moodScore.roundToInt()
    var lastHapticMood by remember { mutableIntStateOf(moodInt) }
    var editingWorkout by remember { mutableStateOf<WorkoutEvent?>(null) }

    CollectSavedFeedback(
        pulses = viewModel.savedFeedback,
        message = savedMessage,
        snackbarHostState = snackbarHostState,
    )

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    val workoutTypeNames = if (uiState.workoutOptions.isNotEmpty()) {
        uiState.workoutOptions.map { it.name }
    } else {
        listOf("Cardio", "Strength", "Yoga / Balance")
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
            text = stringResource(R.string.exercise_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.exercise_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LoggedAtField(
            millis = uiState.loggedAtMillis,
            onChange = viewModel::onLoggedAtChanged,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.logMode == WorkoutLogMode.Full,
                onClick = { viewModel.setLogMode(WorkoutLogMode.Full) },
                label = { Text(stringResource(R.string.log_mode_full)) },
            )
            FilterChip(
                selected = uiState.logMode == WorkoutLogMode.Session,
                onClick = { viewModel.setLogMode(WorkoutLogMode.Session) },
                label = { Text(stringResource(R.string.log_mode_session)) },
            )
        }

        Text(
            text = stringResource(
                if (uiState.logMode == WorkoutLogMode.Full) {
                    R.string.log_mode_full_workout_hint
                } else {
                    R.string.log_mode_session_workout_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.activeWorkouts.isNotEmpty()) {
            Text(
                text = stringResource(R.string.active_workouts),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.activeWorkouts.forEach { workout ->
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.onActiveWorkoutTapped(workout) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.workout_active_label,
                                    workout.workoutType,
                                ),
                            )
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.workout_type),
            style = MaterialTheme.typography.titleMedium,
        )
        if (uiState.isLoading) {
            InlineLoading()
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workoutTypeNames.forEach { typeName ->
                    FilterChip(
                        selected = uiState.workoutType.equals(typeName, ignoreCase = true),
                        onClick = { viewModel.onWorkoutTypeSelected(typeName) },
                        label = { Text(typeName) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = viewModel::openAddWorkoutTypeDialog,
                    label = { Text(stringResource(R.string.add_workout_type)) },
                )
            }
        }

        if (uiState.logMode == WorkoutLogMode.Full) {
            OutlinedTextField(
                value = uiState.durationMinutes,
                onValueChange = viewModel::onDurationChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.duration_minutes)) },
                singleLine = true,
            )
        }

        val isCardio = uiState.workoutType.equals("Cardio", ignoreCase = true)
        val isStrength = uiState.workoutType.equals("Strength", ignoreCase = true)

        when {
            isCardio -> {
                Text(
                    text = stringResource(R.string.cardio_activity),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val activities = (DEFAULT_CARDIO_ACTIVITIES + uiState.customCardioActivities).distinct()
                    activities.forEach { activity ->
                        FilterChip(
                            selected = uiState.cardioActivity == activity,
                            onClick = { viewModel.onCardioActivitySelected(activity) },
                            label = { Text(activity) },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = viewModel::openAddCardioDialog,
                        label = { Text(stringResource(R.string.add_cardio_type)) },
                    )
                }
            }
            isStrength -> {
                Text(
                    text = stringResource(R.string.muscle_groups),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MUSCLE_GROUPS.forEach { group ->
                        FilterChip(
                            selected = group in uiState.selectedMuscleGroups,
                            onClick = { viewModel.toggleMuscleGroup(group) },
                            label = { Text(group) },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.notes,
            onValueChange = viewModel::onNotesChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.workout_notes)) },
            minLines = 2,
        )

        if (uiState.logMode == WorkoutLogMode.Full) {
            Text(
                text = stringResource(R.string.use_mood_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = moodInt.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(moodDescriptorResId(moodInt)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = uiState.moodScore,
                onValueChange = { newValue ->
                    val newInt = newValue.roundToInt()
                    if (newInt != lastHapticMood) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        lastHapticMood = newInt
                    }
                    viewModel.onMoodChanged(newValue)
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    hideKeyboard()
                    viewModel.saveWorkout()
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save_workout))
            }
        } else {
            Button(
                onClick = {
                    hideKeyboard()
                    viewModel.startSessionWorkout()
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start_workout))
            }
        }

        if (uiState.recentWorkouts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recent_workouts),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.recentWorkouts.forEach { workout ->
                val details = buildList {
                    add("${workout.durationMinutes} min")
                    workout.cardioActivity?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (workout.muscleGroups.isNotBlank()) add(workout.muscleGroups)
                    when {
                        workout.preMood != null && workout.postMood != null ->
                            add("mood ${workout.preMood} → ${workout.postMood}")
                        workout.postMood != null ->
                            add("mood ${workout.postMood}")
                    }
                }
                LoggedEntry(
                    timeLabel = DateUtils.sessionTimeLabel(workout.startTime, workout.endTime),
                    title = workout.workoutType,
                    footer = details.joinToString(" · "),
                    onClick = { editingWorkout = workout },
                )
            }
        }
    }

    if (uiState.moodPrompt != null && uiState.pendingWorkoutLabel != null) {
        WorkoutMoodSheet(
            prompt = uiState.moodPrompt!!,
            workoutLabel = uiState.pendingWorkoutLabel!!,
            moodScore = uiState.promptMoodScore,
            onMoodChanged = viewModel::onPromptMoodChanged,
            onConfirm = {
                hideKeyboard()
                viewModel.confirmMoodPrompt()
            },
            onDismiss = viewModel::dismissMoodPrompt,
        )
    }

    if (uiState.showAddCardioDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddCardioDialog,
            title = { Text(stringResource(R.string.add_cardio_type_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.newCardioName,
                    onValueChange = viewModel::onNewCardioNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_cardio_type_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.confirmAddCardio()
                    },
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddCardioDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (uiState.showAddWorkoutTypeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddWorkoutTypeDialog,
            title = { Text(stringResource(R.string.add_workout_type_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.newWorkoutTypeName,
                    onValueChange = viewModel::onNewWorkoutTypeNameChanged,
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
                TextButton(onClick = viewModel::dismissAddWorkoutTypeDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editingWorkout?.let { workout ->
        EditWorkoutDialog(
            event = workout,
            onConfirm = { duration, notes ->
                viewModel.updateWorkout(workout, duration, notes)
                editingWorkout = null
            },
            onDismiss = { editingWorkout = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutMoodSheet(
    prompt: WorkoutMoodPrompt,
    workoutLabel: String,
    moodScore: Float,
    onMoodChanged: (Float) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moodInt = moodScore.roundToInt()
    val haptic = LocalHapticFeedback.current
    var lastHapticMood by remember { mutableIntStateOf(moodInt) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = when (prompt) {
                    WorkoutMoodPrompt.Pre ->
                        stringResource(R.string.pre_workout_mood_title, workoutLabel)
                    WorkoutMoodPrompt.Post ->
                        stringResource(R.string.post_workout_mood_title, workoutLabel)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = moodInt.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(moodDescriptorResId(moodInt)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = moodScore,
                onValueChange = { newValue ->
                    val newInt = newValue.roundToInt()
                    if (newInt != lastHapticMood) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        lastHapticMood = newInt
                    }
                    onMoodChanged(newValue)
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = onConfirm) {
                    Text(
                        text = when (prompt) {
                            WorkoutMoodPrompt.Pre -> stringResource(R.string.start_workout)
                            WorkoutMoodPrompt.Post -> stringResource(R.string.finish_workout)
                        },
                    )
                }
            }
        }
    }
}
