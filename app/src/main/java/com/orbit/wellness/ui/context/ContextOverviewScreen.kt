package com.orbit.wellness.ui.context

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.data.local.CheckInContextCategories
import com.orbit.wellness.ui.util.CollectSnackbarMessages

@Composable
fun ContextOverviewScreen(
    viewModel: ContextOverviewViewModel,
    snackbarHostState: SnackbarHostState,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
    discarding: Boolean = false,
    onOpenActivity: () -> Unit,
    onOpenWorkout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val overview = uiState.overview
    var confirmCancel by remember { mutableStateOf(false) }
    val busy = uiState.completing || discarding

    BackHandler(enabled = !confirmCancel && !busy) {
        confirmCancel = true
    }

    LaunchedEffect(viewModel) {
        viewModel.finished.collect { onDone() }
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
        if (!uiState.loaded) return@Column

        Text(
            text = overview.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        overview.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (!overview.missing) {
            Text(
                text = "What happened in this stretch? This is not a minute-by-minute log.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!overview.missing) {
            overview.cards.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { card ->
                        ContextCategoryCard(
                            card = card,
                            onClick = when {
                                card.category == CheckInContextCategories.ACTIVITY && card.interactive ->
                                    onOpenActivity
                                card.category == CheckInContextCategories.WORKOUT && card.interactive ->
                                    onOpenWorkout
                                else -> null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }

        Button(
            onClick = {
                if (overview.missing) onDone() else viewModel.finish()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.save_checkin))
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
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.check_in_discard_title)) },
            text = { Text(stringResource(R.string.check_in_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        onDiscard()
                    },
                ) {
                    Text(stringResource(R.string.check_in_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) {
                    Text(stringResource(R.string.check_in_keep_editing))
                }
            },
        )
    }
}

@Composable
private fun ContextCategoryCard(
    card: ContextCategoryCardModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container = if (card.interactive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = modifier
            .heightIn(min = 148.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = card.statusText,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (card.comingNext) {
                Text(
                    text = ContextOverviewCopy.COMING_NEXT,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
