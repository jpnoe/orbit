package com.orbit.wellness.ui.insights

import com.orbit.wellness.domain.checkin.StateOccurrenceKey
import kotlin.math.abs

const val STATE_MAP_PADDING_FRACTION = 0.12
private const val ZERO_RANGE = 1e-8

data class MapAxisSpan(
    val min: Double,
    val max: Double,
)

data class MapPlotPoint(
    val key: StateOccurrenceKey,
    val recordedAt: Long,
    val x: Float,
    val y: Float,
)

/**
 * Display bounds for one axis. A range that is effectively zero is centered
 * on that value. Coordinates themselves are not changed.
 */
fun mapAxisSpan(
    values: List<Double>,
    paddingFraction: Double = STATE_MAP_PADDING_FRACTION,
): MapAxisSpan {
    require(values.isNotEmpty()) { "An axis span needs coordinates" }
    val low = values.min()
    val high = values.max()
    val span = high - low
    if (!span.isFinite() || span <= ZERO_RANGE * maxOf(1.0, abs(low), abs(high))) {
        val center = if (low.isFinite()) low else 0.0
        return MapAxisSpan(center - 1.0, center + 1.0)
    }
    val pad = span * paddingFraction
    return MapAxisSpan(low - pad, high + pad)
}

fun mapDisplayFraction(value: Double, span: MapAxisSpan): Float {
    val width = span.max - span.min
    if (!value.isFinite() || width <= 0.0) return 0.5f
    return ((value - span.min) / width).toFloat()
}

/**
 * Nearest plotted point inside [radius], then the most recent recorded time,
 * then the occurrence key. Overlapping coordinates are not separated.
 */
fun selectMapPoint(
    points: List<MapPlotPoint>,
    tapX: Float,
    tapY: Float,
    radius: Float,
): StateOccurrenceKey? {
    val limit = radius * radius
    return points
        .map { point -> point to ((point.x - tapX) * (point.x - tapX) + (point.y - tapY) * (point.y - tapY)) }
        .filter { it.second <= limit }
        .minWithOrNull(compareBy<Pair<MapPlotPoint, Float>> { it.second }.thenByDescending { it.first.recordedAt }.thenBy { it.first.key.raw })
        ?.first
        ?.key
}
