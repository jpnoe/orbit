package com.orbit.wellness.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.HistoryItem
import com.orbit.wellness.data.local.ActivityEvent
import com.orbit.wellness.data.local.MealEvent
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.data.local.SleepEvent
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.WorkoutEvent
import com.orbit.wellness.ui.trends.TrendsScreen
import com.orbit.wellness.ui.trends.TrendsViewModel
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.util.DateUtils
import com.orbit.wellness.util.VolumeUnits
import kotlin.math.roundToInt

private enum class HistoryTab {
    LOG,
    TRENDS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyViewModel: HistoryViewModel,
    trendsViewModel: TrendsViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val feed by historyViewModel.feed.collectAsState()
    val uiState by historyViewModel.uiState.collectAsState()
    val trendsUiState by trendsViewModel.uiState.collectAsState()
    val journalSaved = stringResource(R.string.journal_saved)
    var selectedTab by remember { mutableStateOf(HistoryTab.LOG) }
    val showTrendsTab = trendsUiState.hasEnoughData

    CollectSavedFeedback(
        pulses = historyViewModel.savedFeedback,
        message = journalSaved,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (embedded) 0.dp else 24.dp)
                .padding(
                    top = if (embedded) 0.dp else 20.dp,
                    bottom = 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!embedded) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (showTrendsTab) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedTab == HistoryTab.LOG,
                        onClick = { selectedTab = HistoryTab.LOG },
                        label = { Text(stringResource(R.string.history_tab_log)) },
                    )
                    FilterChip(
                        selected = selectedTab == HistoryTab.TRENDS,
                        onClick = { selectedTab = HistoryTab.TRENDS },
                        label = { Text(stringResource(R.string.history_tab_trends)) },
                    )
                }
            }
        }

        val currentTab = if (showTrendsTab) selectedTab else HistoryTab.LOG

        when (currentTab) {
            HistoryTab.LOG -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = if (embedded) 0.dp else 24.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.history_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    when {
                        feed.isLoading -> {
                            item { InlineLoading() }
                        }
                        feed.items.isEmpty() -> {
                            item {
                                Text(
                                    text = stringResource(R.string.history_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                        else -> {
                            items(feed.items, key = { it.id }) { item ->
                                HistoryItemCard(
                                    item = item,
                                    onClick = { historyViewModel.onSelectEdit(item) },
                                    onLongClick = { historyViewModel.onLongPressItem(item) },
                                )
                            }
                        }
                    }
                }
            }
            HistoryTab.TRENDS -> {
                TrendsScreen(
                    viewModel = trendsViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // Long press options bottom sheet
    if (uiState.selectedItemForOptions != null) {
        ModalBottomSheet(
            onDismissRequest = historyViewModel::dismissOptions,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit),
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedButton(
                    onClick = historyViewModel::onSelectEditFromOptions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(
                        text = stringResource(R.string.edit),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Button(
                    onClick = historyViewModel::onSelectDeleteFromOptions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(
                        text = stringResource(R.string.delete),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (uiState.itemToDelete != null) {
        AlertDialog(
            onDismissRequest = historyViewModel::dismissDeleteDialog,
            title = { Text(stringResource(R.string.delete_entry_title)) },
            text = { Text(stringResource(R.string.delete_entry_message)) },
            confirmButton = {
                TextButton(onClick = historyViewModel::confirmDelete) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = historyViewModel::dismissDeleteDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Edit dialogs
    uiState.itemToEdit?.let { item ->
        when (item) {
            is HistoryItem.Mood -> {
                EditMoodDialog(
                    sample = item.sample,
                    onConfirm = { newScore, note ->
                        historyViewModel.updateMood(item.sample, newScore, note)
                    },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
            is HistoryItem.Activity -> {
                EditActivityDialog(
                    event = item.event,
                    onConfirm = { pre, post -> historyViewModel.updateActivity(item.event, pre, post) },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
            is HistoryItem.Meal -> {
                EditMealDialog(
                    event = item.event,
                    onConfirm = { label -> historyViewModel.updateMeal(item.event, label) },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
            is HistoryItem.Workout -> {
                EditWorkoutDialog(
                    event = item.event,
                    onConfirm = { duration, notes -> historyViewModel.updateWorkout(item.event, duration, notes) },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
            is HistoryItem.SubstanceUse -> {
                EditSubstanceUseDialog(
                    event = item.event,
                    onConfirm = { name, amount, mood, intent ->
                        historyViewModel.updateSubstanceUse(item.event, name, amount, mood, intent)
                    },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
            is HistoryItem.Sleep -> {
                EditSleepDialog(
                    event = item.event,
                    onConfirm = { bedtime, wake, quality ->
                        historyViewModel.updateSleep(item.event, bedtime, wake, quality)
                    },
                    onDismiss = historyViewModel::dismissEditDialog,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemCard(
    item: HistoryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val timeLabel = when (item) {
        is HistoryItem.Activity ->
            DateUtils.sessionTimeLabel(item.event.startTime, item.event.endTime)
        is HistoryItem.Workout ->
            DateUtils.sessionTimeLabel(item.event.startTime, item.event.endTime)
        else -> DateUtils.shortDateTimeLabel(item.timestamp)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (item) {
                is HistoryItem.Mood -> {
                    val moodTitle = if (item.sample.emotion.isNotBlank()) {
                        "${stringResource(R.string.history_mood, item.sample.moodScore)} · ${item.sample.emotion}"
                    } else {
                        stringResource(R.string.history_mood, item.sample.moodScore)
                    }
                    Text(
                        text = moodTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = sourceLabel(item.sample.source),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (item.sample.note.isNotBlank()) {
                        Text(
                            text = item.sample.note,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is HistoryItem.Activity -> {
                    val text = if (item.event.endTime == null) {
                        stringResource(
                            R.string.history_activity_active,
                            item.event.activityName.uppercase(),
                        )
                    } else {
                        val preLabel = item.event.preMood?.toString() ?: "?"
                        val postLabel = item.event.postMood?.toString() ?: "?"
                        stringResource(
                            R.string.history_activity_done,
                            item.event.activityName.uppercase(),
                            preLabel,
                            postLabel,
                        )
                    }
                    Text(text = text, style = MaterialTheme.typography.titleMedium)
                }
                is HistoryItem.Meal -> {
                    val itemLines = if (item.items.isNotEmpty()) {
                        item.items.map { mealItem ->
                            buildString {
                                append(mealItem.foodName)
                                append(" · ")
                                append(VolumeUnits.formatAmount(mealItem.amountValue))
                                append(' ')
                                append(VolumeUnits.label(mealItem.amountUnit))
                                mealItem.calories?.let { cal ->
                                    append(" · ")
                                    append(cal.toInt())
                                    append(" kcal")
                                }
                            }
                        }
                    } else {
                        item.event.summaryTitle
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    }
                    itemLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.history_meal_summary,
                            item.event.itemCount,
                            item.event.calories?.toInt() ?: 0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.event.label.isNotBlank()) {
                        Text(
                            text = item.event.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is HistoryItem.Workout -> {
                    val detail = buildString {
                        append(item.event.workoutType.replace('_', ' '))
                        append(" · ")
                        append(item.event.durationMinutes)
                        append(" min")
                        item.event.cardioActivity?.let {
                            append(" · ")
                            append(it)
                        }
                        if (item.event.muscleGroups.isNotBlank()) {
                            append(" · ")
                            append(item.event.muscleGroups)
                        }
                        when {
                            item.event.endTime == null -> {
                                append(" · TBD (in progress)")
                            }
                            item.event.preMood != null && item.event.postMood != null &&
                                kotlin.math.abs(item.event.endTime - item.event.startTime) >= 60_000L -> {
                                append(" · mood ")
                                append(item.event.preMood)
                                append(" → ")
                                append(item.event.postMood)
                            }
                            item.event.postMood != null -> {
                                append(" · mood ")
                                append(item.event.postMood)
                            }
                        }
                    }
                    Text(text = detail, style = MaterialTheme.typography.titleMedium)
                }
                is HistoryItem.SubstanceUse -> {
                    val intentLabel = when (item.event.intent) {
                        SubstanceIntent.SUPPORTIVE.name -> stringResource(R.string.intent_supportive)
                        else -> stringResource(R.string.intent_coping)
                    }
                    Text(
                        text = stringResource(
                            R.string.history_use,
                            intentLabel,
                            item.event.name,
                            item.event.amount.ifBlank { "—" },
                            item.event.moodScore,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                is HistoryItem.Sleep -> {
                    Text(
                        text = stringResource(R.string.history_sleep_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_sleep_range,
                            DateUtils.shortDateTimeLabel(item.event.bedtime),
                            DateUtils.shortDateTimeLabel(item.event.wakeTime),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_sleep_quality,
                            item.event.sleepQuality,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun EditMoodDialog(
    sample: MoodSample,
    onConfirm: (score: Int, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var score by remember { mutableFloatStateOf(sample.moodScore.toFloat()) }
    var note by remember { mutableStateOf(sample.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_mood_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = score.roundToInt().toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = score,
                    onValueChange = { score = it },
                    valueRange = 0f..10f,
                    steps = 9,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.mood_note_optional)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(score.roundToInt(), note) }) {
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
fun EditActivityDialog(
    event: ActivityEvent,
    onConfirm: (pre: Int?, post: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var preMood by remember { mutableFloatStateOf((event.preMood ?: 5).toFloat()) }
    var postMood by remember { mutableFloatStateOf((event.postMood ?: 5).toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_activity_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.pre_mood) + ": ${preMood.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = preMood,
                    onValueChange = { preMood = it },
                    valueRange = 0f..10f,
                    steps = 9,
                )

                if (event.endTime != null) {
                    Text(
                        text = stringResource(R.string.post_mood) + ": ${postMood.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = postMood,
                        onValueChange = { postMood = it },
                        valueRange = 0f..10f,
                        steps = 9,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val pre = preMood.roundToInt()
                    val post = if (event.endTime != null) postMood.roundToInt() else null
                    onConfirm(pre, post)
                },
            ) {
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
fun EditMealDialog(
    event: MealEvent,
    onConfirm: (label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var labelText by remember { mutableStateOf(event.label) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_meal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.history_meal_summary,
                        event.itemCount,
                        event.calories?.toInt() ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (event.summaryTitle.isNotBlank()) {
                    Text(
                        text = event.summaryTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text(stringResource(R.string.meal_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(labelText) }) {
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
fun EditWorkoutDialog(
    event: WorkoutEvent,
    onConfirm: (durationMinutes: Int, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationText by remember { mutableStateOf(event.durationMinutes.toString()) }
    var notesText by remember { mutableStateOf(event.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_workout_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text(stringResource(R.string.duration_minutes)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.workout_notes)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val duration = durationText.toIntOrNull() ?: event.durationMinutes
                    onConfirm(duration, notesText)
                },
            ) {
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
fun EditSubstanceUseDialog(
    event: SubstanceUseEvent,
    onConfirm: (name: String, amount: String, moodScore: Int, intent: SubstanceIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(event.name) }
    var amount by remember { mutableStateOf(event.amount) }
    var mood by remember { mutableFloatStateOf(event.moodScore.toFloat()) }
    var intent by remember {
        mutableStateOf(
            runCatching { SubstanceIntent.valueOf(event.intent) }
                .getOrDefault(SubstanceIntent.SUPPORTIVE),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_use_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = intent == SubstanceIntent.SUPPORTIVE,
                        onClick = { intent = SubstanceIntent.SUPPORTIVE },
                        label = { Text(stringResource(R.string.intent_supportive)) },
                    )
                    FilterChip(
                        selected = intent == SubstanceIntent.COPING,
                        onClick = { intent = SubstanceIntent.COPING },
                        label = { Text(stringResource(R.string.intent_coping)) },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.use_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.use_amount)) },
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.use_mood_label) + ": ${mood.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = mood,
                    onValueChange = { mood = it },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, amount, mood.roundToInt(), intent) },
            ) {
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
private fun EditSleepDialog(
    event: SleepEvent,
    onConfirm: (bedtime: Long, wakeTime: Long, sleepQuality: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var quality by remember { mutableFloatStateOf(event.sleepQuality.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_sleep_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.history_sleep_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        R.string.history_sleep_range,
                        DateUtils.shortDateTimeLabel(event.bedtime),
                        DateUtils.shortDateTimeLabel(event.wakeTime),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.history_sleep_quality,
                        quality.roundToInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(event.bedtime, event.wakeTime, quality.roundToInt())
                },
            ) {
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
private fun sourceLabel(source: String): String {
    return when (source) {
        MoodSample.SOURCE_ACTIVITY_PRE -> stringResource(R.string.mood_source_pre)
        MoodSample.SOURCE_ACTIVITY_POST -> stringResource(R.string.mood_source_post)
        else -> stringResource(R.string.mood_source_check_in)
    }
}
