package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.ui.util.InlineLoading

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.insights_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        when (val state = uiState) {
            InsightsUiState.Loading -> InlineLoading()
            is InsightsUiState.Ready -> InsightsBody(state.baseline, onOpenLog)
        }
    }
}

@Composable
private fun InsightsBody(
    baseline: InsightsBaseline,
    onOpenLog: () -> Unit,
) {
    Text(
        text = stringResource(R.string.insights_heading),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(R.string.insights_support),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (baseline.kind == InsightsBaselineKind.EMPTY) {
        Text(
            text = stringResource(R.string.insights_empty_invite),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onOpenLog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.insights_open_log))
        }
    }
    SummaryRow(R.string.insights_check_ins, baseline.checkInCount)
    SummaryRow(R.string.insights_days, baseline.dayCount)
    Text(
        text = stringResource(R.string.insights_data_available),
        style = MaterialTheme.typography.titleSmall,
    )
    AvailabilityRow(R.string.insights_data_check_ins, baseline.checkInState)
    AvailabilityRow(R.string.insights_data_context, baseline.context)
    AvailabilityRow(R.string.insights_data_wearables, baseline.wearables)
    Text(
        text = stringResource(R.string.insights_closing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SummaryRow(labelRes: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun AvailabilityRow(labelRes: Int, availability: RecordedAvailability) {
    val recorded = availability == RecordedAvailability.RECORDED
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                if (recorded) R.string.insights_recorded else R.string.insights_not_yet_recorded,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = if (recorded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
