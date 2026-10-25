package com.orbit.wellness.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun SettingsSection(
    reminderTime: LocalTime,
    onReminderTimeChanged: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val timeLabel = remember(reminderTime) {
        reminderTime.format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.reminder_time_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.reminder_time_subtitle, timeLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.reminder_time_change))
        }
    }

    if (showPicker) {
        ReminderTimePickerDialog(
            initial = reminderTime,
            onDismiss = { showPicker = false },
            onConfirm = { time ->
                onReminderTimeChanged(time)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_time_picker_title)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) },
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
