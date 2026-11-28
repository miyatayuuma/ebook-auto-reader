package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingModeTest {
    @Test fun stableKeysRoundTrip() {
        assertEquals("ears", ReadingMode.EARS.key)
        assertEquals("eyes", ReadingMode.EYES.key)
        ReadingMode.entries.forEach { assertEquals(it, ReadingMode.fromKey(it.key)) }
        assertEquals(2, ReadingMode.entries.map { it.key }.distinct().size)
    }

    @Test fun missingOrUnknownPreferencePreservesExistingBehavior() {
        assertEquals(ReadingMode.EARS, ReadingMode.DEFAULT)
        listOf(null, "", "unknown", "EYES", "目で追う").forEach {
            assertEquals(ReadingMode.EARS, ReadingMode.fromKey(it))
        }
    }
}
