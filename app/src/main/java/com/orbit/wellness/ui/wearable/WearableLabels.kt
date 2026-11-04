package com.orbit.wellness.ui.wearable

import com.orbit.wellness.R
import com.orbit.wellness.data.local.WearableMetric

fun wearableMetricLabelRes(metric: String): Int {
    return when (metric) {
        WearableMetric.STEPS -> R.string.wearable_metric_steps
        WearableMetric.RESTING_HEART_RATE -> R.string.wearable_metric_resting_heart_rate
        WearableMetric.HEART_RATE_AVERAGE -> R.string.wearable_metric_heart_rate_average
        WearableMetric.HEART_RATE_SAMPLE -> R.string.wearable_metric_heart_rate_sample
        WearableMetric.SLEEP_DURATION -> R.string.wearable_metric_sleep
        else -> R.string.wearable_metric_unknown
    }
}
