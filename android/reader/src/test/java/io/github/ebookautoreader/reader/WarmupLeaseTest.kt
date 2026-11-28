package io.github.ebookautoreader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarmupLeaseTest {
    @Test fun claimWaitsForInitializationAndTransfersOwnershipOnlyOnce() = runTest {
        var releases = 0
        val init = launch { delay(500) }
        val lease = WarmupLease(init) { releases++ }
        val claim = async { lease.claim() }
        advanceTimeBy(250)
        assertFalse(claim.isCompleted)
        advanceTimeBy(251)
        assertTrue(claim.await())
        assertFalse(lease.claim())
        lease.discard()
        assertEquals(0, releases)
    }

    @Test fun discardWaitsForNativeWorkAndPreventsClaim() = runTest {
        var releases = 0
        val init = launch {
            withContext(NonCancellable) { delay(500) }
        }
        val lease = WarmupLease(init) { releases++ }
        runCurrent()
        val claim = async { lease.claim() }
        runCurrent()
        lease.discard()
        lease.discard()
        assertEquals(0, releases)
        advanceTimeBy(501)
        assertFalse(claim.await())
        assertEquals(1, releases)
    }

    @Test fun cancelledClaimDoesNotLeakOrTakeOwnership() = runTest {
        var releases = 0
        val init = launch { delay(500) }
        val lease = WarmupLease(init) { releases++ }
        val claim = async { lease.claim() }
        runCurrent()
        claim.cancel()
        lease.discard()
        runCurrent()
        assertEquals(1, releases)
    }
}
