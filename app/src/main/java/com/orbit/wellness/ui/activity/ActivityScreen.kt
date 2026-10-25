package com.orbit.wellness.ui.activity

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
import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.ui.checkin.moodDescriptorResId
import com.orbit.wellness.ui.history.EditActivityDialog
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.LoggedAtField
import com.orbit.wellness.ui.util.LoggedEntry
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedMessage = stringResource(R.string.activity_logged)
    val hideKeyboard = rememberHideKeyboard()
    val haptic = LocalHapticFeedback.current
    val moodInt = uiState.moodScore.roundToInt()
    var lastHapticMood by remember { mutableIntStateOf(moodInt) }
    var editingActivity by remember { mutableStateOf<ActivityEvent?>(null) }

    CollectSavedFeedback(
        pulses = viewModel.savedFeedback,
        message = savedMessage,
        snackbarHostState = snackbarHostState,
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.activity_tab_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.log_activity_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LoggedAtField(
            millis = uiState.loggedAtMillis,
            onChange = viewModel::onLoggedAtChanged,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.logMode == ActivityLogMode.Full,
                onClick = { viewModel.setLogMode(ActivityLogMode.Full) },
                label = { Text(stringResource(R.string.log_mode_full)) },
            )
            FilterChip(
                selected = uiState.logMode == ActivityLogMode.Session,
                onClick = { viewModel.setLogMode(ActivityLogMode.Session) },
                label = { Text(stringResource(R.string.log_mode_session)) },
            )
        }

        Text(
            text = stringResource(
                if (uiState.logMode == ActivityLogMode.Full) {
                    R.string.log_mode_full_activity_hint
                } else {
                    R.string.log_mode_session_activity_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.isLoading) {
            InlineLoading()
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.activityOptions.forEach { option ->
                    val isActive = uiState.activeActivities.any {
                        it.activityName.equals(option.name, ignoreCase = true)
                    }
                    val selected = when (uiState.logMode) {
                        ActivityLogMode.Full ->
                            uiState.selectedName.equals(option.name, ignoreCase = true)
                        ActivityLogMode.Session -> isActive
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onActivityChipTapped(option.name) },
                        label = {
                            Text(
                                text = if (uiState.logMode == ActivityLogMode.Session && isActive) {
                                    stringResource(R.string.activity_active_label, option.name)
                                } else {
                                    option.name
                                },
                            )
                        },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = viewModel::openAddActivityDialog,
                    label = { Text(stringResource(R.string.add_activity)) },
                )
            }
        }

        if (uiState.logMode == ActivityLogMode.Full) {
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
                    viewModel.logFullActivity()
                },
                enabled = !uiState.isSaving && uiState.selectedName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.log_activity_now))
            }
        }

        if (uiState.recentActivities.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recent_activities),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.recentActivities.forEach { event ->
                val footer = if (event.preMood != null && event.postMood != null) {
                    "mood ${event.preMood} → ${event.postMood}"
                } else {
                    event.postMood?.let { "mood $it" }
                }
                LoggedEntry(
                    timeLabel = DateUtils.sessionTimeLabel(event.startTime, event.endTime),
                    title = event.activityName,
                    footer = footer,
                    onClick = { editingActivity = event },
                )
            }
        }
    }

    if (uiState.moodPrompt != null && uiState.pendingActivityName != null) {
        ActivityMoodSheet(
            prompt = uiState.moodPrompt!!,
            activityName = uiState.pendingActivityName!!,
            moodScore = uiState.promptMoodScore,
            onMoodChanged = viewModel::onPromptMoodChanged,
            onConfirm = {
                hideKeyboard()
                viewModel.confirmMoodPrompt()
            },
            onDismiss = viewModel::dismissMoodPrompt,
        )
    }

    if (uiState.showAddActivityDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddActivityDialog,
            title = { Text(stringResource(R.string.add_activity_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.newActivityName,
                    onValueChange = viewModel::onNewActivityNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_activity_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.confirmAddActivity()
                    },
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddActivityDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editingActivity?.let { event ->
        EditActivityDialog(
            event = event,
            onConfirm = { pre, post ->
                viewModel.updateActivity(event, pre, post)
                editingActivity = null
            },
            onDismiss = { editingActivity = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityMoodSheet(
    prompt: ActivityMoodPrompt,
    activityName: String,
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
                    ActivityMoodPrompt.Pre ->
                        stringResource(R.string.pre_activity_mood_title, activityName)
                    ActivityMoodPrompt.Post ->
                        stringResource(R.string.post_activity_mood_title, activityName)
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
                            ActivityMoodPrompt.Pre -> stringResource(R.string.start_activity)
                            ActivityMoodPrompt.Post -> stringResource(R.string.finish_activity)
                        },
                    )
                }
            }
        }
    }
}
