package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.util.DateUtils

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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.insights_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        when (val state = uiState) {
            InsightsUiState.Loading -> InlineLoading()
            is InsightsUiState.Ready -> InsightsBody(state.content, onOpenLog)
        }
    }
}

@Composable
private fun InsightsBody(
    content: InsightsContent,
    onOpenLog: () -> Unit,
) {
    if (content.phase == InsightsPhase.EMPTY) {
        Text(
            text = stringResource(R.string.insights_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.insights_empty_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onOpenLog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.insights_open_log))
        }
        return
    }

    Text(
        text = stringResource(R.string.insights_collecting),
        style = MaterialTheme.typography.titleMedium,
    )
    if (content.phase == InsightsPhase.ONE_OBSERVATION) {
        Text(
            text = stringResource(R.string.insights_one_observation),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    CountLine(R.string.insights_states, content.stateObservationCount)
    CountLine(R.string.insights_completed_intervals, content.completedIntervalCount)
    CountLine(R.string.insights_linked_transitions, content.linkedTransitionCount)
    CountLine(R.string.insights_measured_transitions, content.measuredTransitionCount)
    val start = content.earliestRecordedAt
    val end = content.latestRecordedAt
    if (start != null && end != null) {
        val span = if (start == end) {
            DateUtils.datedTimeLabel(start)
        } else {
            DateUtils.timeRangeLabel(start, end)
        }
        Text(
            text = stringResource(R.string.insights_span, span),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SectionTitle(R.string.insights_temporal_heading)
    Text(
        text = stringResource(R.string.insights_calendar_day_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CountLine(R.string.insights_moments, content.momentObservations)
    CountLine(R.string.insights_calendar_days, content.calendarDaysRepresented)
    CountLine(R.string.insights_contained_intervals, content.fullyContainedIntervals)
    CountLine(R.string.insights_overlapping_intervals, content.boundaryOverlappingIntervals)

    SectionTitle(R.string.insights_coverage)
    content.coreCoverage.forEach { coverage ->
        val name = DefaultCheckInAxes.ALL.firstOrNull { it.id == coverage.axisId }?.name
            ?: coverage.axisId
        Text(
            text = stringResource(
                R.string.insights_axis_coverage,
                name,
                coverage.recordedCount,
                coverage.missingCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    SectionTitle(R.string.insights_emotion_heading)
    Text(
        text = stringResource(R.string.insights_emotion_note),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = if (content.recordedEmotions.isEmpty()) {
            stringResource(R.string.insights_emotion_none)
        } else {
            stringResource(R.string.insights_emotion_names, content.recordedEmotions.joinToString())
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    SectionTitle(R.string.insights_context_heading)
    CountLine(R.string.insights_intervals_activity, content.intervalsContainingActivity)
    CountLine(R.string.insights_intervals_workout, content.intervalsContainingWorkout)
    CountLine(R.string.insights_intervals_meals, content.intervalsContainingMeals)
    CountLine(R.string.insights_intervals_usage, content.intervalsContainingUsage)

    SectionTitle(R.string.insights_quality_heading)
    if (content.issues.isEmpty()) {
        Text(
            text = stringResource(R.string.insights_no_issues),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        var expanded by remember { mutableStateOf(false) }
        Text(
            text = stringResource(R.string.insights_issue_count, content.issues.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    if (expanded) R.string.insights_hide_issues else R.string.insights_show_issues,
                ),
            )
        }
        if (expanded) {
            content.issues.forEach { issue ->
                val id = issue.checkInId?.let { " · $it" }.orEmpty()
                Text(
                    text = "${issue.code.name}$id · ${issue.detail}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.insights_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun CountLine(labelRes: Int, count: Int) {
    Text(
        text = stringResource(labelRes, count),
        style = MaterialTheme.typography.bodyMedium,
    )
}
