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
import com.orbit.wellness.domain.checkin.EmbeddingNeighbor
import com.orbit.wellness.domain.checkin.JointEmbeddingDataset
import com.orbit.wellness.domain.checkin.JointEmbeddingLayer
import com.orbit.wellness.domain.checkin.stateNodeId
import kotlin.math.round

@Composable
fun JointEmbeddingSection(
    embedding: JointEmbeddingDataset,
    stateId: String,
) {
    var open by remember(stateId) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Joint embedding")
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Retrospective measurement. Scope ${embedding.vocabularyScope}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(embedding.method, style = MaterialTheme.typography.bodySmall)
        embedding.layers.forEach { layer ->
            LayerCard(layer, stateNodeId(stateId))
        }
        Notes(embedding.judgments)
    }
}

@Composable
private fun LayerCard(layer: JointEmbeddingLayer, anchor: String) {
    var open by remember(layer.name) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("${layerTitle(layer.name)} · ${layer.dimensionality} dimensions")
    }
    if (!open) return
    if (layer.unreliableBecause.isNotEmpty()) {
        Text(layer.unreliableBecause, style = MaterialTheme.typography.bodySmall)
    }
    if (layer.omittedConstantPresence.isNotEmpty()) {
        Text(
            "Constant presence omitted: ${layer.omittedConstantPresence.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (layer.quantityHeldBack.isNotEmpty()) {
        Text(
            "Quantity held back: ${layer.quantityHeldBack.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (layer.node(anchor) == null) {
        Text("This anchor has no coordinates in this fit.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    NeighborBlock("Nearest states", layer.nearest(anchor, "state"))
    NeighborBlock("Nearest transitions", layer.nearest(anchor, "transition"))
    NeighborBlock("Nearest concepts", layer.nearest(anchor, "concept"))
}

@Composable
private fun NeighborBlock(title: String, neighbors: List<EmbeddingNeighbor>) {
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
    if (neighbors.isEmpty()) {
        Text("None.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
        return
    }
    neighbors.forEach { neighbor ->
        Text(
            "${display(neighbor.id)} · similarity ${format(neighbor.similarity)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        )
        neighbor.contributors.forEach { contributor ->
            Text(
                contributor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
            )
        }
    }
}

@Composable
private fun Notes(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text("Notes")
    }
    if (!open) return
    lines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

private fun layerTitle(name: String): String = when (name) {
    "layer-zero" -> "Layer zero"
    "reciprocal" -> "Reciprocal layer"
    else -> name
}

private fun display(id: String): String = when {
    id.startsWith("state:") -> "state ${id.removePrefix("state:")}"
    id.startsWith("transition:") -> "transition ${id.removePrefix("transition:")}"
    else -> id.removePrefix("concept:")
}

private fun format(value: Double): String {
    val rounded = round(value * 1000.0) / 1000.0
    return rounded.toString()
}
