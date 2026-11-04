package com.orbit.wellness.data.local

/**
 * Stable default scales shared by [OrbitMigrations.MIGRATION_16_17] and
 * [OrbitDatabase.ensureDefaultCheckInAxes]. Higher values follow each axis's
 * high label. Anxiety increases toward "Extreme anxiety"; that polarity is
 * stored as observed and is not inverted here.
 */
object DefaultCheckInAxes {
    const val MOOD = "mood"
    const val ENERGY = "energy"
    const val CONFIDENCE = "confidence"
    const val ANXIETY = "anxiety"

    /** Shared by migration and startup so a re-seed does not move the timestamp. */
    const val SEEDED_CREATED_AT = 0L

    val ALL: List<CheckInAxis> = listOf(
        axis(
            id = MOOD,
            name = "Mood",
            lowLabel = "Very unpleasant",
            highLabel = "Very pleasant",
            sortOrder = 0,
        ),
        axis(
            id = ENERGY,
            name = "Energy",
            lowLabel = "No energy",
            highLabel = "Extremely energized",
            sortOrder = 1,
        ),
        axis(
            id = CONFIDENCE,
            name = "Confidence",
            lowLabel = "No confidence",
            highLabel = "Extremely confident",
            sortOrder = 2,
        ),
        axis(
            id = ANXIETY,
            name = "Anxiety",
            lowLabel = "No anxiety",
            highLabel = "Extreme anxiety",
            sortOrder = 3,
        ),
    )

    fun insertOrIgnoreSql(): String {
        val tuples = ALL.joinToString(",\n") { axis ->
            "('${sql(axis.id)}', '${sql(axis.name)}', '${sql(axis.lowLabel)}', " +
                "'${sql(axis.highLabel)}', ${axis.sortOrder}, ${axis.isDefault.toSql()}, " +
                "${axis.isEnabled.toSql()}, ${axis.createdAt})"
        }
        val header = """
            INSERT OR IGNORE INTO check_in_axes (
                id, name, lowLabel, highLabel, sortOrder, isDefault, isEnabled, createdAt
            ) VALUES
        """.trimIndent()
        return header + "\n" + tuples
    }

    private fun axis(
        id: String,
        name: String,
        lowLabel: String,
        highLabel: String,
        sortOrder: Int,
    ): CheckInAxis {
        return CheckInAxis(
            id = id,
            name = name,
            lowLabel = lowLabel,
            highLabel = highLabel,
            sortOrder = sortOrder,
            isDefault = true,
            isEnabled = true,
            createdAt = SEEDED_CREATED_AT,
        )
    }

    private fun sql(value: String): String = value.replace("'", "''")

    private fun Boolean.toSql(): Int = if (this) 1 else 0
}
