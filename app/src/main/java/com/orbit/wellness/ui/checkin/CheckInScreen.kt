package com.orbit.wellness.ui.checkin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import com.orbit.wellness.data.CheckInDrafts
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInContextStartBasis
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.LoggedAtField
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
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val sleepSavedMessage = stringResource(R.string.sleep_saved)
    val hideKeyboard = rememberHideKeyboard()
    var sleepTimeField by remember { mutableStateOf<SleepTimeField?>(null) }
    var sleepEditing by remember { mutableStateOf(false) }

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

    CollectSavedFeedback(
        pulses = viewModel.sleepSavedFeedback,
        message = sleepSavedMessage,
        snackbarHostState = snackbarHostState,
        onPulse = { sleepEditing = false },
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

        if (!uiState.sleepLoaded || !uiState.latestLoaded) {
            InlineLoading()
        } else {
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
                onQualityChanged = viewModel::onSleepQualityChanged,
                onSave = {
                    hideKeyboard()
                    viewModel.saveSleep()
                },
            )

            Button(
                onClick = onOpenLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.check_in_add_entry))
            }

            if (uiState.hasLatestCheckIn) {
                LatestCheckInCard(uiState)
            }
        }
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

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckInLogScreen(
    viewModel: CheckInViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenContext: (String) -> Unit,
    onDiscarded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val hideKeyboard = rememberHideKeyboard()
    var confirmCancel by remember { mutableStateOf(false) }
    val busy = uiState.isSaving || uiState.isDiscarding

    BackHandler(enabled = !confirmCancel && !busy) {
        confirmCancel = true
    }

    LaunchedEffect(viewModel) {
        viewModel.openContext.collect { checkInId ->
            onOpenContext(checkInId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.check_in_log_heading),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.mood_label),
            style = MaterialTheme.typography.titleMedium,
        )

        if (uiState.axesLoaded && uiState.enabledAxes.isEmpty()) {
            Text(
                text = stringResource(R.string.check_in_no_axes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        uiState.enabledAxes.forEach { axis ->
            CheckInAxisRow(
                axis = axis,
                explicitValue = uiState.axisDrafts[axis.id],
                moodAnchor = uiState.moodAnchor,
                onValueChange = { viewModel.onAxisChanged(axis.id, it) },
                onClear = { viewModel.clearAxis(axis.id) },
            )
        }

        Text(
            text = stringResource(R.string.predominant_emotion_label),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.emotionOptions.forEach { option ->
                FilterChip(
                    selected = uiState.selectedEmotion == option.name,
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

        Button(
            onClick = {
                hideKeyboard()
                viewModel.openContextDraft()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.save_mood))
        }
        OutlinedButton(
            onClick = { confirmCancel = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cancel))
        }
    }

    if (confirmCancel) {
        DiscardEntryDialog(
            onConfirm = {
                confirmCancel = false
                viewModel.discardEntry(onDiscarded)
            },
            onDismiss = { confirmCancel = false },
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
private fun DiscardEntryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_in_discard_title)) },
        text = { Text(stringResource(R.string.check_in_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.check_in_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.check_in_keep_editing))
            }
        },
    )
}

@Composable
private fun LatestCheckInCard(state: CheckInUiState) {
    val missing = stringResource(R.string.check_in_not_set)
    val detail = MaterialTheme.typography.bodyMedium
    val detailColor = MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val range = latestCheckInRangeLabel(state)
            if (range.isNotEmpty()) {
                Text(
                    text = range,
                    style = MaterialTheme.typography.labelMedium,
                    color = detailColor,
                )
            }
            Text(
                text = stringResource(R.string.latest_check_in),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = checkInEmotionLine(state.latestEmotion, missing),
                style = detail,
                color = detailColor,
            )
            Text(
                text = checkInMoodLine(state.latestMood, missing),
                style = detail,
                color = detailColor,
            )
            Text(
                text = checkInAxisLine(DefaultCheckInAxes.ENERGY, state.latestEnergy, missing),
                style = detail,
                color = detailColor,
            )
            Text(
                text = checkInAxisLine(DefaultCheckInAxes.CONFIDENCE, state.latestConfidence, missing),
                style = detail,
                color = detailColor,
            )
            Text(
                text = checkInAxisLine(DefaultCheckInAxes.ANXIETY, state.latestAnxiety, missing),
                style = detail,
                color = detailColor,
            )
        }
    }
}

@Composable
private fun latestCheckInRangeLabel(state: CheckInUiState): String {
    val end = state.latestRecordedAt ?: return ""
    val start = state.latestWindowStartAt
    return if (state.latestStartBasis == CheckInContextStartBasis.DAY_START || start == null) {
        DateUtils.wakeupRangeLabel(
            startMillis = start,
            endMillis = end,
            wakeupLabel = stringResource(R.string.history_interval_wakeup),
        )
    } else {
        DateUtils.timeRangeLabel(start, end)
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
                            text = DateUtils.timeRangeLabel(bedtimeMillis, wakeTimeMillis),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.sleep_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.history_sleep_quality, qualityInt),
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
                    if (alreadyLogged) R.string.sleep_logged_title else R.string.sleep_heading,
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
            val haptic = LocalHapticFeedback.current
            var lastHapticQuality by remember(qualityInt) { mutableIntStateOf(qualityInt) }
            Slider(
                value = sleepQuality,
                onValueChange = { newValue ->
                    val newInt = newValue.roundToInt()
                    if (newInt != lastHapticQuality) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        lastHapticQuality = newInt
                    }
                    onQualityChanged(newValue)
                },
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
private fun CheckInAxisRow(
    axis: CheckInAxis,
    explicitValue: Int?,
    moodAnchor: Float,
    onValueChange: (Float) -> Unit,
    onClear: () -> Unit,
) {
    val isSet = explicitValue != null
    val displayed = explicitValue ?: CheckInDrafts.visualAnchor(axis.id, moodAnchor)
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember(axis.id) { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    var lastHaptic by remember(axis.id) { mutableIntStateOf(displayed) }

    LaunchedEffect(isDragged) {
        if (isDragged && explicitValue == null) {
            onValueChange(displayed.toFloat())
        }
    }

    LaunchedEffect(displayed, isSet) {
        if (!isSet) {
            lastHaptic = displayed
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = axis.name,
                style = MaterialTheme.typography.titleMedium,
            )
            if (isSet) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.check_in_clear_axis))
                }
            }
        }
        Text(
            text = if (isSet) displayed.toString() else stringResource(R.string.check_in_not_set),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = displayed.toFloat(),
            onValueChange = { newValue ->
                val newInt = newValue.roundToInt()
                if (newInt != lastHaptic) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    lastHaptic = newInt
                }
                onValueChange(newValue)
            },
            interactionSource = interactionSource,
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = axis.lowLabel,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = axis.highLabel,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
