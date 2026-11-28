package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class DigitTemplatesTest {
    private fun glyph(n: Int): FloatArray = FloatArray(560) { if (it / 56 == n) 1f else 0f }

    @Test fun learnsOnlyWithMatchingLabelsAndRepeatedObservations() {
        val t = DigitTemplates()
        t.observe(listOf(glyph(1)), "12")
        assertEquals(0, t.learned)
        for (n in 0..9) t.observe(listOf(glyph(n)), "$n")
        assertEquals(0, t.learned)
        assertEquals("incomplete_templates", t.match(listOf(glyph(3))).reason)
        for (n in 0..9) t.observe(listOf(glyph(n)), "$n")
        assertEquals(10, t.learned)
        assertEquals(39, t.match(listOf(glyph(3), glyph(9))).number)
    }

    @Test fun uncertainAndContradictoryInputsAreRejected() {
        val t = DigitTemplates()
        for (n in 0..9) repeat(2) { t.observe(listOf(glyph(n)), "$n") }
        assertEquals("low_confidence", t.match(listOf(FloatArray(560) { 0.5f })).reason)
        assertEquals("segmentation", t.match(emptyList()).reason)
        assertEquals("segmentation", t.match(listOf(FloatArray(1))).reason)
        assertEquals("leading_zero", t.match(listOf(glyph(0), glyph(1))).reason)
        t.observe(listOf(glyph(5)), "1")
        assertEquals(0, t.learned)
    }

    @Test fun repeatedDigitInOnePageIsOneObservation() {
        val t = DigitTemplates()
        t.observe(listOf(glyph(1), glyph(1)), "11")
        assertEquals(0, t.learned)
        t.observe(listOf(glyph(1)), "1")
        assertEquals(1, t.learned)
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
