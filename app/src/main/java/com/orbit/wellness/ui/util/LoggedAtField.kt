package com.orbit.wellness.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
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
import com.orbit.wellness.util.DateUtils
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggedAtField(
    millis: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val dateLabel = remember(millis) {
        DateUtils.localDateOf(millis).format(DateTimeFormatter.ofPattern("EEE MMM d"))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.logged_at_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showDate = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(dateLabel)
            }
            OutlinedButton(
                onClick = { showTime = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(DateUtils.formatTime(millis))
            }
        }
    }

    if (showDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.utcMidnightMillis(millis),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { utc ->
                            onChange(DateUtils.withDateUtcMidnight(millis, utc))
                        }
                        showDate = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = DateUtils.hourOf(millis),
            initialMinute = DateUtils.minuteOf(millis),
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.logged_at_time_title)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(DateUtils.withHourMinute(millis, timeState.hour, timeState.minute))
                        showTime = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
