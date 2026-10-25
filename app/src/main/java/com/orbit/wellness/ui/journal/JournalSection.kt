package com.orbit.wellness.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.DailyJournal
import com.orbit.wellness.ui.util.rememberHideKeyboard
import com.orbit.wellness.util.DateUtils
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun JournalSection(
    uiState: JournalUiState,
    onNoteChanged: (String) -> Unit,
    onSave: () -> Unit,
    onEdit: (DailyJournal) -> Unit,
    onRequestDelete: (DailyJournal) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val hideKeyboard = rememberHideKeyboard()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.journal_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.journal_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.showComposer) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (uiState.isEditing) {
                            stringResource(R.string.edit_journal_title)
                        } else {
                            stringResource(R.string.journal_label)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedTextField(
                        value = uiState.note,
                        onValueChange = onNoteChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.journal_label)) },
                        minLines = 4,
                        enabled = !uiState.isLoading,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                hideKeyboard()
                                onSave()
                            },
                            enabled = !uiState.isSaving &&
                                !uiState.isLoading &&
                                uiState.note.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.save_journal))
                        }
                        if (uiState.isEditing) {
                            TextButton(onClick = onCancelEdit) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }

        if (uiState.entries.isNotEmpty()) {
            Text(
                text = stringResource(R.string.journal_entries_title),
                style = MaterialTheme.typography.titleSmall,
            )
            uiState.entries.forEach { entry ->
                JournalEntryCard(
                    entry = entry,
                    isToday = entry.localDate == uiState.todayDate,
                    onEdit = { onEdit(entry) },
                    onDelete = { onRequestDelete(entry) },
                )
            }
        }
    }

    uiState.entryPendingDelete?.let {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.delete_entry_title)) },
            text = { Text(stringResource(R.string.delete_journal_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun JournalEntryCard(
    entry: DailyJournal,
    isToday: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isToday) {
                    stringResource(R.string.journal_today_label)
                } else {
                    formatJournalDate(entry.localDate)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.note,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

private fun formatJournalDate(localDate: String): String {
    val date = DateUtils.parseLocalDate(localDate)
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)
}
