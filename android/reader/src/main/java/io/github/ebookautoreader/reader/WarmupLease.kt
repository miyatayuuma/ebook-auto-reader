package io.github.ebookautoreader.reader

import kotlinx.coroutines.Job

/**
 * Single-dispatcher ownership of a prewarming job.
 * Claim hands resources to the reader; discard releases after all child work
 * completes (including native work that cannot be interrupted immediately).
 */
internal class WarmupLease(private val job: Job, private val release: () -> Unit) {
    private var available = true

    suspend fun claim(): Boolean {
        job.join()
        if (!available) return false
        available = false
        return true
    }

    fun discard() {
        if (!available) return
        available = false
        job.invokeOnCompletion { release() }
        job.cancel()
    }
}
