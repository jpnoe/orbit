package com.orbit.wellness.ui.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.wellness.ui.activity.ActivityOptionPicker
import com.orbit.wellness.ui.activity.AddActivityNameDialog
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.rememberHideKeyboard

@Composable
fun ContextualActivityScreen(
    viewModel: ContextualActivityViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val hideKeyboard = rememberHideKeyboard()

    LaunchedEffect(viewModel) {
        viewModel.finished.collect { onFinished() }
    }

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (!uiState.loaded) return@Column

        if (uiState.missing) {
            Text(
                text = ContextOverviewCopy.MISSING,
                style = MaterialTheme.typography.headlineSmall,
            )
            return@Column
        }

        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        uiState.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "Choose what belonged to this stretch. There is no start or end time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ActivityOptionPicker(
            options = uiState.options,
            isSelected = { option -> option.id in uiState.selectedIds },
            onOptionClick = { option -> viewModel.toggle(option.id) },
            onAddClick = viewModel::openAddDialog,
        )

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveActivities()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save activities")
        }
    }

    if (uiState.showAddDialog) {
        AddActivityNameDialog(
            name = uiState.newActivityName,
            onNameChange = viewModel::onNewActivityNameChanged,
            onConfirm = {
                hideKeyboard()
                viewModel.confirmAddActivity()
            },
            onDismiss = viewModel::dismissAddDialog,
        )
    }
}
