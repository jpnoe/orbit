package com.orbit.wellness.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.ui.util.CollectSavedFeedback

@Composable
fun JournalScreen(
    viewModel: JournalViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedMessage = stringResource(R.string.journal_saved)

    CollectSavedFeedback(
        pulses = viewModel.savedFeedback,
        message = savedMessage,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        JournalSection(
            uiState = uiState,
            onNoteChanged = viewModel::onNoteChanged,
            onSave = viewModel::saveJournal,
            onEdit = viewModel::startEdit,
            onRequestDelete = viewModel::requestDelete,
            onDismissDelete = viewModel::dismissDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelEdit = viewModel::cancelEdit,
        )
    }
}
