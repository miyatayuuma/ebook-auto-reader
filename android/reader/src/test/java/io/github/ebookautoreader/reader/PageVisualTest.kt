package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class PageVisualTest {
    @Test
    fun marginsAndMildRasterChangesDoNotCountAsPageTurnsButBottomTextDoes() {
        val width = PageVisual.WIDTH
        val height = PageVisual.HEIGHT
        val paper = IntArray(width * height) { 245 }
        val chrome = paper.copyOf().also { pixels ->
            pixels.fill(15, 0, width * 4)
            pixels.fill(20, width * (height - 4))
            pixels[width * 60 + 25] = 237
        }
        val different = paper.copyOf().also { pixels ->
            for (row in height - 13 until height - 8) {
                for (x in 30..42) pixels[row * width + x] = 18
            }
        }
        val original = PageVisual.signature(paper)
        assertEquals(PageComparison.SAME, original.compare(PageVisual.signature(chrome)))
        assertEquals(PageComparison.DIFFERENT, original.compare(PageVisual.signature(different)))
    }

    @Test
    fun requiresThreeStableChangedFramesAndNeverTreatsATransientAsUnchanged() {
        val origin = PageSignature(ByteArray(120) { 100 })
        val intermediate = PageSignature(ByteArray(120) { 150.toByte() })
        val next = PageSignature(ByteArray(120) { 200.toByte() })
        val moved = PageTransition(origin)
        for (frame in listOf(origin, intermediate, origin, next, next)) {
            assertEquals(PageComparison.UNCERTAIN, moved.observe(frame))
        }
        assertEquals(PageComparison.DIFFERENT, moved.observe(next))

        val reversed = PageTransition(origin)
        assertEquals(PageComparison.UNCERTAIN, reversed.observe(intermediate))
        repeat(6) { assertEquals(PageComparison.UNCERTAIN, reversed.observe(origin)) }

        val unchanged = PageTransition(origin)
        repeat(3) { assertEquals(PageComparison.UNCERTAIN, unchanged.observe(origin)) }
        assertEquals(PageComparison.SAME, unchanged.observe(origin))
    }

    @Test
    fun aBottomMenuNeedsABoundaryAndASeparateControlTrack() {
        val width = PageVisual.WIDTH
        val height = PageVisual.HEIGHT
        val luma = IntArray(width * height) { 245 }
        for (row in 123 until 150) luma.fill(48, row * width, (row + 1) * width)
        assertFalse(PageVisual.hasMenu(luma))
        luma.fill(210, 133 * width + 12, 133 * width + 82)
        assertTrue(PageVisual.hasMenu(luma))
    }
}
