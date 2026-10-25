package com.orbit.wellness.domain

/**
 * Time-weighted mood average for samples on a single day.
 * Each sample holds until the next sample; the last holds until [dayEndMillis].
 */
fun timeWeightedMoodAverage(
    samples: List<Pair<Long, Int>>,
    dayEndMillis: Long,
): Double? {
    if (samples.isEmpty()) return null
    val sorted = samples.sortedBy { it.first }
    if (sorted.size == 1) return sorted.first().second.toDouble()

    var weightedSum = 0.0
    var totalWeight = 0.0

    for (i in sorted.indices) {
        val (timestamp, mood) = sorted[i]
        val nextTimestamp = if (i < sorted.lastIndex) {
            sorted[i + 1].first
        } else {
            dayEndMillis
        }
        val weight = (nextTimestamp - timestamp).coerceAtLeast(0L).toDouble()
        weightedSum += mood * weight
        totalWeight += weight
    }

    return if (totalWeight == 0.0) {
        sorted.last().second.toDouble()
    } else {
        weightedSum / totalWeight
    }
}
