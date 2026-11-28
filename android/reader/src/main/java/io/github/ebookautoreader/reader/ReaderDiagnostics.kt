package io.github.ebookautoreader.reader

import android.os.Debug
import android.os.SystemClock
import android.util.Log

internal object ReaderDiagnostics {
    private const val TAG = "EbookReader"

    fun event(message: String) {
        Log.i(TAG, message)
    }

    fun memory(): String {
        val vm = Runtime.getRuntime()
        return "pssKiB=${Debug.getPss()} nativeAllocatedKiB=${Debug.getNativeHeapAllocatedSize() / 1024}" +
            " javaUsedKiB=${(vm.totalMemory() - vm.freeMemory()) / 1024}"
    }

    suspend fun <T> measure(stage: String, operation: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        event("$stage start")
        var result = "ok"
        try {
            return operation()
        } catch (error: Throwable) {
            result = error.javaClass.simpleName
            throw error
        } finally {
            event("$stage end ms=${SystemClock.elapsedRealtime() - start} result=$result")
        }
    }
}
