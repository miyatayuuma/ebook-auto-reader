package io.github.ebookautoreader.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AcquiredPage(val number: Int, val exactKey: String, val cacheKey: String)
internal data class AcquisitionProgress(val first: Int, val last: Int, val pages: List<AcquiredPage>) {
    val total: Int get() = last - first + 1
    init {
        require(first in 0..9999 && last in first..9999 && total <= 1000)
        require(pages.size <= total)
        require(pages.withIndex().all { (i, page) -> page.number == first + i &&
            KEY.matches(page.exactKey) && KEY.matches(page.cacheKey) })
        require(pages.map { it.exactKey }.toSet().size == pages.size)
    }
    companion object { private val KEY = Regex("[0-9a-f]{64}") }
}

/** Strict numbered trial. No guessing of unnumbered pages, no backward gestures,
 * no movement after the requested last page. Checkpoint before moving forward. */
internal class AcquisitionRun(
    private val pages: ReaderPages,
    private val persist: suspend (PageFrame) -> String,
    private val lookup: suspend (PageFrame) -> String?,
    private val checkpoint: suspend (AcquisitionProgress) -> Unit,
) {
    suspend fun run(initial: AcquisitionProgress): AcquisitionProgress {
        var progress = initial
        if (progress.pages.size == progress.total) return progress
        var frame: PageFrame? = pages.capture()
        try {
            var expected = progress.pages.lastOrNull()?.number ?: progress.first
            var resumeAnchor = progress.pages.lastOrNull()
            while (true) {
                currentCoroutineContext().ensureActive()
                val current = requireNotNull(frame)
                if (current.pageNumber != expected) throw ReaderFailure(
                    "ページ番号を確認できません。指定ページを表示してください。", "acquire_number_mismatch")
                val exact = current.exactKey() ?: throw ReaderFailure(
                    "ページの画像識別情報がありません。", "acquire_identity_missing")
                if (resumeAnchor != null) {
                    if (resumeAnchor.exactKey != exact || resumeAnchor.cacheKey != lookup(current))
                        throw ReaderFailure("前回の取得ページと一致しません。", "acquire_anchor_mismatch")
                    resumeAnchor = null
                } else {
                    if (progress.pages.any { it.exactKey == exact }) throw ReaderFailure(
                        "取得済みページと重複しています。", "acquire_duplicate")
                    val key = persist(current)
                    currentCoroutineContext().ensureActive()
                    val next = progress.copy(pages = progress.pages + AcquiredPage(expected, exact, key))
                    checkpoint(next)
                    progress = next
                }
                if (expected == progress.last) return progress
                // The page port verifies the origin and stable destination.
                val signature = current.signature
                current.close()
                frame = null
                val turn = pages.prepareTurn(true, signature)
                frame = turn()
                expected++
            }
        } finally { frame?.close() }
    }
}
