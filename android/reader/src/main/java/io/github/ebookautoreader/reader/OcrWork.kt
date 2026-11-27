package io.github.ebookautoreader.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T : Any> runOcrWork(
    worker: CoroutineDispatcher,
    deadlineMs: Long,
    onTimeout: () -> Unit,
    terminate: () -> Unit,
    work: suspend () -> T,
): T = supervisorScope {
    val running = async(worker) { work() }
    try {
        withTimeoutOrNull(deadlineMs) { running.await() } ?: run {
            onTimeout()
            throw ReaderFailure("OCRが時間内に完了しませんでした。表示や文字サイズを確認してください。", "ocr_timeout")
        }
    } finally {
        if (!running.isCompleted) {
            // Await cancellation never closes a session or recycles its bitmap while JNI is still using it.
            running.cancel()
            terminate()
        }
    }
}
