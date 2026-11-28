package io.github.ebookautoreader.reader

/** Estimate a complete out-and-back trip, including already captured pages.
 * All values are wall-clock milliseconds; caller accounts for playback rate.
 */
internal object EyePrefetchPlan {
    const val INITIAL_TURN_MS = 1_600L
    private const val RETURN_MARGIN_MS = 800L
    private const val MAX_BURST = 2

    fun depth(remainingMs: Long, turnMs: Long, frontierDistance: Int, freeFrames: Int, ahead: Int): Int {
        val max = minOf(MAX_BURST, freeFrames, ahead).coerceAtLeast(0)
        val cost = turnMs.coerceIn(100L, 8_000L)
        return (max downTo 1).firstOrNull { count ->
            remainingMs >= (frontierDistance.coerceAtLeast(0).toLong() + count) * 2 * cost + RETURN_MARGIN_MS
        } ?: 0
    }

    // React immediately to a slower turn; relax the estimate gradually.
    fun updateEstimate(previous: Long, observed: Long): Long {
        val sample = observed.coerceIn(100L, 8_000L)
        return maxOf(sample, (previous * 3 + sample) / 4).coerceIn(100L, 8_000L)
    }
}
