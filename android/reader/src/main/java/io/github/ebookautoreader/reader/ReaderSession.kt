package io.github.ebookautoreader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

internal class ReaderFailure(message: String, val reason: String = "reader_failure") : Exception(message)

internal interface PageFrame : Closeable {
    val signature: String
}
internal interface ReaderPages {
    suspend fun capture(): PageFrame
    suspend fun prepareTurn(forward: Boolean, previous: String): suspend () -> PageFrame
    suspend fun read(frame: PageFrame): PageText
}
internal interface ReaderAudio {
    suspend fun synthesize(unit: ReadingUnit): Closeable
    suspend fun play(audio: Closeable, started: () -> Unit, progress: (Int) -> Unit)
}

internal enum class ReaderStage { CAPTURE, OCR, SYNTHESIS, PLAYBACK, NEXT, RESTORE }
internal data class ReaderState(
    val displayed: Int? = null,
    val ocr: Int? = null,
    val synthesized: Position? = null,
    val playing: Position? = null,
    val confirmed: Position? = null,
    val milliseconds: Int = 0,
    val stage: ReaderStage = ReaderStage.CAPTURE,
)

internal class ReaderSession(
    private val pages: ReaderPages,
    private val audio: ReaderAudio,
    pronunciations: Pronunciations,
    private val report: (ReaderState) -> Unit,
) {
    private data class KnownPage(val index: Int, val signature: String)
    private data class Prepared(val unit: ReadingUnit, val audio: Closeable) : Closeable by audio

    private val sentences = SentenceBuffer(pronunciations)
    private val known = mutableMapOf<Int, KnownPage>()
    private val recent = LinkedHashSet<String>()
    private var cursor: KnownPage? = null
    private var decoded = -1
    private var blankPage: Int? = null
    private var unfinished: ReadingUnit? = null
    private var queued: ReadingUnit? = null
    private var reachedFailure: ReaderFailure? = null
    private var trusted = true
    private var state = ReaderState()

    private fun update(value: ReaderState) {
        state = value
        report(value)
    }

    suspend fun run() = supervisorScope {
        verifyOrStart()
        var prepared: Prepared? = null
        var waiting: Prepared? = null
        var ahead: Deferred<Prepared?>? = null
        var pageTask: Deferred<Unit>? = null
        try {
            while (true) {
                if (unfinished == null && queued == null && !sentences.hasUnit()) pageTask?.await()
                val unit = unfinished ?: takeNext().also { unfinished = it }
                if (requireNotNull(cursor).index < unit.start.page) {
                    pageTask?.await()
                    for (next in requireNotNull(cursor).index + 1..unit.start.page) {
                        check(next in known)
                        move(forward = true, target = next).close()
                    }
                }
                val current = prepared ?: prepare(unit).also { prepared = it }
                check(current.unit === unit)
                val prefetchStarted = CompletableDeferred<Deferred<Unit>?>()
                ahead = async {
                    val nextUnit = reserveNext() ?: run {
                        prefetchStarted.await()?.await()
                        reserveNext()
                    }
                    nextUnit?.let { prepare(it) }?.also { waiting = it }
                }
                audio.play(current.audio, started = {
                    update(state.copy(playing = unit.start, confirmed = unit.start,
                        milliseconds = 0, stage = ReaderStage.PLAYBACK))
                    if (pageTask?.isActive != true && reachedFailure == null) {
                        pageTask = async { prefetch(unit.start.page) }
                    }
                    prefetchStarted.complete(pageTask)
                }, progress = { ms -> update(state.copy(milliseconds = ms)) })
                update(state.copy(playing = null, confirmed = unit.end, stage = ReaderStage.NEXT))
                unfinished = null
                prefetchStarted.complete(pageTask)
                prepared = null
                current.close()
                prepared = ahead.await()
                waiting = null
                ahead = null
                val keepFrom = minOf(unit.end.page, requireNotNull(cursor).index)
                known.keys.removeAll { it < keepFrom }
                if ((blankPage ?: Int.MAX_VALUE) < unit.end.page) blankPage = null
            }
        } finally {
            withContext(NonCancellable) {
                ahead?.cancel()
                pageTask?.cancelAndJoin()
                ahead?.cancelAndJoin()
            }
            prepared?.close()
            waiting?.close()
            update(state.copy(playing = null, milliseconds = 0))
        }
    }

    private suspend fun verifyOrStart() {
        if (!trusted) throw ReaderFailure("表示位置を確認できません。読書ページを開き直してください。")
        if (cursor == null) {
            update(state.copy(stage = ReaderStage.CAPTURE))
            pages.capture().use { frame ->
                register(KnownPage(0, frame.signature))
                decode(frame, 0)
            }
        } else {
            val displayed = requireNotNull(cursor)
            update(state.copy(stage = ReaderStage.CAPTURE))
            checkedCapture(displayed).use { frame ->
                if (decoded < 0) decode(frame, displayed.index)
            }
        }
    }

    private suspend fun prepare(unit: ReadingUnit): Prepared {
        if (state.playing == null) update(state.copy(stage = ReaderStage.SYNTHESIS))
        val clip = audio.synthesize(unit)
        try {
            update(state.copy(synthesized = unit.end))
            return Prepared(unit, clip)
        } catch (error: Throwable) {
            clip.close()
            throw error
        }
    }

    private suspend fun prefetch(playingPage: Int) {
        try {
            val visible = requireNotNull(cursor)
            val limit = limitFrom(playingPage)
            if (visible.index < decoded && visible.index < limit) {
                move(forward = true, target = visible.index + 1).close()
            }
            if (decoded < limit) loadNext()
        } catch (failure: ReaderFailure) {
            reachedFailure = failure
        }
    }

    private fun reserveNext(): ReadingUnit? = queued ?: sentences.poll()?.also { queued = it }

    private suspend fun takeNext(): ReadingUnit {
        queued?.let {
            queued = null
            return it
        }
        while (!sentences.hasUnit()) {
            reachedFailure?.let { throw it }
            val oldest = sentences.oldestPage()
            if (oldest != null && decoded >= limitFrom(oldest)) {
                sentences.flush()
                break
            }
            val limit = limitFrom(state.confirmed?.page ?: 0)
            if (decoded >= limit && oldest == null) {
                throw ReaderFailure("先読み先に本文がありません。表示ページを確認してください。")
            }
            loadNext()
        }
        return requireNotNull(sentences.poll())
    }

    private fun limitFrom(page: Int) = if (blankPage == page + 1) page + 2 else page + 1

    private suspend fun loadNext() {
        val target = decoded + 1
        var displayed = requireNotNull(cursor)
        while (displayed.index < decoded) {
            move(forward = true, target = displayed.index + 1).close()
            displayed = requireNotNull(cursor)
        }
        if (state.playing == null) update(state.copy(stage = ReaderStage.CAPTURE))
        val frame = if (displayed.index == target) checkedCapture(displayed) else {
            check(displayed.index == decoded)
            move(forward = true, target = target)
        }
        frame.use { decode(it, target) }
    }

    private suspend fun decode(frame: PageFrame, index: Int) {
        if (state.playing == null) update(state.copy(stage = ReaderStage.OCR))
        val page = pages.read(frame)
        if (page.kind in listOf(PageKind.BODY, PageKind.HEADING) && page.lines.none { it.original.isNotBlank() }) {
            throw ReaderFailure("本文を確認できません。")
        }
        sentences.add(index, page)
        decoded = index
        if (page.kind == PageKind.BLANK && blankPage == null) blankPage = index
        update(state.copy(ocr = index, stage = if (state.playing == null) ReaderStage.NEXT else ReaderStage.PLAYBACK))
    }

    private fun register(page: KnownPage) {
        known[page.index] = page
        cursor = page
        trusted = true
        recent.add(page.signature)
        if (recent.size > 128) recent.remove(recent.first())
        update(state.copy(displayed = page.index))
    }

    private suspend fun checkedCapture(expected: KnownPage): PageFrame {
        val frame = pages.capture()
        if (frame.signature == expected.signature) return frame
        frame.close()
        trusted = false
        update(state.copy(displayed = null))
        throw ReaderFailure("表示ページが変わりました。自動操作を停止しました。")
    }

    private suspend fun verifyCursor(): Boolean {
        val displayed = cursor ?: return false
        return checkedCapture(displayed).use { true }
    }

    private suspend fun move(forward: Boolean, target: Int): PageFrame {
        currentCoroutineContext().ensureActive()
        val from = requireNotNull(cursor)
        val expected = known[target]
        val turn = try {
            pages.prepareTurn(forward, from.signature)
        } catch (failure: ReaderFailure) {
            trusted = false
            update(state.copy(displayed = null))
            throw failure
        }
        currentCoroutineContext().ensureActive()
        var result: PageFrame? = null
        try {
            withContext(NonCancellable) {
                trusted = false
                update(state.copy(displayed = null))
                val frame = withTimeoutOrNull(4_000) { turn() }
                    ?: throw ReaderFailure("ページ遷移の確認が時間内に完了しませんでした。")
                result = frame
                val matches = if (expected != null) frame.signature == expected.signature
                    else forward && frame.signature !in recent
                if (frame.signature == from.signature) {
                    // The source confirmed two stable captures with the original signature.
                    trusted = true
                    update(state.copy(displayed = from.index))
                    throw ReaderFailure("ページが変わりませんでした。巻末または操作方向を確認してください。")
                }
                if (!matches) throw ReaderFailure("ページの重複または表示位置の不一致を検出しました。")
                register(KnownPage(target, frame.signature))
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } catch (error: Throwable) {
            result?.close()
            throw error
        }
    }

    suspend fun restore(): Boolean {
        if (!trusted) return false
        val from = cursor ?: return true
        val target = state.confirmed?.page ?: 0
        val destination = if (from.index >= target) target..from.index else from.index..target
        if (destination.last - destination.first > 2 || destination.any { it !in known }) return false
        update(state.copy(stage = ReaderStage.RESTORE))
        return try {
            verifyCursor()
            var index = from.index
            while (index != target) {
                val forward = index < target
                index += if (forward) 1 else -1
                move(forward, index).close()
            }
            true
        } catch (_: ReaderFailure) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
