package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R
import com.orbit.wellness.ui.util.InlineLoading

enum class ResearchPane {
    STATE_MAP,
    ASSOCIATIONS,
    PREDICTION,
}

@Composable
fun ResearchPreviewScreen(
    stateMapViewModel: ResearchPreviewViewModel,
    associationViewModel: BehaviorAssociationViewModel,
    walkForwardViewModel: WalkForwardViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pane by remember { mutableStateOf(ResearchPane.STATE_MAP) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = pane == ResearchPane.STATE_MAP,
                onClick = { pane = ResearchPane.STATE_MAP },
                label = { Text(BehaviorAssociationCopy.STATE_MAP_PANE) },
            )
            FilterChip(
                selected = pane == ResearchPane.ASSOCIATIONS,
                onClick = { pane = ResearchPane.ASSOCIATIONS },
                label = { Text(BehaviorAssociationCopy.ASSOCIATIONS_PANE) },
            )
            FilterChip(
                selected = pane == ResearchPane.PREDICTION,
                onClick = { pane = ResearchPane.PREDICTION },
                label = { Text(PredictionLabCopy.PANE) },
            )
        }
        when (pane) {
            ResearchPane.STATE_MAP -> StateMapResearchPane(stateMapViewModel)
            ResearchPane.ASSOCIATIONS -> AssociationResearchPane(associationViewModel)
            ResearchPane.PREDICTION -> PredictionResearchPane(walkForwardViewModel)
        }
    }
}

@Composable
private fun StateMapResearchPane(stateMapViewModel: ResearchPreviewViewModel) {
    val uiState by stateMapViewModel.uiState.collectAsState()
    when (val state = uiState) {
        ResearchPreviewUiState.Loading -> InlineLoading()
        is ResearchPreviewUiState.Ready -> StateMapSection(
            content = state.stateMap,
            selectedKey = state.selectedKey,
            onSelect = stateMapViewModel::selectState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AssociationResearchPane(associationViewModel: BehaviorAssociationViewModel) {
    val uiState by associationViewModel.uiState.collectAsState()
    when (val state = uiState) {
        BehaviorAssociationUiState.Loading -> InlineLoading()
        is BehaviorAssociationUiState.Ready -> BehaviorAssociationSection(
            content = state.content,
            selectedIdentity = state.selectedIdentity,
            onSelect = associationViewModel::selectBehavior,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
