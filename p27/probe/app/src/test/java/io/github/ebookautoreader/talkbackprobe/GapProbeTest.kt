package io.github.ebookautoreader.talkbackprobe
import org.junit.Assert.*
import org.junit.Test
class GapProbeTest {
    @Test fun noCandidatesBeforeSpeech() {
        val g = GapProbe(); assertTrue(g.due(10000).isEmpty())
    }
    @Test fun thresholdsOnceAndCancelledByNewSpeech() {
        val g = GapProbe()
        g.update(0, true); g.update(40000, false)
        assertTrue(g.due(40999).isEmpty())
        assertEquals(listOf(1000L, 1500L), g.due(41500))
        assertTrue(g.due(41999).isEmpty())
        g.update(42000, true); assertTrue(g.due(50000).isEmpty())
        g.update(50000, false); assertEquals(listOf(1000L,1500L,2000L,3000L), g.due(53000))
        g.reset(); assertTrue(g.due(60000).isEmpty())
    }
}
