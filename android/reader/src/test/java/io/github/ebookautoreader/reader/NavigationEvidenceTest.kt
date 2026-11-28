package io.github.ebookautoreader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationEvidenceTest {
    private fun sig(value: Int) = PageSignature(ByteArray(128) { value.toByte() })
    private val origin = sig(10)
    private val band = sig(20)
    private val next = sig(90)
    private val nextBand = sig(100)

    @Test fun adjacentNumberNeedsTwoMatchingFrames() {
        val e = NavigationEvidence(origin, band, 20, true)
        assertTrue(e.needsNumber(next, nextBand))
        assertEquals(NavigationDecision.WAIT, e.observe(next, nextBand, 21).decision)
        assertFalse(e.needsNumber(next, nextBand))
        assertEquals(NavigationDecision.CHANGED, e.observe(next, nextBand, null).decision)
    }

    @Test fun wrongDistanceAndChangedHeaderAreNotAcceptedEarly() {
        val e = NavigationEvidence(origin, band, 20, false)
        e.observe(next, nextBand, 18)
        assertEquals(NavigationDecision.CONFLICT, e.observe(next, nextBand, null).decision)
        val f = NavigationEvidence(origin, band, 20, true)
        repeat(6) { assertEquals(NavigationDecision.WAIT, f.observe(origin, nextBand, 21).decision) }
    }

    @Test fun duplicateAndMissingNumbersUseStableBodyOnlyWithoutInterruption() {
        for (number in listOf(null, 20)) {
            val e = NavigationEvidence(origin, band, 20, true)
            assertEquals(NavigationDecision.WAIT, e.observe(next, nextBand, number).decision)
            assertEquals(NavigationDecision.WAIT, e.observe(next, nextBand, null).decision)
            assertEquals(NavigationDecision.CHANGED, e.observe(next, nextBand, null).decision)
        }
        val e = NavigationEvidence(origin, band, 20, true)
        e.observe(next, nextBand, 21)
        e.interrupted()
        repeat(5) { assertEquals(NavigationDecision.WAIT, e.observe(next, nextBand, null).decision) }
    }

    @Test fun originRequiresFourFramesAndNoPriorDeparture() {
        val e = NavigationEvidence(origin, band, 20, true)
        repeat(3) { assertEquals(NavigationDecision.WAIT, e.observe(origin, band, null).decision) }
        assertEquals(NavigationDecision.UNCHANGED, e.observe(origin, band, null).decision)
        val f = NavigationEvidence(origin, band, 20, true)
        f.observe(next, nextBand, 21)
        repeat(5) { assertEquals(NavigationDecision.WAIT, f.observe(origin, band, null).decision) }
    }

    @Test fun screenshotRetryAndCancellationRespectDispatchInterval() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        val dispatches = mutableListOf<Long>()
        val cancelled = async {
            gate.capture(before = {}) {
                dispatches += testScheduler.currentTime
                delay(10_000)
            }
        }
        runCurrent()
        advanceTimeBy(20)
        cancelled.cancelAndJoin()
        var attempts = 0
        val result = gate.capture(before = {}) {
            dispatches += testScheduler.currentTime
            if (attempts++ == 0) throw ReaderFailure("rate limited", "capture_android_3")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(0L, 420L, 1020L), dispatches)
    }

    @Test fun screenshotDoesNotRetryOtherFailures() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        var calls = 0
        val failure = runCatching {
            gate.capture(before = {}) {
                calls++
                throw ReaderFailure("secure", "capture_android_6")
            }
        }.exceptionOrNull()
        assertEquals(1, calls)
        assertEquals("capture_android_6", (failure as ReaderFailure).reason)
    }

    @Test fun activeTimeoutExcludesHoldButExpiresAfterResume() = runTest {
        var blocked = true
        val job = async {
            runCatching { withActiveTimeout(500, { blocked }, { testScheduler.currentTime }) { delay(10_000) } }
        }
        advanceTimeBy(2_000)
        assertFalse(job.isCompleted)
        blocked = false
        advanceTimeBy(650)
        runCurrent()
        assertEquals("page_in_transition", (job.await().exceptionOrNull() as ReaderFailure).reason)
    }
}
