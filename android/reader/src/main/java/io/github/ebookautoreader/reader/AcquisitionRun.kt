package io.github.ebookautoreader.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Only page-local, semantic OCR failures may be deferred. Native/model,
// timeout, storage and navigation errors remain fatal to the scan.
internal val DEFERRED_OCR_REASONS = setOf("ocr_unclassified_page", "ocr_incomplete_body")
internal data class AcquiredPage(val number: Int, val exactKey: String, val cacheKey: String?,
                                 val failure: String? = null)
internal data class AcquisitionProgress(val first: Int, val last: Int, val pages: List<AcquiredPage>) {
    val total: Int get() = last - first + 1
    val saved: Int get() = pages.count { it.cacheKey != null }
    val unresolved: List<AcquiredPage> get() = pages.filter { it.cacheKey == null }
    val remaining: Int get() = total - pages.size
    val complete: Boolean get() = saved == total
    init {
        require(first in 0..9999 && last in first..9999 && total <= 1000)
        require(pages.size <= total)
        require(pages.withIndex().all { (i, page) -> page.number == first + i &&
            KEY.matches(page.exactKey) && (page.cacheKey?.let {
                KEY.matches(it) && page.failure == null
            } ?: (page.failure in DEFERRED_OCR_REASONS)) })
        require(pages.map { it.exactKey }.toSet().size == pages.size)
    }
    companion object { private val KEY = Regex("[0-9a-f]{64}") }
}

/** Strict numbered scan. Checkpoint a success or explicitly deferred OCR
 * failure before moving. Never turn after the requested last page. */
internal class AcquisitionRun(
    private val pages: ReaderPages,
    private val persist: suspend (PageFrame) -> String,
    private val lookup: suspend (PageFrame) -> String?,
    private val checkpoint: suspend (AcquisitionProgress) -> Unit,
) {
    private suspend fun identify(current: PageFrame, expected: Int): String {
        if (current.pageNumber != expected) throw ReaderFailure(
            "ページ番号を確認できません。指定ページを表示してください。", "acquire_number_mismatch")
        return current.exactKey() ?: throw ReaderFailure(
            "ページの画像識別情報がありません。", "acquire_identity_missing")
    }

    private suspend fun recognize(current: PageFrame, number: Int, exact: String): AcquiredPage {
        val result = try {
            AcquiredPage(number, exact, persist(current))
        } catch (failure: ReaderFailure) {
            if (failure.reason !in DEFERRED_OCR_REASONS) throw failure
            AcquiredPage(number, exact, null, failure.reason)
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun run(initial: AcquisitionProgress): AcquisitionProgress {
        var progress = initial
        if (progress.remaining == 0) return progress
        var frame: PageFrame? = pages.capture()
        try {
            var expected = progress.pages.lastOrNull()?.number ?: progress.first
            var resumeAnchor = progress.pages.lastOrNull()
            while (true) {
                currentCoroutineContext().ensureActive()
                val current = requireNotNull(frame)
                val exact = identify(current, expected)
                if (resumeAnchor != null) {
                    if (resumeAnchor.exactKey != exact ||
                        (resumeAnchor.cacheKey != null && resumeAnchor.cacheKey != lookup(current)))
                        throw ReaderFailure("前回の取得ページと一致しません。", "acquire_anchor_mismatch")
                    resumeAnchor = null
                } else {
                    if (progress.pages.any { it.exactKey == exact }) throw ReaderFailure(
                        "取得済みページと重複しています。", "acquire_duplicate")
                    val item = recognize(current, expected, exact)
                    val next = progress.copy(pages = progress.pages + item)
                    checkpoint(next)
                    progress = next
                }
                if (expected == progress.last) return progress
                val signature = current.signature
                current.close()
                frame = null
                val turn = pages.prepareTurn(true, signature)
                frame = turn()
                expected++
            }
        } finally { frame?.close() }
    }

    /** Retry one manually displayed unresolved page; no page gestures. */
    suspend fun retry(initial: AcquisitionProgress, number: Int): AcquisitionProgress {
        val old = initial.unresolved.singleOrNull { it.number == number }
            ?: throw ReaderFailure("指定ページは未解決一覧にありません。", "acquire_retry_not_pending")
        val current = pages.capture()
        try {
            val exact = identify(current, number)
            if (exact != old.exactKey)
                throw ReaderFailure("前回の取得ページと一致しません。", "acquire_anchor_mismatch")
            val item = recognize(current, number, exact)
            val next = initial.copy(pages = initial.pages.map { if (it.number == number) item else it })
            checkpoint(next)
            return next
        } finally { current.close() }
    }
}
