package com.orbit.wellness.ui.context

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.rememberHideKeyboard

@Composable
fun ContextualUsageScreen(
    viewModel: ContextualUsageViewModel,
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
                text = stringResource(R.string.usage_title),
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
            text = stringResource(R.string.usage_context_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        UsageSection(
            title = stringResource(R.string.intent_supportive),
            intent = SubstanceIntent.SUPPORTIVE,
            options = uiState.options,
            lines = uiState.lines,
            enabled = !uiState.isSaving,
            onToggle = viewModel::toggle,
            onAmountChanged = viewModel::onAmountChanged,
            onRemove = viewModel::remove,
            onAdd = { viewModel.openAddDialog(SubstanceIntent.SUPPORTIVE) },
        )
        UsageSection(
            title = stringResource(R.string.intent_coping),
            intent = SubstanceIntent.COPING,
            options = uiState.options,
            lines = uiState.lines,
            enabled = !uiState.isSaving,
            onToggle = viewModel::toggle,
            onAmountChanged = viewModel::onAmountChanged,
            onRemove = viewModel::remove,
            onAdd = { viewModel.openAddDialog(SubstanceIntent.COPING) },
        )

        Button(
            onClick = {
                hideKeyboard()
                viewModel.saveUsage()
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_usage))
        }
    }

    uiState.addIntent?.let { intent ->
        AddUsageOptionDialog(
            title = stringResource(
                if (intent == SubstanceIntent.SUPPORTIVE) {
                    R.string.add_supportive_option
                } else {
                    R.string.add_coping_option
                },
            ),
            name = uiState.newOptionName,
            onNameChange = viewModel::onNewOptionNameChanged,
            onConfirm = {
                hideKeyboard()
                viewModel.confirmAddOption()
            },
            onDismiss = viewModel::dismissAddDialog,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageSection(
    title: String,
    intent: SubstanceIntent,
    options: List<SubstanceOption>,
    lines: List<ContextualUsageLine>,
    enabled: Boolean,
    onToggle: (SubstanceOption) -> Unit,
    onAmountChanged: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val sectionOptions = options.filter { it.intent == intent.name }
    val sectionLines = lines.filter { it.intent == intent.name }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sectionOptions.forEach { option ->
                FilterChip(
                    selected = lines.any { it.substanceOptionId == option.id },
                    onClick = { if (enabled) onToggle(option) },
                    enabled = enabled,
                    label = { Text(option.name) },
                )
            }
            FilterChip(
                selected = false,
                onClick = { if (enabled) onAdd() },
                enabled = enabled,
                label = { Text(stringResource(R.string.add_activity)) },
            )
        }
        sectionLines.forEach { line ->
            UsageAmountField(
                line = line,
                enabled = enabled,
                onAmountChanged = onAmountChanged,
                onRemove = onRemove,
                showRemove = sectionOptions.none { it.id == line.substanceOptionId },
            )
        }
    }
}

@Composable
private fun UsageAmountField(
    line: ContextualUsageLine,
    enabled: Boolean,
    onAmountChanged: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    showRemove: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = line.amount,
            onValueChange = { onAmountChanged(line.key, it) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = {
                Text("${line.name} · ${stringResource(R.string.usage_amount_optional)}")
            },
        )
        if (showRemove) {
            TextButton(
                onClick = { onRemove(line.key) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.check_in_clear_axis))
            }
        }
    }
}

@Composable
private fun AddUsageOptionDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.usage_title)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
