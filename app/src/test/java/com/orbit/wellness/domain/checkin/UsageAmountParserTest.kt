package com.orbit.wellness.domain.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAmountParserTest {
    @Test
    fun numbersWithAndWithoutUnitsParse() {
        assertParsed("3", 3.0, null)
        assertParsed("10mg", 10.0, "mg")
        assertParsed("10 mg", 10.0, "mg")
        assertParsed("0.5mg", 0.5, "mg")
        assertParsed(".5 mg", 0.5, "mg")
        assertParsed("  3  ", 3.0, null)
    }

    @Test
    fun unitAliasesFoldCaseAndMicroSymbols() {
        assertParsed("10 MG", 10.0, "mg")
        assertParsed("10 μg", 10.0, "mcg")
        assertParsed("10 µg", 10.0, "mcg")
        assertParsed("2 tablets", 2.0, "tablet")
        assertParsed("1 g", 1.0, "g")
        assertParsed("1000 mg", 1000.0, "mg")
    }

    @Test
    fun blankNegativeNonfiniteAndProseStayOutOfParsed() {
        assertTrue(UsageAmountParser.parse("") is UsageAmountParse.Missing)
        assertTrue(UsageAmountParser.parse("   ") is UsageAmountParse.Missing)
        assertUnparsed(" -2 ", UsageParseFailure.NEGATIVE)
        assertUnparsed("NaN", UsageParseFailure.NONFINITE)
        assertUnparsed("Infinity", UsageParseFailure.NONFINITE)
        assertUnparsed("-Infinity", UsageParseFailure.NONFINITE)
        assertUnparsed("3 after lunch", UsageParseFailure.TRAILING_PROSE)
        assertUnparsed("10 mg later", UsageParseFailure.TRAILING_PROSE)
        assertUnparsed("nope", UsageParseFailure.MALFORMED)
        assertUnparsed("1e3", UsageParseFailure.MALFORMED)
    }

    @Test
    fun unparsedTextIsTheOriginalPhrase() {
        val original = "  3 after lunch  "
        val parsed = UsageAmountParser.parse(original) as UsageAmountParse.Unparsed
        assertEquals(original, parsed.originalText)
        assertEquals(UsageParseFailure.TRAILING_PROSE, parsed.reason)
    }

    private fun assertParsed(text: String, number: Double, unit: String?) {
        val parsed = UsageAmountParser.parse(text) as UsageAmountParse.Parsed
        assertEquals(number, parsed.number, 0.0)
        assertEquals(unit, parsed.unit)
    }

    private fun assertUnparsed(text: String, reason: UsageParseFailure) {
        val parsed = UsageAmountParser.parse(text) as UsageAmountParse.Unparsed
        assertEquals(text, parsed.originalText)
        assertEquals(reason, parsed.reason)
    }
}
