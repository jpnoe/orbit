package com.orbit.wellness.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.orbit.wellness.domain.checkin.ConceptSupport
import com.orbit.wellness.domain.checkin.PersonalSemanticOccurrenceProjection
import com.orbit.wellness.domain.checkin.SemanticConceptType
import com.orbit.wellness.domain.checkin.auditLabel

@Composable
fun OccurrenceProjectionSection(projection: PersonalSemanticOccurrenceProjection) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Occurrence projection")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(structureLine(projection), style = MaterialTheme.typography.bodyMedium)
        Text(axisLine(projection), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Emotions recorded ${projection.statesWithEmotion}. Empty ${projection.statesWithoutEmotion}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(quantityLine(projection), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Raw strings shared across key spaces: ${projection.overlaps.size}. Typed keys stay separate.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SemanticConceptType.entries.forEach { type ->
            ConceptTypeBlock(type, projection.concepts.filter { it.key.type == type })
        }
        var judgments by remember { mutableStateOf(false) }
        TextButton(onClick = { judgments = !judgments }) {
            Text("Judgments")
        }
        if (judgments) {
            projection.judgments.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
        }
        if (projection.unattached.isNotEmpty()) {
            Text("Unattached source records", style = MaterialTheme.typography.bodyMedium)
            projection.unattached.forEach { row ->
                Text(
                    "${row.concept.type.auditLabel()} · ${row.label} · ${row.quantityText ?: "no quantity"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConceptTypeBlock(type: SemanticConceptType, concepts: List<ConceptSupport>) {
    var open by remember(type) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("${type.auditLabel()} · ${concepts.size}")
    }
    if (!open) return
    if (concepts.isEmpty()) {
        Text("None recorded.", style = MaterialTheme.typography.bodySmall)
        return
    }
    concepts.forEach { concept ->
        val name = concept.displayNames.ifEmpty { listOf(concept.key.identity) }.joinToString(", ")
        val intent = concept.key.intent?.let { " · $it" }.orEmpty()
        Text(
            "$name$intent · backbone ${concept.backboneOccurrences} · unattached ${concept.unattachedOccurrences}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
        )
    }
}

private fun structureLine(projection: PersonalSemanticOccurrenceProjection): String {
    val structure = projection.structure
    return "${structure.states} states · ${structure.transitions} transitions · " +
        "valid ${structure.valid} · boundary ${structure.boundary} · " +
        "incomplete ${structure.incomplete} · conflicting ${structure.conflicting} · " +
        "unlinked states ${structure.unlinkedStates} · unlinked transitions ${structure.unlinkedTransitions}"
}

private fun axisLine(projection: PersonalSemanticOccurrenceProjection): String {
    return projection.axes.joinToString("\n") { axis ->
        "${axis.axisId} observed ${axis.observed} · missing ${axis.missing} · conflicting ${axis.conflicting}"
    }
}

private fun quantityLine(projection: PersonalSemanticOccurrenceProjection): String {
    val quantity = projection.quantity
    return "Workout rows ${quantity.workoutOccurrences}. " +
        "Contextual usage rows ${quantity.usageOccurrences}. " +
        "Amounts parsed ${quantity.usageParsed}, missing ${quantity.usageMissing}, unparsed ${quantity.usageUnparsed}."
}
