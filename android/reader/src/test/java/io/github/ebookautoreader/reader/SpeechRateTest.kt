package io.github.ebookautoreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRateTest {
    @Test
    fun sliderCoversFullRangeInFivePercentSteps() {
        assertEquals(1f, SpeechRate.fromProgress(0), 0f)
        assertEquals(2.5f, SpeechRate.fromProgress(30), 0f)
        for (step in 0..30) {
            val rate = (100 + step * 5) / 100f
            assertEquals(rate, SpeechRate.fromProgress(step), 0f)
            assertEquals(step, SpeechRate.progress(rate))
            assertEquals(rate, SpeechRate.normalize(rate), 0f)
        }
    }

    @Test
    fun legacyAndInvalidPreferencesAreNormalized() {
        assertEquals(1f, SpeechRate.normalize(.65f), 0f)
        assertEquals(1.65f, SpeechRate.normalize(1.65f), 0f)
        assertEquals(1.35f, SpeechRate.normalize(1.33f), 0f)
        assertEquals(2.5f, SpeechRate.normalize(3f), 0f)
        assertEquals(1f, SpeechRate.normalize(Float.NaN), 0f)
        assertEquals(1f, SpeechRate.fromProgress(-1), 0f)
        assertEquals(2.5f, SpeechRate.fromProgress(31), 0f)
    }
}
