package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class EyePrefetchPlanTest {
    @Test fun choosesOneOrTwoOnlyWhenRoundTripFits() {
        assertEquals(0, EyePrefetchPlan.depth(3_999, 1_600, 0, 3, 4))
        assertEquals(1, EyePrefetchPlan.depth(4_000, 1_600, 0, 3, 4))
        assertEquals(1, EyePrefetchPlan.depth(7_199, 1_600, 0, 3, 4))
        assertEquals(2, EyePrefetchPlan.depth(7_200, 1_600, 0, 3, 4))
    }

    @Test fun includesAlreadyCapturedDistanceAndBufferLimits() {
        assertEquals(0, EyePrefetchPlan.depth(7_200, 1_600, 2, 3, 4))
        assertEquals(1, EyePrefetchPlan.depth(20_000, 1_600, 2, 1, 4))
        assertEquals(1, EyePrefetchPlan.depth(20_000, 1_600, 2, 3, 1))
        assertEquals(0, EyePrefetchPlan.depth(20_000, 1_600, 0, 0, 4))
        assertEquals(0, EyePrefetchPlan.depth(20_000, 1_600, 0, 3, 0))
    }

    @Test fun slowTurnsImmediatelyReduceFuturePrefetch() {
        assertEquals(2_500L, EyePrefetchPlan.updateEstimate(1_600, 2_500))
        assertEquals(1_300L, EyePrefetchPlan.updateEstimate(1_600, 400))
        assertEquals(8_000L, EyePrefetchPlan.updateEstimate(1_600, 60_000))
    }
}
