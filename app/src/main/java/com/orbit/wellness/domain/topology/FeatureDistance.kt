package com.orbit.wellness.domain.topology

import com.orbit.wellness.domain.state.FeatureAxes
import kotlin.math.sqrt

object FeatureDistance {
    /**
     * RMS Euclidean over axes present in both masks.
     * Returns +∞ when there is no overlap (incomparable).
     */
    fun rms(
        a: FloatArray,
        maskA: Int,
        b: FloatArray,
        maskB: Int,
    ): Float {
        val shared = maskA and maskB
        if (shared == 0) return Float.POSITIVE_INFINITY
        var sum = 0f
        var count = 0
        for (axis in 0 until FeatureAxes.COUNT) {
            if (shared and FeatureAxes.bit(axis) == 0) continue
            val d = a[axis] - b[axis]
            sum += d * d
            count++
        }
        if (count == 0) return Float.POSITIVE_INFINITY
        return sqrt(sum / count)
    }

    fun mutualReachability(coreA: Float, coreB: Float, distance: Float): Float {
        return maxOf(coreA, coreB, distance)
    }
}
