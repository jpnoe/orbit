package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.ui.util.InlineLoading

@Composable
fun ResearchPreviewScreen(
    viewModel: ResearchPreviewViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back))
        }
        Text(
            text = stringResource(R.string.research_preview_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        when (val state = uiState) {
            ResearchPreviewUiState.Loading -> InlineLoading()
            is ResearchPreviewUiState.Ready -> StateMapSection(
                content = state.stateMap,
                selectedKey = state.selectedKey,
                onSelect = viewModel::selectState,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
