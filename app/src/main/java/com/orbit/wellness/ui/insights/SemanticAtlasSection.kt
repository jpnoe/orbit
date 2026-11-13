package com.orbit.wellness.ui.insights

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.domain.checkin.SemanticAtlasDataset
import com.orbit.wellness.domain.checkin.SemanticAtlasLabelBox
import com.orbit.wellness.domain.checkin.SemanticAtlasPoint
import com.orbit.wellness.domain.checkin.SemanticAtlasView
import com.orbit.wellness.domain.checkin.RegionDescriptionDataset
import com.orbit.wellness.domain.checkin.StateGroupAtlasDataset
import com.orbit.wellness.domain.checkin.semanticAtlasLabelsToDraw
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

@Composable
fun SemanticAtlasSection(
    atlas: SemanticAtlasDataset,
    groups: StateGroupAtlasDataset,
    regions: RegionDescriptionDataset,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("Semantic atlas") }
    if (!open) return
    GroupedStateAtlasSection(groups, regions)
    var rawOpen by remember { mutableStateOf(false) }
    TextButton(onClick = { rawOpen = !rawOpen }) { Text("Raw projection audit") }
    if (rawOpen) RawProjectionAudit(atlas)
}

@Composable
private fun RawProjectionAudit(atlas: SemanticAtlasDataset) {
    var geometry by remember { mutableIntStateOf(0) }
    var context by remember { mutableIntStateOf(0) }
    val geometryLabel = if (geometry == 0) "Direct incidence" else "Distributional context"
    val contextLabel = if (context == 0) "Layer zero" else "Reciprocal"
    val view = atlas.views.first { it.geometryLabel == geometryLabel && it.contextLabel == contextLabel }
    var states by remember { mutableStateOf(true) }
    var transitions by remember { mutableStateOf(true) }
    var concepts by remember { mutableStateOf(true) }
    var colorMode by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Retrospective full-history diagnostic. Not causal, not predictive, and not suitable for recommendation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Layer zero is the direct layer. Reciprocal is the existing one-hop layer. Distributional context is not graph radius 2.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "No clusters, region names, or behavior effects. Support changes concept-label priority only.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { geometry = 0 }) { Text(mark(geometry == 0, "Direct incidence")) }
            TextButton(onClick = { geometry = 1 }) { Text(mark(geometry == 1, "Distributional context")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context = 0 }) { Text(mark(context == 0, "Layer zero")) }
            TextButton(onClick = { context = 1 }) { Text(mark(context == 1, "Reciprocal")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { states = !states }) { Text(mark(states, "States")) }
            TextButton(onClick = { transitions = !transitions }) { Text(mark(transitions, "Transitions")) }
            TextButton(onClick = { concepts = !concepts }) { Text(mark(concepts, "Concepts")) }
        }
        TextButton(onClick = { colorMode = (colorMode + 1) % COLOR_MODES.size }) {
            Text("Color · ${COLOR_MODES[colorMode]}")
        }
        Text(
            "Color reads an emotion or one axis. It does not assign a cluster.",
            style = MaterialTheme.typography.bodySmall,
        )
        AtlasCanvas(
            view = view,
            states = states,
            transitions = transitions,
            concepts = concepts,
            colorMode = COLOR_MODES[colorMode],
            selectedId = selectedId,
            onSelect = { selectedId = it },
        )
        QualityBlock(view)
        SelectionBlock(view, selectedId)
        UnprojectedBlock(view)
        Notes(atlas.judgments)
    }
}

@Composable
private fun AtlasCanvas(
    view: SemanticAtlasView,
    states: Boolean,
    transitions: Boolean,
    concepts: Boolean,
    colorMode: String,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textPx = with(density) { 11.sp.toPx() }
    val hitPx = with(density) { 28.dp.toPx() }
    var scale by remember(view.geometry, view.context) { mutableFloatStateOf(1f) }
    var pan by remember(view.geometry, view.context) { mutableStateOf(Offset.Zero) }
    val visible = view.points.filter { point ->
        point.projected && when (point.kind) {
            "state" -> states
            "transition" -> transitions
            else -> concepts
        }
    }
    LaunchedEffect(view.geometry, view.context) {
        if (selectedId != null && view.points.none { it.id == selectedId }) onSelect(null)
    }
    Column {
        TextButton(onClick = {
            scale = 1f
            pan = Offset.Zero
        }) { Text("Fit") }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .pointerInput(view.geometry, view.context, states, transitions, concepts) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val panChange = event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            if (panChange.getDistance() > viewConfiguration.touchSlop || abs(zoomChange - 1f) > 0.01f) {
                                moved = true
                            }
                            if (moved) {
                                pan += panChange
                                scale = (scale * zoomChange).coerceIn(0.4f, 8f)
                                event.changes.forEach { change -> if (change.pressed) change.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved) {
                            onSelect(nearest(visible, down.position, size.width.toFloat(), size.height.toFloat(), scale, pan, hitPx))
                        }
                    }
                },
        ) {
            val width = size.width
            val height = size.height
            val screenOf = screenMapping(visible, width, height, scale, pan)
            view.edges.forEach { edge ->
                val from = visible.firstOrNull { it.id == edge.fromId } ?: return@forEach
                val to = visible.firstOrNull { it.id == edge.toId } ?: return@forEach
                drawLine(
                    color = scheme.outline.copy(alpha = 0.35f),
                    start = screenOf(from),
                    end = screenOf(to),
                    strokeWidth = 2f,
                )
            }
            visible.forEach { point ->
                val center = screenOf(point)
                val selected = point.id == selectedId
                val color = pointColor(point, colorMode, scheme.primary, scheme.secondary, scheme.tertiary)
                drawMarker(point.kind, center, color, selected)
            }
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = textPx
                color = scheme.onSurface.toArgb()
            }
            val boxes = visible.associate { point ->
                val center = screenOf(point)
                val widthPx = paint.measureText(point.label)
                point.id to SemanticAtlasLabelBox(
                    left = center.x + 8f,
                    top = center.y - textPx - 4f,
                    right = center.x + 8f + widthPx,
                    bottom = center.y - 2f,
                )
            }
            val shown = semanticAtlasLabelsToDraw(visible, boxes, selectedId)
            drawContext.canvas.nativeCanvas.apply {
                shown.forEach { id ->
                    val point = visible.first { it.id == id }
                    val box = boxes.getValue(id)
                    drawText(point.label, box.left, box.bottom, paint)
                }
            }
        }
    }
}

private fun screenMapping(
    points: List<SemanticAtlasPoint>,
    width: Float,
    height: Float,
    scale: Float,
    pan: Offset,
): (SemanticAtlasPoint) -> Offset {
    val xs = points.mapNotNull { it.x }
    val ys = points.map { it.y ?: 0.0 }
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0
    val x0 = if (maxX - minX < 1e-6) minX - 0.5 else minX
    val x1 = if (maxX - minX < 1e-6) maxX + 0.5 else maxX
    val y0 = if (maxY - minY < 1e-6) minY - 0.5 else minY
    val y1 = if (maxY - minY < 1e-6) maxY + 0.5 else maxY
    val pad = 36f
    return { point ->
        val x = point.x ?: x0
        val y = point.y ?: ((y0 + y1) / 2.0)
        val fitX = pad + ((x - x0) / (x1 - x0) * (width - 2 * pad)).toFloat()
        val fitY = pad + ((y1 - y) / (y1 - y0) * (height - 2 * pad)).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        Offset(cx + (fitX - cx) * scale + pan.x, cy + (fitY - cy) * scale + pan.y)
    }
}

private fun nearest(
    points: List<SemanticAtlasPoint>,
    tap: Offset,
    width: Float,
    height: Float,
    scale: Float,
    pan: Offset,
    hit: Float,
): String? {
    val screenOf = screenMapping(points, width, height, scale, pan)
    return points.map { it to screenOf(it) }
        .filter { (_, center) -> hypot(center.x - tap.x, center.y - tap.y) <= hit }
        .minByOrNull { (_, center) -> hypot(center.x - tap.x, center.y - tap.y) }
        ?.first
        ?.id
}

private fun pointColor(
    point: SemanticAtlasPoint,
    mode: String,
    primary: Color,
    secondary: Color,
    tertiary: Color,
): Color {
    val base = when (point.kind) {
        "state" -> primary
        "transition" -> secondary
        else -> tertiary
    }
    if (point.kind != "state") return base
    return when (mode) {
        "emotion" -> point.emotion?.let { emotionColor(it) } ?: base
        "mood" -> axisColor(point, DefaultCheckInAxes.MOOD) ?: base
        "energy" -> axisColor(point, DefaultCheckInAxes.ENERGY) ?: base
        "confidence" -> axisColor(point, DefaultCheckInAxes.CONFIDENCE) ?: base
        "anxiety" -> axisColor(point, DefaultCheckInAxes.ANXIETY) ?: base
        else -> base
    }
}

private fun axisColor(point: SemanticAtlasPoint, axisId: String): Color? {
    val raw = point.axisValues[axisId] ?: return null
    val fraction = (raw.coerceIn(0, 10) / 10f)
    return Color(red = 0.25f + 0.6f * fraction, green = 0.45f * (1f - fraction), blue = 0.55f * (1f - fraction))
}

private fun emotionColor(name: String): Color {
    val palette = listOf(
        Color(0xFF33658A),
        Color(0xFFC46B3A),
        Color(0xFF3E7C59),
        Color(0xFF7A4E8A),
        Color(0xFF8C4A4A),
        Color(0xFF3F6F8C),
    )
    val index = abs(name.hashCode()) % palette.size
    return palette[index]
}

@Composable
private fun QualityBlock(view: SemanticAtlasView) {
    val quality = view.quality
    Text("${view.geometryLabel} · ${view.contextLabel}", style = MaterialTheme.typography.labelMedium)
    Text(
        "Projected ${quality.projected} · unprojected ${quality.unprojected}.",
        style = MaterialTheme.typography.bodySmall,
    )
    quality.byKind.forEach { kind ->
        Text(
            "${kind.kind} · projected ${kind.projected} · unprojected ${kind.unprojected}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "Source dimensions ${quality.sourceDimensionality}. Positive ${quality.positiveDimensions}. Retained ${quality.retainedDimensions}.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Stress ${formatPoint(quality.stress)}. Mean top-5 retention ${formatPoint(quality.meanRetention)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    quality.retentionByKind.forEach { kind ->
        Text(
            "${kind.kind} retention ${formatPoint(kind.meanRetention)} · scored ${kind.scored}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "Negative eigenvalue share ${formatPoint(quality.negativeEigenvalueShare)}. Smallest eigenvalue ${formatPoint(quality.smallestEigenvalue)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (quality.degeneracy.isNotBlank()) {
        Text(quality.degeneracy, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        if (quality.retrospectiveFullHistory) "Retrospective full history." else "Scope is not full history.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (quality.smallSampleWarning.isNotBlank()) {
        Text(quality.smallSampleWarning, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SelectionBlock(view: SemanticAtlasView, selectedId: String?) {
    val point = view.points.firstOrNull { it.id == selectedId }
    if (point == null) {
        Text("Tap a point.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("${point.label} · ${point.kind}", style = MaterialTheme.typography.bodyMedium)
    Text("${view.geometryLabel} · ${view.contextLabel}", style = MaterialTheme.typography.bodySmall)
    Text(
        if (point.projected) {
            "2D ${formatPoint(point.x)}, ${if (point.y == null) "second coordinate absent" else formatPoint(point.y)}"
        } else {
            "Unprojected · ${point.placement}"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Source dimensions ${view.sourceDimensionality}.", style = MaterialTheme.typography.bodySmall)
    if (point.neighbors.isEmpty()) {
        Text("No original neighbor in this projection.", style = MaterialTheme.typography.bodySmall)
    } else {
        point.neighbors.forEach { neighbor ->
            val kept = if (neighbor.retained) "retained" else "not retained"
            Text(
                "${neighbor.label} · similarity ${formatPoint(neighbor.similarity)} · " +
                    "2D distance ${formatPoint(neighbor.projectedDistance)} · rank ${neighbor.projectedRank} · $kept",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (point.incidentLabels.isNotEmpty()) {
        Text("Incident · ${point.incidentLabels.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
    }
    if (point.carriedLabels.isNotEmpty()) {
        Text("Carried · ${point.carriedLabels.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Visual proximity is projected. The original cosine is authoritative.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun UnprojectedBlock(view: SemanticAtlasView) {
    val missing = view.points.filter { !it.projected }
    if (missing.isEmpty()) return
    val shown = missing.take(8)
    shown.forEach { point ->
        Text("${point.label} · ${point.placement}", style = MaterialTheme.typography.bodySmall)
    }
    val extra = missing.size - shown.size
    if (extra > 0) Text("$extra more unprojected.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Notes(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text("Notes") }
    if (!open) return
    lines.forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

private fun mark(selected: Boolean, label: String): String = if (selected) "● $label" else label

private fun formatPoint(value: Double?): String {
    if (value == null) return "none"
    val rounded = round(value * 1000.0) / 1000.0
    return if (rounded == 0.0) "0.0" else rounded.toString()
}

private val COLOR_MODES = listOf("none", "emotion", "mood", "energy", "confidence", "anxiety")

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    kind: String,
    center: Offset,
    color: Color,
    selected: Boolean,
) {
    val radius = if (selected) 11f else 7f
    when (kind) {
        "state" -> drawCircle(color = color, radius = radius, center = center)
        "transition" -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(path, color)
        }
        else -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y + radius * 0.8f)
                lineTo(center.x - radius, center.y + radius * 0.8f)
                close()
            }
            drawPath(path, color)
        }
    }
    if (selected) {
        drawCircle(color = color, radius = radius + 4f, center = center, style = Stroke(width = 2f))
    }
}
