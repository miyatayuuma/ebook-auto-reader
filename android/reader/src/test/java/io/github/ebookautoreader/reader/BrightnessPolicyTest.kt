package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class BrightnessPolicyTest {
    @Test fun ownedMinimumOrPartialChangeCanRestore() {
        val original = BrightnessState(1, 120)
        assertTrue(BrightnessPolicy.canRestore(BrightnessState(0, 1), original))
        assertTrue(BrightnessPolicy.canRestore(BrightnessState(0, 120), original))
    }
    @Test fun externalChangeWins() {
        val original = BrightnessState(1, 120)
        assertFalse(BrightnessPolicy.canRestore(BrightnessState(0, 45), original))
        assertFalse(BrightnessPolicy.canRestore(BrightnessState(1, 1), original))
    }
}
