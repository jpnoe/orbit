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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orbit.wellness.R
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.ui.checkin.moodDescriptorResId
import com.orbit.wellness.ui.history.EditSubstanceUseDialog
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.util.LoggedAtField
import com.orbit.wellness.ui.util.LoggedEntry
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UseScreen(
    viewModel: UseViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedMessage = stringResource(R.string.use_saved)
    val haptic = LocalHapticFeedback.current
    val hideKeyboard = rememberHideKeyboard()
    val moodInt = uiState.moodScore.roundToInt()
    var lastHapticMood by remember { mutableIntStateOf(moodInt) }
    var editingUse by remember { mutableStateOf<SubstanceUseEvent?>(null) }

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
            text = stringResource(R.string.use_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.use_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LoggedAtField(
            millis = uiState.loggedAtMillis,
            onChange = viewModel::onLoggedAtChanged,
        )

        Text(
            text = stringResource(R.string.use_intent_label),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.intent == SubstanceIntent.SUPPORTIVE,
                onClick = { viewModel.onIntentSelected(SubstanceIntent.SUPPORTIVE) },
                label = { Text(stringResource(R.string.intent_supportive)) },
            )
            FilterChip(
                selected = uiState.intent == SubstanceIntent.COPING,
                onClick = { viewModel.onIntentSelected(SubstanceIntent.COPING) },
                label = { Text(stringResource(R.string.intent_coping)) },
            )
        }

        if (uiState.isLoading) {
            InlineLoading()
        } else if (uiState.options.isEmpty()) {
            Text(
                text = if (uiState.intent == SubstanceIntent.SUPPORTIVE) {
                    stringResource(R.string.use_empty_supportive)
                } else {
                    stringResource(R.string.use_empty_coping)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.quick_add),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.options.forEach { option ->
                    FilterChip(
                        selected = uiState.name == option.name,
                        onClick = { viewModel.applyOption(option) },
                        label = { Text(option.name) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = uiState.name,
            onValueChange = viewModel::onNameChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.use_name)) },
            singleLine = true,
            readOnly = true,
        )
        OutlinedTextField(
            value = uiState.amount,
            onValueChange = viewModel::onAmountChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.use_amount)) },
            singleLine = true,
        )

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = stringResource(R.string.mood_low), style = MaterialTheme.typography.labelMedium)
                Text(text = stringResource(R.string.mood_high), style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = uiState.moodScore,
                onValueChange = { newValue ->
                    val newInt = newValue.roundToInt()
                    if (newInt != lastHapticMood) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastHapticMood = newInt
                    }
                    viewModel.onMoodChanged(newValue)
                },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider()

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveUse()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_use))
        }

        if (uiState.recentEvents.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recent_use),
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.recentEvents.forEach { event ->
                val intentLabel = when (event.intent) {
                    SubstanceIntent.SUPPORTIVE.name -> stringResource(R.string.intent_supportive)
                    else -> stringResource(R.string.intent_coping)
                }
                val amountPart = event.amount.takeIf { it.isNotBlank() }
                LoggedEntry(
                    timeLabel = DateUtils.shortDateTimeLabel(event.timestamp),
                    title = event.name,
                    footer = buildString {
                        append(intentLabel)
                        if (amountPart != null) {
                            append(" · ")
                            append(amountPart)
                        }
                        append(" · mood ")
                        append(event.moodScore)
                    },
                    onClick = { editingUse = event },
                )
            }
        }
    }

    editingUse?.let { event ->
        EditSubstanceUseDialog(
            event = event,
            onConfirm = { name, amount, mood, intent ->
                viewModel.updateUse(event, name, amount, mood, intent)
                editingUse = null
            },
            onDismiss = { editingUse = null },
        )
    }
}
