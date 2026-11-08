package com.orbit.wellness.ui.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orbit.wellness.domain.checkin.BehaviorIdentity

@Composable
fun BehaviorAssociationSection(
    content: BehaviorAssociationContent,
    selectedIdentity: BehaviorIdentity?,
    onSelect: (BehaviorIdentity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = content.protocol, style = MaterialTheme.typography.titleMedium)
        Text(text = "Behaviors ${content.behaviorCount}", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Intervals ${content.intervalCount}", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Calendar days ${content.calendarDayCount}", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Excluded intervals ${content.excludedIntervalCount}", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = content.notice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.profiles.isEmpty() || selectedIdentity == null) {
            Text(text = content.emptyMessage, style = MaterialTheme.typography.bodyLarge)
            return
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.profiles.forEach { profile ->
                FilterChip(
                    selected = profile.identity == selectedIdentity,
                    onClick = { onSelect(profile.identity) },
                    label = { Text(profile.selectorLabel) },
                )
            }
        }
        val profile = content.profiles.first { it.identity == selectedIdentity }
        ProfileDetail(profile)
    }
}

@Composable
private fun ProfileDetail(profile: BehaviorAssociationProfileView) {
    var showSpread by remember(profile.identity) { mutableStateOf(false) }
    var showEmotions by remember(profile.identity) { mutableStateOf(false) }
    var showContext by remember(profile.identity) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = profile.displayName, style = MaterialTheme.typography.titleLarge)
        Text(text = profile.kindLabel, style = MaterialTheme.typography.bodyLarge)
        if (profile.intentLabel != null) {
            Text(text = profile.intentLabel, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = BehaviorAssociationCopy.IDENTITY_KEY,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = profile.identityKey,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SupportBlock(profile.support)
        profile.axes.forEach { axis ->
            Text(text = axis.label, style = MaterialTheme.typography.titleMedium)
            axis.lenses.forEach { lens ->
                LensBlock(lens, showSpread)
            }
        }
        TextButton(onClick = { showSpread = !showSpread }) {
            Text(BehaviorAssociationCopy.SPREAD)
        }
        Text(text = profile.emotions.starting.label, style = MaterialTheme.typography.titleMedium)
        EmotionSide(BehaviorAssociationCopy.PRESENT, profile.emotions.starting.present, showEmotions)
        EmotionSide(BehaviorAssociationCopy.ABSENT, profile.emotions.starting.absent, showEmotions)
        Text(text = profile.emotions.ending.label, style = MaterialTheme.typography.titleMedium)
        EmotionSide(BehaviorAssociationCopy.PRESENT, profile.emotions.ending.present, showEmotions)
        EmotionSide(BehaviorAssociationCopy.ABSENT, profile.emotions.ending.absent, showEmotions)
        TextButton(onClick = { showEmotions = !showEmotions }) {
            Text(BehaviorAssociationCopy.EMOTION_TOKENS)
        }
        CalendarBlock(profile.calendar)
        QuantityBlock(profile.quantity)
        TextButton(onClick = { showContext = !showContext }) {
            Text(BehaviorAssociationCopy.CONTEXT_BALANCE)
        }
        if (showContext) {
            ContextBalanceBlock(profile.context)
        }
    }
}

@Composable
private fun SupportBlock(support: SupportPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Present intervals ${support.presentIntervals}")
        Text("Absent intervals ${support.absentIntervals}")
        Text("Unknown intervals ${support.unknownIntervals}")
        Text("Excluded intervals ${support.excludedIntervals}")
        Text("Present occurrences ${support.presentOccurrences}")
    }
}

@Composable
private fun LensBlock(lens: AxisLensPresentation, showSpread: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = lens.label, style = MaterialTheme.typography.labelLarge)
        if (lens.present != null) {
            Text("${BehaviorAssociationCopy.PRESENT} ${lens.present}")
            if (showSpread && lens.presentSpread != null) Text(lens.presentSpread)
        }
        if (lens.absent != null) {
            Text("${BehaviorAssociationCopy.ABSENT} ${lens.absent}")
            if (showSpread && lens.absentSpread != null) Text(lens.absentSpread)
        }
        if (lens.difference != null) {
            Text("${BehaviorAssociationCopy.DIFFERENCE} ${lens.difference}")
        }
        if (lens.unavailable) {
            Text(BehaviorAssociationCopy.NOT_ENOUGH_DATA)
        }
    }
}

@Composable
private fun EmotionSide(
    side: String,
    emotions: EmotionSidePresentation,
    showTokens: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$side recorded ${emotions.recorded}, missing ${emotions.missing}, eligible ${emotions.eligible}")
        if (showTokens) {
            emotions.tokens.forEach { token ->
                Text("${token.token} ${token.count}")
            }
        }
    }
}

@Composable
private fun CalendarBlock(calendar: CalendarPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Calendar-day association", style = MaterialTheme.typography.titleMedium)
        Text(calendar.dayWeightNote)
        Text("Present days ${calendar.presentDays}")
        Text("Absent days ${calendar.absentDays}")
        Text("Unknown days ${calendar.unknownDays}")
        calendar.axes.forEach { axis ->
            Text(text = axis.label, style = MaterialTheme.typography.labelLarge)
            if (axis.present != null) Text("${BehaviorAssociationCopy.PRESENT} ${axis.present}")
            if (axis.absent != null) Text("${BehaviorAssociationCopy.ABSENT} ${axis.absent}")
            if (axis.difference != null) Text("${BehaviorAssociationCopy.DIFFERENCE} ${axis.difference}")
            if (axis.unavailable) Text(BehaviorAssociationCopy.NOT_ENOUGH_DATA)
        }
    }
}

@Composable
private fun ContextBalanceBlock(context: ContextBalancePresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(context.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ContextGroupBlock(BehaviorAssociationCopy.PRESENT, context.present)
        ContextGroupBlock(BehaviorAssociationCopy.ABSENT, context.absent)
        context.nearest.forEach { gap ->
            Text(text = gap.label, style = MaterialTheme.typography.labelLarge)
            if (gap.summary != null) {
                Text(gap.summary)
            } else {
                Text(BehaviorAssociationCopy.NOT_ENOUGH_DATA)
            }
            Text("Unavailable present intervals ${gap.unavailablePresentCount}")
        }
    }
}

@Composable
private fun ContextGroupBlock(side: String, group: ContextGroupPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = side, style = MaterialTheme.typography.titleMedium)
        ContextLine("Start", group.startClock)
        ContextLine("End", group.endClock)
        ContextLine("Midpoint", group.midpointClock)
        ContextLine("Duration", group.duration)
        ContextLine("Time since wake at start", group.timeSinceWakeStart)
        ContextLine("Time since wake at end", group.timeSinceWakeEnd)
        group.startingAxes.forEach { axis ->
            ContextLine(axis.label, axis.summary)
        }
    }
}

@Composable
private fun ContextLine(label: String, value: String?) {
    Text("$label ${value ?: BehaviorAssociationCopy.NOT_ENOUGH_DATA}")
}

@Composable
private fun QuantityBlock(quantity: QuantityPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Quantity", style = MaterialTheme.typography.titleMedium)
        when (quantity) {
            QuantityPresentation.PresenceOnly -> Text(BehaviorAssociationCopy.PRESENCE_ONLY)
            is QuantityPresentation.Workout -> {
                Text("Intervals ${quantity.intervals}")
                Text("Sessions ${quantity.sessions}")
                if (quantity.minutes != null) Text("Minutes ${quantity.minutes}")
                if (quantity.minutesSpread != null) Text(quantity.minutesSpread)
            }
            is QuantityPresentation.Usage -> {
                Text("Parsed intervals ${quantity.parsedIntervals}")
                Text("Missing or unparsed intervals ${quantity.missingIntervals}")
                quantity.units.forEach { unit ->
                    Text(text = unit.unitLabel, style = MaterialTheme.typography.labelLarge)
                    if (unit.total != null) Text("Total ${unit.total}")
                    if (unit.perHour != null) Text("Per hour ${unit.perHour}")
                }
            }
        }
    }
}
