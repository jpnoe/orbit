package com.orbit.wellness.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orbit.wellness.domain.checkin.StateMapPoint
import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import java.time.ZoneId
private val PlotHeight = 280.dp
private val TouchRadius = 24.dp
private val PlotInset = 16.dp

@Composable
fun StateMapSection(
    content: StateMapContent,
    selectedKey: StateOccurrenceKey?,
    onSelect: (StateOccurrenceKey) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val selected = content.points.firstOrNull { it.key == selectedKey }
    val detail = selected?.let { stateMapDetail(it, zone) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = content.heading,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = content.support,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (content.mode) {
            StateMapMode.UNAVAILABLE -> Text(
                text = content.unavailableReason.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
            )
            StateMapMode.ONE_COMPONENT -> {
                StateMapPlot(content, selectedKey, onSelect)
                Text(
                    text = componentAxisLabel(0, content.explainedVariancePercent.first()),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            StateMapMode.TWO_COMPONENT -> {
                Text(
                    text = componentAxisLabel(1, content.explainedVariancePercent[1]),
                    style = MaterialTheme.typography.labelLarge,
                )
                StateMapPlot(content, selectedKey, onSelect)
                Text(
                    text = componentAxisLabel(0, content.explainedVariancePercent[0]),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (detail != null) {
            StateMapDetailCard(detail)
        }
        StateMapLegend()
        Text(
            text = content.disclaimer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StateMapLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            StateMapCopy.LEGEND_COLOR,
            StateMapCopy.LEGEND_FILLED,
            StateMapCopy.LEGEND_OUTLINE,
            StateMapCopy.LEGEND_LINES,
        ).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            text = StateMapCopy.ORIENTATION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StateMapPlot(
    content: StateMapContent,
    selectedKey: StateOccurrenceKey?,
    onSelect: (StateOccurrenceKey) -> Unit,
) {
    val selected = content.points.firstOrNull { it.key == selectedKey }
    val description = stateMapDescription(content, selected?.let { stateMapDetail(it, ZoneId.systemDefault()) })
    val outline = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val recent = MaterialTheme.colorScheme.primary
    val selection = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PlotHeight)
            .semantics { contentDescription = description }
            .pointerInput(content.points, content.mode) {
                detectTapGestures { tap ->
                    val placed = placedPoints(content, size.width.toFloat(), size.height.toFloat(), PlotInset.toPx())
                    val hit = selectMapPoint(placed, tap.x, tap.y, TouchRadius.toPx())
                    if (hit != null) onSelect(hit)
                }
            },
    ) {
        val placed = placedPoints(content, size.width, size.height, PlotInset.toPx())
        val byKey = placed.associateBy { it.key }
        val oldest = content.points.minOfOrNull { it.recordedAt } ?: 0L
        val newest = content.points.maxOfOrNull { it.recordedAt } ?: oldest
        content.edges.forEach { edge ->
            val from = byKey[edge.fromKey] ?: return@forEach
            val to = byKey[edge.toKey] ?: return@forEach
            drawLine(
                color = outline.copy(alpha = 0.55f),
                start = Offset(from.x, from.y),
                end = Offset(to.x, to.y),
                strokeWidth = 2.dp.toPx(),
            )
        }
        content.points.forEach { point ->
            val place = byKey.getValue(point.key)
            val color = lerp(muted, recent, point.displayColorFraction(oldest, newest))
            val radius = if (point.key == content.latestStateKey) 8.dp.toPx() else 6.dp.toPx()
            if (point.outgoingContextAvailable) {
                drawCircle(color = color, radius = radius, center = Offset(place.x, place.y))
            } else {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(place.x, place.y),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            if (point.key == selectedKey) {
                drawCircle(
                    color = selection,
                    radius = radius + 5.dp.toPx(),
                    center = Offset(place.x, place.y),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun StateMapDetailCard(detail: StateMapDetail) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = detail.whenLabel, style = MaterialTheme.typography.titleMedium)
            if (detail.emotion != null) {
                Text(text = detail.emotion, style = MaterialTheme.typography.bodyLarge)
            }
            detail.lines.forEach { (label, value) ->
                Text(
                    text = "$label  $value",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (detail.accumulating) {
                Text(
                    text = StateMapCopy.ACCUMULATING,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Pixel positions for the current canvas. A one-component map uses a single
 * horizontal coordinate and a shared vertical center. That center is not stored
 * as a second component.
 */
internal fun placedPoints(
    content: StateMapContent,
    width: Float,
    height: Float,
    inset: Float,
): List<MapPlotPoint> {
    if (content.points.isEmpty() || width <= inset * 2f || height <= inset * 2f) return emptyList()
    val xSpan = mapAxisSpan(content.points.map { it.x })
    val yValues = if (content.mode == StateMapMode.ONE_COMPONENT) {
        List(content.points.size) { 0.0 }
    } else {
        content.points.map { it.y ?: 0.0 }
    }
    val ySpan = mapAxisSpan(yValues)
    return content.points.mapIndexed { index, point ->
        MapPlotPoint(
            key = point.key,
            recordedAt = point.recordedAt,
            x = inset + mapDisplayFraction(point.x, xSpan) * (width - inset * 2f),
            y = inset + (1f - mapDisplayFraction(yValues[index], ySpan)) * (height - inset * 2f),
        )
    }
}

internal fun StateMapPoint.displayColorFraction(oldest: Long, newest: Long): Float {
    val span = kotlin.math.max(newest - oldest, 1L).toFloat()
    return ((recordedAt - oldest).toFloat() / span).coerceIn(0f, 1f)
}
