package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class DigitTemplatesTest {
    private fun glyph(n: Int): FloatArray = FloatArray(560) { if (it / 56 == n) 1f else 0f }
    private fun page(t: DigitTemplates, n: Int) = t.observe(n.toString().map { glyph(it - '0') }, n.toString())
    private fun trained(): DigitTemplates = DigitTemplates().also { t ->
        for (n in 100..129) page(t, n)
    }

    @Test fun learnsFromDistinctPagesAndRecognizesUnseenNumber() {
        val t = trained()
        assertEquals(10, t.learned)
        assertEquals(390, t.match(listOf(glyph(3), glyph(9), glyph(0))).number)
    }

    @Test fun mismatchDoesNotEraseExistingTemplates() {
        val t = trained()
        assertEquals("same_digit_mismatch", t.observe(listOf(glyph(5)), "1"))
        assertEquals(10, t.learned)
        assertEquals(3, t.match(listOf(glyph(3))).number)
    }

    @Test fun samePageIsNotCountedTwice() {
        val t = DigitTemplates()
        assertEquals("accepted", page(t, 11))
        assertEquals("duplicate_page", page(t, 11))
        assertEquals(0, t.learned)
        page(t, 12)
        assertEquals(1, t.learned)
    }

    @Test fun refusesAmbiguousOrIncompleteMatchesAndClearsSession() {
        val t = trained()
        assertEquals("low_confidence", t.match(listOf(FloatArray(560) { 0.5f })).reason)
        assertEquals("segmentation", t.match(emptyList()).reason)
        assertEquals("leading_zero", t.match(listOf(glyph(0), glyph(1))).reason)
        t.clear()
        assertEquals(0, t.learned)
        assertEquals("incomplete_templates", t.match(listOf(glyph(3))).reason)
    }

    @Test fun separatesLeadingRunsAndRejectsDarkBackground() {
        val width = 60; val height = 30
        val pixels = IntArray(width * height) { 255 }
        for (y in 5..24) for (x in 2..7) pixels[y * width + x] = 0
        for (y in 5..24) for (x in 10..15) pixels[y * width + x] = 0
        for (y in 5..24) for (x in 28..44) pixels[y * width + x] = 0
        assertEquals(2, DigitSegmentation.fromGray(pixels, width, height)?.glyphs?.size)
        assertNull(DigitSegmentation.fromGray(IntArray(width * height), width, height))
    }
}
