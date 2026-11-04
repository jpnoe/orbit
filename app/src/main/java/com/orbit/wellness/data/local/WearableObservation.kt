package com.orbit.wellness.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * One physiological observation imported from a wearable source.
 *
 * This is not a [BiometricSnapshot] and it is not a psychological state.
 * [observedStartAt] and [observedEndAt] are measurement times. [importedAt] is
 * when Orbit stored the row. A point uses the same instant for both ends.
 * Missing data is the absence of a row, not a stored zero.
 */
@Entity(
    tableName = "wearable_observations",
    indices = [
        Index(value = ["metric", "observedStartAt"]),
        Index(value = ["source", "sourceRecordId"]),
    ],
)
data class WearableObservation(
    @PrimaryKey val id: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val observedStartAt: Long,
    val observedEndAt: Long,
    val temporalKind: String,
    val method: String,
    val source: String,
    val originPackage: String?,
    val sourceRecordId: String?,
    val recordingMethod: String?,
    val importedAt: Long,
) {
    init {
        require(metric in WearableMetric.ALL) { "Unknown wearable metric: $metric" }
        require(temporalKind in WearableTemporalKind.ALL) { "Unknown temporal kind: $temporalKind" }
        require(method in WearableMethod.ALL) { "Unknown wearable method: $method" }
        require(unit.isNotBlank()) { "Wearable unit must not be blank" }
        require(value.isFinite() && value >= 0.0) { "Wearable value must be a non-negative number" }
        require(observedStartAt <= observedEndAt) { "Observation start must not be later than its end" }
        if (temporalKind == WearableTemporalKind.POINT) {
            require(observedStartAt == observedEndAt) { "A point observation has one measurement time" }
        }
    }

    companion object {
        /**
         * Same source identity produces the same primary key, so a repeated sync
         * replaces the row. Aggregates have no Health Connect record id, so their
         * identity is the metric, method, and exact requested window.
         */
        fun identity(
            source: String,
            sourceRecordId: String?,
            metric: String,
            method: String,
            observedStartAt: Long,
            observedEndAt: Long,
        ): String {
            val key = listOf(
                source,
                sourceRecordId.orEmpty(),
                metric,
                method,
                observedStartAt.toString(),
                observedEndAt.toString(),
            ).joinToString("|")
            return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
        }

        fun create(
            metric: String,
            value: Double,
            unit: String,
            observedStartAt: Long,
            observedEndAt: Long,
            temporalKind: String,
            method: String,
            source: String = SOURCE_HEALTH_CONNECT,
            originPackage: String? = null,
            sourceRecordId: String? = null,
            recordingMethod: String? = null,
            importedAt: Long,
        ): WearableObservation {
            val recordId = sourceRecordId?.takeIf { it.isNotBlank() }
            return WearableObservation(
                id = identity(source, recordId, metric, method, observedStartAt, observedEndAt),
                metric = metric,
                value = value,
                unit = unit,
                observedStartAt = observedStartAt,
                observedEndAt = observedEndAt,
                temporalKind = temporalKind,
                method = method,
                source = source,
                originPackage = originPackage?.takeIf { it.isNotBlank() },
                sourceRecordId = recordId,
                recordingMethod = recordingMethod?.takeIf { it.isNotBlank() },
                importedAt = importedAt,
            )
        }

        const val SOURCE_HEALTH_CONNECT = "health_connect"
    }
}

object WearableMetric {
    const val STEPS = "steps"
    const val RESTING_HEART_RATE = "resting_heart_rate"
    const val HEART_RATE_AVERAGE = "heart_rate_average"
    const val HEART_RATE_SAMPLE = "heart_rate_sample"
    const val SLEEP_DURATION = "sleep_duration"

    val ALL = setOf(
        STEPS,
        RESTING_HEART_RATE,
        HEART_RATE_AVERAGE,
        HEART_RATE_SAMPLE,
        SLEEP_DURATION,
    )
}

object WearableTemporalKind {
    const val POINT = "point"
    const val MEASURED_INTERVAL = "measured_interval"
    const val AGGREGATED_WINDOW = "aggregated_window"

    val ALL = setOf(POINT, MEASURED_INTERVAL, AGGREGATED_WINDOW)
}

object WearableMethod {
    const val AGGREGATE_TOTAL = "aggregate_total"
    const val STEPS_RECORD = "steps_record"
    const val RESTING_HEART_RATE_RECORD = "resting_heart_rate_record"
    const val HEART_RATE_AVERAGE = "heart_rate_average"
    const val HEART_RATE_SAMPLE = "heart_rate_sample"
    const val SLEEP_SESSION = "sleep_session"

    val ALL = setOf(
        AGGREGATE_TOTAL,
        STEPS_RECORD,
        RESTING_HEART_RATE_RECORD,
        HEART_RATE_AVERAGE,
        HEART_RATE_SAMPLE,
        SLEEP_SESSION,
    )
}

object WearableUnit {
    const val COUNT = "count"
    const val BEATS_PER_MINUTE = "bpm"
    const val HOURS = "h"
}

/**
 * Cumulative and other aggregated windows. Their values already cover the whole
 * window, so overlapping rows must not be added together.
 */
fun aggregatedWindows(observations: List<WearableObservation>): List<WearableObservation> {
    return observations.filter { it.temporalKind == WearableTemporalKind.AGGREGATED_WINDOW }
}
