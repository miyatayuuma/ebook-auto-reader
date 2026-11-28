package io.github.ebookautoreader.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One service-wide request clock, updated immediately before actual dispatch.
 * Cancellation retains the most recent dispatch timestamp. Retry only rate limiting.
 */
internal class ScreenshotGate(
    private val clock: () -> Long,
    private val intervalMs: Long = 420,
    private val retryMs: Long = 600,
) {
    private val lock = Mutex()
    private var last: Long? = null

    suspend fun <T> capture(before: suspend () -> Unit, request: suspend () -> T): T = lock.withLock {
        repeat(3) { attempt ->
            val remaining = last?.let { intervalMs - (clock() - it) } ?: 0
            if (remaining > 0) delay(remaining)
            before()
            last = clock()
            try {
                return@withLock request()
            } catch (failure: ReaderFailure) {
                if (failure.reason != "capture_android_3" || attempt == 2) throw failure
                delay(retryMs)
            }
        }
        error("unreachable")
    }
}
