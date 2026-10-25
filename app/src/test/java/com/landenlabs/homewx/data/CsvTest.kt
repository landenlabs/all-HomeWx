package com.landenlabs.homewx.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test fun plainFieldIsUnquoted() = assertEquals("abc", Csv.field("abc"))

    @Test fun nullBecomesEmpty() = assertEquals("", Csv.field(null))

    @Test fun specialCharactersAreQuotedAndEscaped() {
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"line1\nline2\"", Csv.field("line1\nline2"))
    }

    @Test fun parsesSimpleRowsAndEmptyFields() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "", "3")),
            Csv.parse("a,b,c\n1,,3\n")
        )
    }

    @Test fun handlesCrlfAndMissingTrailingNewline() {
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a,b\r\n1,2"))
    }

    @Test fun parsesQuotedCommasQuotesAndNewlines() {
        assertEquals(
            listOf(listOf("x"), listOf("a,b", "say \"hi\"", "l1\nl2")),
            Csv.parse("x\n\"a,b\",\"say \"\"hi\"\"\",\"l1\nl2\"\n")
        )
    }

    @Test fun roundTripsAwkwardValues() {
        val values = listOf("", "plain", "a,b", "q\"uote", "multi\nline", "cr\r\nlf", " spaced ", "Rain, heavy \"at times\"")
        val line = values.joinToString(",") { Csv.field(it) } + "\n"
        assertEquals(listOf(values), Csv.parse(line))
    }

    @Test fun emptyInputHasNoRows() = assertEquals(emptyList<List<String>>(), Csv.parse(""))
}
