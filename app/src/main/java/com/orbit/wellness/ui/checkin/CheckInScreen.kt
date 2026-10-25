package com.orbit.wellness.ui.checkin

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orbit.wellness.R
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.ui.history.EditMoodDialog
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.LoggedAtField
import com.orbit.wellness.ui.util.LoggedEntry
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import java.time.LocalDate
import kotlin.math.roundToInt

private enum class SleepTimeField {
    Bedtime,
    Wake,
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    viewModel: CheckInViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedMessage = stringResource(R.string.mood_saved)
    val sleepSavedMessage = stringResource(R.string.sleep_saved)
    val haptic = LocalHapticFeedback.current
    val hideKeyboard = rememberHideKeyboard()
    val moodInt = uiState.moodScore.roundToInt()
    var lastHapticMood by remember { mutableIntStateOf(moodInt) }
    var sleepTimeField by remember { mutableStateOf<SleepTimeField?>(null) }
    var sleepEditing by remember { mutableStateOf(false) }
    var editingMood by remember { mutableStateOf<MoodSample?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        viewModel.syncFromCommitted()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncFromCommitted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.syncFromCommitted()
        }
    }

    LaunchedEffect(moodInt) {
        lastHapticMood = moodInt
    }

    CollectSavedFeedback(
        pulses = viewModel.savedFeedback,
        message = savedMessage,
        snackbarHostState = snackbarHostState,
    )

    CollectSavedFeedback(
        pulses = viewModel.sleepSavedFeedback,
        message = sleepSavedMessage,
        snackbarHostState = snackbarHostState,
        onPulse = { sleepEditing = false },
    )

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.check_in_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = stringResource(R.string.privacy_note),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (uiState.sleepLoaded) {
            SleepSummaryCard(
                alreadyLogged = uiState.hasLoggedSleepToday,
                expanded = !uiState.hasLoggedSleepToday || sleepEditing,
                bedtimeMillis = uiState.bedtimeMillis,
                wakeTimeMillis = uiState.wakeTimeMillis,
                sleepQuality = uiState.sleepQuality,
                prefillFromHealthConnect = uiState.sleepPrefillFromHealthConnect,
                isSaving = uiState.isSavingSleep,
                onEdit = { sleepEditing = true },
                onCollapse = { sleepEditing = false },
                onPickBedtime = { sleepTimeField = SleepTimeField.Bedtime },
                onPickWake = { sleepTimeField = SleepTimeField.Wake },
                onQualityChanged = { newValue ->
                    val newInt = newValue.roundToInt()
                    if (newInt != lastHapticMood) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    viewModel.onSleepQualityChanged(newValue)
                },
                onSave = {
                    hideKeyboard()
                    viewModel.saveSleep()
                },
            )
        }

        Text(
            text = stringResource(R.string.mood_label),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = moodInt.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(moodDescriptorResId(moodInt)),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MoodSlider(
            value = uiState.moodScore,
            onValueChange = { newValue ->
                val newInt = newValue.roundToInt()
                if (newInt != lastHapticMood) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    lastHapticMood = newInt
                }
                viewModel.onMoodChanged(newValue)
            },
        )

        Text(
            text = stringResource(R.string.predominant_emotion_label),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.emotionOptions.forEach { option ->
                val isSelected = uiState.selectedEmotion == option.name
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.onEmotionSelected(option.name) },
                    label = { Text(option.name) },
                )
            }
            FilterChip(
                selected = false,
                onClick = viewModel::openAddEmotionDialog,
                label = { Text(stringResource(R.string.add_activity)) },
            )
        }

        OutlinedTextField(
            value = uiState.moodNote,
            onValueChange = viewModel::onMoodNoteChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.mood_note_optional)) },
            minLines = 2,
        )

        LoggedAtField(
            millis = uiState.loggedAtMillis,
            onChange = viewModel::onLoggedAtChanged,
        )

        if (uiState.todaySamples.isNotEmpty()) {
            Text(
                text = stringResource(R.string.mood_logs_today, uiState.todaySampleCount),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.todaySamples.forEach { sample ->
                val title = if (sample.emotion.isNotBlank()) {
                    "${sample.moodScore}/10 · ${sample.emotion}"
                } else {
                    "${sample.moodScore}/10"
                }
                LoggedEntry(
                    timeLabel = DateUtils.shortDateTimeLabel(sample.recordedAt),
                    title = title,
                    footer = sample.note.takeIf { it.isNotBlank() },
                    onClick = { editingMood = sample },
                )
            }
        }

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveMood()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(text = stringResource(R.string.save_mood))
        }
    }

    editingMood?.let { sample ->
        EditMoodDialog(
            sample = sample,
            onConfirm = { score, note ->
                viewModel.updateMood(sample, score, note)
                editingMood = null
            },
            onDismiss = { editingMood = null },
        )
    }

    sleepTimeField?.let { field ->
        val initialMillis = when (field) {
            SleepTimeField.Bedtime -> uiState.bedtimeMillis
            SleepTimeField.Wake -> uiState.wakeTimeMillis
        }
        SleepTimePickerDialog(
            title = when (field) {
                SleepTimeField.Bedtime -> stringResource(R.string.sleep_bedtime)
                SleepTimeField.Wake -> stringResource(R.string.sleep_wake)
            },
            initialHour = DateUtils.hourOf(initialMillis),
            initialMinute = DateUtils.minuteOf(initialMillis),
            onDismiss = { sleepTimeField = null },
            onConfirm = { hour, minute ->
                val today = LocalDate.now()
                val millis = when (field) {
                    SleepTimeField.Bedtime -> {
                        // Evening hours belong to last night; early-morning hours are after midnight.
                        val date = if (hour >= 12) today.minusDays(1) else today
                        DateUtils.atTimeMillis(date, hour, minute)
                    }
                    SleepTimeField.Wake -> DateUtils.atTimeMillis(today, hour, minute)
                }
                when (field) {
                    SleepTimeField.Bedtime -> viewModel.onBedtimeChanged(millis)
                    SleepTimeField.Wake -> viewModel.onWakeTimeChanged(millis)
                }
                sleepTimeField = null
            },
        )
    }

    if (uiState.showAddEmotionDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddEmotionDialog,
            title = { Text(stringResource(R.string.add_emotion_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.newEmotionName,
                    onValueChange = viewModel::onNewEmotionNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_activity_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.confirmAddEmotion()
                    },
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddEmotionDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SleepSummaryCard(
    alreadyLogged: Boolean,
    expanded: Boolean,
    bedtimeMillis: Long,
    wakeTimeMillis: Long,
    sleepQuality: Float,
    prefillFromHealthConnect: Boolean,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onCollapse: () -> Unit,
    onPickBedtime: () -> Unit,
    onPickWake: () -> Unit,
    onQualityChanged: (Float) -> Unit,
    onSave: () -> Unit,
) {
    val qualityInt = sleepQuality.roundToInt()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!expanded && alreadyLogged) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sleep_logged_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.sleep_logged_summary,
                                DateUtils.shortDateTimeLabel(bedtimeMillis),
                                DateUtils.shortDateTimeLabel(wakeTimeMillis),
                                qualityInt,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.edit))
                    }
                }
                return@Column
            }

            Text(
                text = stringResource(
                    if (alreadyLogged) R.string.sleep_logged_title else R.string.sleep_good_morning,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.sleep_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (prefillFromHealthConnect && !alreadyLogged) {
                Text(
                    text = stringResource(R.string.sleep_prefill_health_connect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sleep_bedtime),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = onPickBedtime) {
                        Text(DateUtils.shortDateTimeLabel(bedtimeMillis))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sleep_wake),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = onPickWake) {
                        Text(DateUtils.shortDateTimeLabel(wakeTimeMillis))
                    }
                }
            }
            Text(
                text = stringResource(R.string.sleep_quality_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = qualityInt.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(moodDescriptorResId(qualityInt)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sleepQuality,
                onValueChange = onQualityChanged,
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSave,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (alreadyLogged) R.string.update_sleep else R.string.save_sleep,
                    ),
                )
            }
            if (alreadyLogged) {
                TextButton(
                    onClick = onCollapse,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.done_editing_sleep))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MoodSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.mood_low),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.mood_high),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
