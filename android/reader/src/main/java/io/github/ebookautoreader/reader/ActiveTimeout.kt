package io.github.ebookautoreader.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select

// The gate is sampled on the same dispatcher as the window operations. Time while
// Android's shade or the DMM menu is visible does not consume the active budget.
internal suspend fun <T> withActiveTimeout(
    limitMs: Long,
    blocked: () -> Boolean,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    onDiscard: (T) -> Unit = {},
    action: suspend () -> T,
): T = coroutineScope {
    var produced: T? = null
    var delivered = false
    val work = async { action().also { produced = it } }
    val timer = async {
        var remaining = limitMs
        var previous = clock()
        var wasBlocked = blocked()
        while (remaining > 0) {
            delay(50)
            val now = clock()
            val isBlocked = blocked()
            if (!wasBlocked && !isBlocked) remaining -= (now - previous).coerceAtLeast(0)
            previous = now
            wasBlocked = isBlocked
        }
    }
    try {
        select {
            work.onAwait { it.also { delivered = true } }
            timer.onAwait { throw ReaderFailure("ページ遷移の確認が時間内に完了しませんでした。", "page_in_transition") }
        }
    } finally {
        work.cancel()
        timer.cancel()
        // Cleanup a frame completed concurrently with timeout/cancellation.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            work.join()
            if (!delivered) produced?.let(onDiscard)
        }
    }
}
