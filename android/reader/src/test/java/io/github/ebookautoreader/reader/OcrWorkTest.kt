package io.github.ebookautoreader.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OcrWorkTest {
    @Test
    fun timeoutReportsAndSignalsNativeButRetainsOwnershipUntilItReturns() = runTest {
        val events = mutableListOf<String>()
        val nativeExit = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                runOcrWork(StandardTestDispatcher(testScheduler), 1_000,
                    onTimeout = { events += "timeout" }, terminate = { events += "terminate" }) {
                    try {
                        withContext(NonCancellable) { nativeExit.await() }
                    } finally {
                        events += "native_exit"
                    }
                    "page"
                }
            }
        }
        advanceTimeBy(1_001)
        assertEquals(listOf("timeout", "terminate"), events)
        assertFalse(result.isCompleted)
        nativeExit.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is ReaderFailure)
        assertEquals(listOf("timeout", "terminate", "native_exit"), events)
    }

    @Test
    fun explicitStopSignalsNativeWithoutAFalseTimeout() = runTest {
        val events = mutableListOf<String>()
        val nativeExit = CompletableDeferred<Unit>()
        val read = async {
            runOcrWork(StandardTestDispatcher(testScheduler), 1_000,
                onTimeout = { events += "timeout" }, terminate = { events += "terminate" }) {
                withContext(NonCancellable) { nativeExit.await() }
            }
        }
        runCurrent()
        read.cancel()
        runCurrent()
        assertEquals(listOf("terminate"), events)
        assertFalse(read.isCompleted)
        nativeExit.complete(Unit)
        read.join()
        assertTrue(read.isCancelled)
    }
}
