package io.github.ebookautoreader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable

internal class ReaderFailure(message: String, val reason: String = "reader_failure") : Exception(message)

internal interface PageFrame : Closeable {
    val signature: PageSignature
    val pageNumber: Int? get() = null
    val headerSignature: PageSignature? get() = null
}
internal interface ReaderPages {
    suspend fun capture(): PageFrame
    suspend fun prepareTurn(forward: Boolean, previous: PageSignature): suspend () -> PageFrame
    suspend fun read(frame: PageFrame): PageText
}
internal const val MAX_CLIP_BYTES = 4L * 1024 * 1024

internal interface ReaderClip : Closeable {
    val durationMs: Int
    val bytes: Long
}

internal interface ReaderAudio {
    val rate: Float
    suspend fun synthesize(unit: ReadingUnit): ReaderClip
    suspend fun play(audio: ReaderClip, started: () -> Unit, progress: (Int) -> Unit)
}

internal enum class ReaderStage { CAPTURE, OCR, SYNTHESIS, PLAYBACK, NEXT, RESTORE }
internal data class ReaderState(
    val displayed: Int? = null,
    val ocr: Int? = null,
    val synthesized: Position? = null,
    val playing: Position? = null,
    val completed: Position? = null,
    val bufferMs: Long = 0,
    val confirmed: Position? = null,
    val milliseconds: Int = 0,
    val stage: ReaderStage = ReaderStage.CAPTURE,
)

internal class ReaderSession(
    private val pages: ReaderPages,
    private val audio: ReaderAudio,
    pronunciations: Pronunciations,
    private val report: (ReaderState) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    private companion object {
        const val AHEAD_PAGES = 4
        const val BUFFER_TARGET_MS = 20_000
        const val MAX_CLIPS = 8
    }

    private data class KnownPage(
        val index: Int, val signature: PageSignature, val pageNumber: Int?, val header: PageSignature?,
    ) {
        fun matches(frame: PageFrame) = frame.signature.matches(signature) &&
            (header == null || frame.headerSignature?.identical(header) == true) &&
            (pageNumber == null || frame.pageNumber == null || pageNumber == frame.pageNumber)
    }
    private data class CapturedPage(val index: Int, val frame: PageFrame)
    private class Pending(val unit: ReadingUnit, var clip: ReaderClip? = null)

    // Both producer coroutines and playback own this state on the caller's dispatcher.
    // Device OCR and file work switch dispatchers inside their respective ports.
    private val sentences = SentenceBuffer(pronunciations)
    private val known = mutableMapOf<Int, KnownPage>()
    private val recent = ArrayDeque<PageSignature>()
    private val pending = ArrayDeque<Pending>()
    private val frames = ArrayDeque<CapturedPage>()
    private var readingFrame = false
    private var captured = -1
    private var captureFinished = false
    private val changes = MutableStateFlow(0L)
    private var cursor: KnownPage? = null
    private var decoded = -1
    private var reachedFailure: ReaderFailure? = null
    private var trusted = true
    private var interruptedTurn: KnownPage? = null
    var restoreFailure: ReaderFailure? = null
        private set
    private var state = ReaderState()
    private var runStarted = 0L
    private val milestones = mutableSetOf<String>()

    private fun milestone(name: String) {
        if (milestones.add(name)) log("startup $name elapsedMs=${clock() - runStarted}")
    }

    private fun update(value: ReaderState) {
        val elapsed = if (value.playing != null) value.milliseconds else 0
        state = value.copy(bufferMs = availableMs(elapsed))
        report(state)
        changes.value++
    }

    private fun availableMs(elapsed: Int = if (state.playing != null) state.milliseconds else 0): Long {
        val sourceMs = pending.sumOf { it.clip?.durationMs?.toLong() ?: 0 }
        return ((sourceMs - elapsed).coerceAtLeast(0) / audio.rate.toDouble()).toLong()
    }

    private fun clipCount() = pending.count { it.clip != null }

    suspend fun run(startedAt: Long = clock()) {
        runStarted = startedAt
        milestones.clear()
        try {
            coroutineScope {
                verifyOrStart()
                launch { producePages() }
                launch { produceText() }
                launch { produceAudio() }
                consume()
            }
        } finally {
            // Structured cancellation has joined both workers, including any dispatched turn.
            // Keep the source units for replay, and release every prepared player and WAV.
            frames.forEach { it.frame.close() }
            frames.clear()
            captured = decoded
            captureFinished = false
            pending.forEach { entry ->
                entry.clip?.close()
                entry.clip = null
            }
            update(state.copy(playing = null, synthesized = null, milliseconds = 0))
        }
    }

    private suspend fun consume() {
        var lastEnd: Long? = null
        var lastStartPage: Int? = null
        var starvations = 0
        var totalWait = 0L
        try {
            while (true) {
                val current = pending.firstOrNull()
                val clip = current?.clip
                if (clip == null) {
                    if (current == null && !sentences.hasUnit() && captureFinished && !readingFrame && frames.isEmpty()) reachedFailure?.let { throw it }
                    update(state.copy(playing = null, stage = ReaderStage.NEXT))
                    if (lastEnd != null) starvations++
                    val waitStarted = clock()
                    try {
                        changes.first {
                            pending.firstOrNull()?.clip != null ||
                                (reachedFailure != null && captureFinished && !readingFrame && frames.isEmpty() && pending.isEmpty() && !sentences.hasUnit())
                        }
                    } finally {
                        if (lastEnd != null) {
                            val wait = clock() - waitStarted
                            totalWait += wait
                            log("buffer wait ms=$wait")
                        }
                    }
                    continue
                }

                val unit = requireNotNull(current).unit
                var startedAt: Long? = null
                audio.play(clip, started = {
                    val now = clock()
                    milestone("playback")
                    val gap = lastEnd?.let { now - it }
                    startedAt = now
                    update(state.copy(playing = unit.start, confirmed = unit.start,
                        milliseconds = 0, stage = ReaderStage.PLAYBACK))
                    log("buffer start page=${unit.start.page} remainingMs=${state.bufferMs}" +
                        " gaps=$starvations gapMs=${gap ?: 0}" +
                        " pageBoundaryMs=${if (lastStartPage != null && lastStartPage != unit.start.page) gap else null}")
                }, progress = { ms -> update(state.copy(milliseconds = ms)) })
                val now = clock()
                log("audio completed page=${unit.end.page} wallMs=${startedAt?.let { now - it }} sourceMs=${clip.durationMs}")
                lastEnd = now
                lastStartPage = unit.start.page
                pending.removeFirst()
                clip.close()
                known.keys.removeAll { it < minOf(unit.end.page, requireNotNull(cursor).index) }
                update(state.copy(playing = null, completed = unit.end, confirmed = unit.end,
                    milliseconds = 0, stage = ReaderStage.NEXT))
            }
        } finally {
            log("buffer stop gaps=$starvations waitMs=$totalWait")
        }
    }

    private suspend fun producePages() {
        var lastCheck = clock()
        try {
            while (true) {
                val version = changes.value
                val from = state.confirmed?.page ?: 0
                val limit = from + AHEAD_PAGES
                val visible = requireNotNull(cursor).index
                when {
                    reachedFailure != null -> return
                    visible < captured -> {
                        move(forward = true, target = visible + 1).close()
                        lastCheck = clock()
                    }
                    captured < limit && frames.size + (if (readingFrame) 1 else 0) < 2 -> {
                        captureNext()
                        lastCheck = clock()
                    }
                    decoded >= limit && pending.isEmpty() && !sentences.hasUnit() -> {
                        if (sentences.oldestPage() == null) {
                            throw ReaderFailure("先読み範囲内に本文がありません。表示を確認してください。")
                        }
                        sentences.flush()
                        update(state)
                    }
                    clock() - lastCheck >= 3_000 -> {
                        verifyCursor()
                        lastCheck = clock()
                    }
                    else -> changes.first { it != version }
                }
            }
        } catch (failure: ReaderFailure) {
            reachedFailure = failure
            log("prefetch stopped reason=${failure.reason}")
            if (!trusted) throw failure
        } finally {
            captureFinished = true
            update(state)
        }
    }

    private suspend fun produceText() {
        while (true) {
            val version = changes.value
            val item = frames.removeFirstOrNull()
            if (item == null) {
                if (captureFinished) {
                    if (reachedFailure?.reason == "page_unchanged") sentences.flush()
                    update(state)
                    return
                }
                changes.first { it != version }
                continue
            }
            readingFrame = true
            update(state)
            try {
                item.frame.use { decode(it, item.index) }
            } catch (failure: ReaderFailure) {
                reachedFailure = failure
                log("prefetch stopped stage=ocr reason=${failure.reason} page=${item.index}")
                frames.forEach { it.frame.close() }
                frames.clear()
                update(state)
                return
            } finally {
                readingFrame = false
                update(state)
            }
        }
    }

    private suspend fun produceAudio() {
        while (true) {
            val version = changes.value
            if (clipCount() >= MAX_CLIPS || availableMs() >= BUFFER_TARGET_MS) {
                changes.first { it != version }
                continue
            }
            val entry = pending.firstOrNull { it.clip == null }
                ?: sentences.poll()?.let { Pending(it).also(pending::addLast) }
            if (entry == null) {
                changes.first { it != version }
                continue
            }
            if (state.playing == null) update(state.copy(stage = ReaderStage.SYNTHESIS))
            val clip = audio.synthesize(entry.unit)
            if (clip.bytes > MAX_CLIP_BYTES || clip.durationMs <= 0) {
                clip.close()
                throw ReaderFailure("合成音声の容量または長さが不正です。", "audio_clip_limit")
            }
            entry.clip = clip
            milestone("synthesis")
            update(state.copy(synthesized = entry.unit.end))
            log("buffer prepared page=${entry.unit.end.page} clips=${clipCount()} remainingMs=${state.bufferMs}")
        }
    }

    private suspend fun verifyOrStart() {
        if (!trusted) throw ReaderFailure("表示位置を確認できません。読書ページを開き直してください。")
        captureFinished = false
        reachedFailure = null
        if (cursor == null) {
            update(state.copy(stage = ReaderStage.CAPTURE))
            val frame = pages.capture()
            milestone("capture")
            register(KnownPage(0, frame.signature, frame.pageNumber, frame.headerSignature))
            frames.addLast(CapturedPage(0, frame))
            captured = 0
            update(state)
        } else {
            update(state.copy(stage = ReaderStage.CAPTURE))
            checkedCapture(requireNotNull(cursor)).close()
            milestone("capture")
        }
    }

    private suspend fun captureNext() {
        val target = captured + 1
        val displayed = requireNotNull(cursor)
        if (state.playing == null) update(state.copy(stage = ReaderStage.CAPTURE))
        val frame = if (displayed.index == target) checkedCapture(displayed) else {
            check(displayed.index == captured)
            move(forward = true, target = target)
        }
        if (reachedFailure != null) {
            frame.close()
            return
        }
        frames.addLast(CapturedPage(target, frame))
        captured = target
        update(state)
    }

    private suspend fun decode(frame: PageFrame, index: Int) {
        if (state.playing == null) update(state.copy(stage = ReaderStage.OCR))
        val page = pages.read(frame)
        milestone("ocr")
        if (page.kind in listOf(PageKind.BODY, PageKind.HEADING) && page.lines.none { it.original.isNotBlank() }) {
            throw ReaderFailure("本文を確認できません。")
        }
        sentences.add(index, page)
        decoded = index
        update(state.copy(ocr = index, stage = if (state.playing == null) ReaderStage.NEXT else ReaderStage.PLAYBACK))
    }

    private fun register(page: KnownPage) {
        known[page.index] = page
        cursor = page
        trusted = true
        interruptedTurn = null
        if (recent.none(page.signature::matches)) recent.addLast(page.signature)
        if (recent.size > 128) recent.removeFirst()
        update(state.copy(displayed = page.index))
    }

    private suspend fun checkedCapture(expected: KnownPage): PageFrame {
        val frame = try {
            pages.capture()
        } catch (failure: ReaderFailure) {
            trusted = false
            update(state.copy(displayed = null))
            throw failure
        }
        if (expected.matches(frame)) return frame
        frame.close()
        trusted = false
        update(state.copy(displayed = null))
        throw ReaderFailure("表示ページが変わりました。自動操作を停止しました。", "page_position_changed")
    }

    private suspend fun verifyCursor(): Boolean {
        val displayed = cursor ?: return false
        return checkedCapture(displayed).use { true }
    }

    private suspend fun move(forward: Boolean, target: Int): PageFrame {
        currentCoroutineContext().ensureActive()
        if (!trusted) throw reachedFailure ?: ReaderFailure("表示位置を確認できません。", "page_position_unknown")
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
            run {
                trusted = false
                update(state.copy(displayed = null))
                val frame = turn()
                result = frame
                val matches = if (expected != null) expected.matches(frame)
                    else forward && recent.all { frame.signature.compare(it) == PageComparison.DIFFERENT }
                if (frame.signature.matches(from.signature)) {
                    if (!from.matches(frame)) {
                        throw ReaderFailure("画面とページ番号が一致しません。", "page_position_unknown")
                    }
                    // The source confirmed stable captures with the original page.
                    trusted = true
                    update(state.copy(displayed = from.index))
                    throw ReaderFailure("ページが変わりませんでした。巻末か、手動でページが送れるか確認してください。", "page_unchanged")
                }
                if (!matches) {
                    if (recent.any(frame.signature::matches) && expected == null) {
                        throw ReaderFailure("以前と同じページが表示されたため停止しました。", "page_duplicate")
                    }
                    throw ReaderFailure("送り先のページを確認できません。表示位置を確認してください。", "page_position_unknown")
                }
                register(KnownPage(target, frame.signature, frame.pageNumber ?: expected?.pageNumber, frame.headerSignature))
            }
            currentCoroutineContext().ensureActive()
            return requireNotNull(result)
        } catch (error: Throwable) {
            result?.close()
            if (error is CancellationException && !trusted) interruptedTurn = from
            throw error
        }
    }

    suspend fun restore(): Boolean {
        restoreFailure = null
        if (!trusted) {
            // Cancellation after a gesture may leave the old or a known adjacent page.
            // Never guess a new page or retry the interrupted gesture.
            val origin = interruptedTurn ?: run {
                restoreFailure = reachedFailure
                return false
            }
            val frame = try { pages.capture() } catch (failure: ReaderFailure) {
                restoreFailure = failure
                return false
            }
            val verified = frame.use {
                known.values.filter { page -> kotlin.math.abs(page.index - origin.index) <= 1 && page.matches(frame) }.singleOrNull()
            }
            if (verified == null) {
                restoreFailure = ReaderFailure("中断後の位置を確定できません。", "page_position_unknown")
                return false
            }
            register(verified)
        }
        val from = cursor ?: return true
        val target = state.confirmed?.page ?: 0
        val destination = if (from.index >= target) target..from.index else from.index..target
        if (destination.last - destination.first > AHEAD_PAGES || destination.any { it !in known }) return false
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
        } catch (error: ReaderFailure) {
            restoreFailure = error
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
