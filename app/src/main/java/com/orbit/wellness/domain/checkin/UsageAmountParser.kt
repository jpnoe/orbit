package com.orbit.wellness.domain.checkin

import java.util.Locale

/**
 * Reads one usage amount phrase. The stored text is never rewritten.
 * A unit is not inferred from the usage name, and units are not converted.
 */
object UsageAmountParser {
    private val numberAndUnit = Regex("""^(\d+(?:\.\d+)?|\.\d+)(?:\s*(\S+))?$""")
    private val startsWithNumber = Regex("""^(\d+(?:\.\d+)?|\.\d+)""")
    private val scientific = Regex("""^[+-]?(?:\d+(?:\.\d+)?|\.\d+)[eE][+-]?\d+$""")
    private val nonfinite = Regex("""^[+-]?(?:nan|infinity|inf)$""", RegexOption.IGNORE_CASE)

    fun parse(amountText: String): UsageAmountParse {
        val text = amountText.trim()
        if (text.isEmpty()) return UsageAmountParse.Missing
        if (scientific.matches(text)) {
            return UsageAmountParse.Unparsed(amountText, UsageParseFailure.MALFORMED)
        }
        if (nonfinite.matches(text)) {
            return UsageAmountParse.Unparsed(amountText, UsageParseFailure.NONFINITE)
        }
        if (text.startsWith("-")) {
            return UsageAmountParse.Unparsed(amountText, UsageParseFailure.NEGATIVE)
        }
        val match = numberAndUnit.matchEntire(text)
        if (match != null) {
            val number = match.groupValues[1].toDouble()
            if (!number.isFinite()) {
                return UsageAmountParse.Unparsed(amountText, UsageParseFailure.NONFINITE)
            }
            val unitToken = match.groupValues[2]
            val unit = if (unitToken.isEmpty()) null else normalizeUnit(unitToken)
            return UsageAmountParse.Parsed(number = number, unit = unit)
        }
        val failure = if (startsWithNumber.containsMatchIn(text) && text.any { it.isLetter() }) {
            UsageParseFailure.TRAILING_PROSE
        } else {
            UsageParseFailure.MALFORMED
        }
        return UsageAmountParse.Unparsed(amountText, failure)
    }

    /**
     * Case-folds a unit token and maps a short list of unambiguous aliases.
     * An unknown token stays its own group. `mg` is not turned into `g`.
     */
    fun normalizeUnit(token: String): String {
        val folded = token.trim().lowercase(Locale.ROOT)
            .replace("µg", "mcg")
            .replace("μg", "mcg")
        return UNIT_ALIASES[folded] ?: folded
    }

    private val UNIT_ALIASES = mapOf(
        "mg" to "mg",
        "milligram" to "mg",
        "milligrams" to "mg",
        "g" to "g",
        "gram" to "g",
        "grams" to "g",
        "mcg" to "mcg",
        "ug" to "mcg",
        "microgram" to "mcg",
        "micrograms" to "mcg",
        "ml" to "ml",
        "milliliter" to "ml",
        "milliliters" to "ml",
        "millilitre" to "ml",
        "millilitres" to "ml",
        "oz" to "oz",
        "ounce" to "oz",
        "ounces" to "oz",
        "tablet" to "tablet",
        "tablets" to "tablet",
        "pill" to "pill",
        "pills" to "pill",
    )
}

sealed interface UsageAmountParse {
    data object Missing : UsageAmountParse

    /** [unit] is null when the phrase was a number with no unit token. */
    data class Parsed(val number: Double, val unit: String?) : UsageAmountParse

    data class Unparsed(val originalText: String, val reason: UsageParseFailure) : UsageAmountParse
}

enum class UsageParseFailure {
    MALFORMED,
    NEGATIVE,
    NONFINITE,
    TRAILING_PROSE,
}
