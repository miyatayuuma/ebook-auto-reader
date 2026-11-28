package io.github.ebookautoreader.lab

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NdlTensorShapeTest {
    @Test fun concretizesDetectorBatch() {
        val shape = longArrayOf(-1, 3, 800, 800)
        assertArrayEquals(longArrayOf(1, 3, 800, 800), ndlImageShape(shape))
        assertArrayEquals(longArrayOf(-1, 3, 800, 800), shape)
        assertArrayEquals(longArrayOf(1, 2), ndlTargetSizeShape(longArrayOf(-1, 2)))
    }

    @Test fun supportsStaticAndDynamicRecognizerBatches() {
        val shape = longArrayOf(1, 3, 32, 768)
        assertArrayEquals(shape, ndlImageShape(shape))
        assertArrayEquals(shape, ndlImageShape(longArrayOf(-1, 3, 32, 768)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDynamicSpatialAxis() {
        ndlImageShape(longArrayOf(-1, 3, -1, 768))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDynamicChannelAxis() {
        ndlImageShape(longArrayOf(-1, -1, 800, 800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDynamicTargetSizeAxis() {
        ndlTargetSizeShape(longArrayOf(-1, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompatibleStaticBatch() {
        ndlImageShape(longArrayOf(2, 3, 800, 800))
    }
}
