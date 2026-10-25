package com.orbit.wellness.util

object VolumeUnits {
    const val UNIT_GRAMS = "g"
    const val UNIT_OZ = "oz"
    const val UNIT_FL_OZ = "fl_oz"
    const val UNIT_SERVING = "serving"

    private const val GRAMS_PER_OZ = 28.3495

    /** Approximate water density — good enough for sodas / drinks. */
    private const val GRAMS_PER_FL_OZ = 29.5735

    fun toGrams(amount: Double, unit: String, servingGrams: Double = 100.0): Double {
        val safe = amount.coerceAtLeast(0.0)
        return when (unit) {
            UNIT_OZ -> (safe * GRAMS_PER_OZ).coerceAtLeast(0.01)
            UNIT_FL_OZ -> (safe * GRAMS_PER_FL_OZ).coerceAtLeast(0.01)
            UNIT_SERVING -> (safe * servingGrams.coerceAtLeast(0.01)).coerceAtLeast(0.01)
            else -> safe.coerceAtLeast(0.01)
        }
    }

    fun fromGrams(grams: Double, unit: String, servingGrams: Double = 100.0): Double {
        val safe = grams.coerceAtLeast(0.0)
        return when (unit) {
            UNIT_OZ -> safe / GRAMS_PER_OZ
            UNIT_FL_OZ -> safe / GRAMS_PER_FL_OZ
            UNIT_SERVING -> safe / servingGrams.coerceAtLeast(0.01)
            else -> safe
        }
    }

    fun label(unit: String): String = when (unit) {
        UNIT_OZ -> "oz"
        UNIT_FL_OZ -> "fl oz"
        UNIT_SERVING -> "serving"
        else -> "g"
    }

    /** Digits with at most one decimal point (e.g. 12, 12.5, .5). */
    fun filterDecimalInput(raw: String, maxLen: Int = 8): String {
        val filtered = buildString {
            var seenDot = false
            for (ch in raw) {
                when {
                    ch.isDigit() -> append(ch)
                    ch == '.' && !seenDot -> {
                        append(ch)
                        seenDot = true
                    }
                }
            }
        }
        return filtered.take(maxLen)
    }

    fun parseAmount(raw: String): Double? {
        val value = raw.trim().toDoubleOrNull() ?: return null
        return value.takeIf { it > 0.0 }
    }

    fun formatAmount(value: Double): String {
        val rounded = (value * 100.0).toLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format("%.2f", rounded).trimEnd('0').trimEnd('.')
        }
    }
}
