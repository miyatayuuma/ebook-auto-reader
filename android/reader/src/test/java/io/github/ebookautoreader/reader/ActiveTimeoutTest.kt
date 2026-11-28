package io.github.ebookautoreader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTimeoutTest {
    @Test fun pausedTimeDoesNotConsumeBudget() = runTest {
        var held = true
        val task = async {
            withActiveTimeout(1_000, { held }, { testScheduler.currentTime }) {
                delay(5_500)
                "ok"
            }
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(task.isCompleted)
        held = false
        advanceTimeBy(500)
        runCurrent()
        assertEquals("ok", task.await())
    }

    @Test fun activeTimeIsFinite() = runTest {
        val task = async {
            runCatching {
                withActiveTimeout(1_000, { false }, { testScheduler.currentTime }) { awaitCancellation() }
            }
        }
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("page_in_transition", (task.await().exceptionOrNull() as? ReaderFailure)?.reason)
    }

    @Test fun waitingCanBeCancelled() = runTest {
        val task = async {
            withActiveTimeout(1_000, { true }, { testScheduler.currentTime }) { awaitCancellation() }
        }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }
}
