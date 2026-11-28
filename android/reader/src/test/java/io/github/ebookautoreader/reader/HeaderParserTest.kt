package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class HeaderParserTest {
    @Test fun splitOrMerged() {
        assertEquals(HeaderObservation(120, "書籍A"), HeaderParser.parse(listOf("１２０", "書籍A")))
        assertEquals(HeaderObservation(120, "書籍A"), HeaderParser.parse(listOf("120 書籍A")))
    }
    @Test fun noNumberIsNotIdentity() {
        assertEquals(HeaderObservation(null, null), HeaderParser.parse(listOf("書籍120")))
        assertEquals(HeaderObservation(120, null), HeaderParser.parse(listOf("120")))
    }
    @Test fun normalizeWhitespace() {
        assertEquals("書籍A", HeaderParser.normalizedTitle("書籍 Ａ"))
    }
}
