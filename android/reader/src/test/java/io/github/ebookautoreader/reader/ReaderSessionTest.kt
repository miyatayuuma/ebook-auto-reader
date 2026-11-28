package io.github.ebookautoreader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionTest {
    @Test
    fun firstClipStartsWithoutWaitingForNextPageOrBuffer() = runTest {
        val pages = FakePages(listOf(body("最初です。"), body("続きです。"))).apply { readDelay = 1_000 }
        val audio = FakeAudio()
        val logs = mutableListOf<String>()
        val session = ReaderSession(pages, audio, Pronunciations(), {},
            clock = { testScheduler.currentTime }, log = logs::add)
        val work = async { session.run() }
        advanceTimeBy(1_200)
        assertEquals(listOf(0), pages.decoded)
        assertEquals(listOf("最初です。"), audio.starts)
        assertTrue(logs.any { it.startsWith("startup playback ") })
        work.cancelAndJoin()
    }

    @Test
    fun figureContinuesPrefetchToFollowingBody() = runTest {
        val pages = FakePages(listOf(body("最初です。"), PageText(emptyList(), PageKind.FIGURE), body("続きです。")))
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        runCatching { session.run() }
        assertEquals(listOf(0, 1, 2), pages.decoded)
        assertEquals(listOf("最初です。", "[図表]", "続きです。"), audio.starts)
    }

    @Test
    fun joinsAcrossPagesAndPreparesDuringActualPlayback() = runTest {
        val pages = FakePages(listOf(body("朝です。川岸の石は"), body("光った。"), body("鳥が鳴いた。")))
        val audio = FakeAudio()
        val states = mutableListOf<ReaderState>()
        val session = ReaderSession(pages, audio, Pronunciations(), states::add)
        val result = async { runCatching { session.run() } }

        advanceTimeBy(700)
        assertEquals(listOf("朝です。"), audio.starts)
        assertEquals(2, pages.display)
        assertEquals(listOf(0, 1, 2), pages.decoded)
        assertEquals(0, states.last().playing?.page)
        assertEquals(2, states.last().synthesized?.page)
        assertEquals(Position(0, 0, 0), states.last().confirmed)

        runCurrent()
        assertTrue(result.await().exceptionOrNull() is ReaderFailure)
        assertEquals(listOf("朝です。", "川岸の石は光った。", "鳥が鳴いた。"), audio.starts)
        assertEquals(listOf(SourceRange(0, 0, 4, 9), SourceRange(1, 0, 0, 4)), audio.clips[1].ranges)
        assertEquals(Position(2, 0, 6), states.last().confirmed)
        assertTrue(session.restore())
        assertEquals(pages.frames, pages.closedFrames)
        assertEquals(audio.clips.size, audio.closedClips)
    }

    @Test
    fun pauseRestoresAndResumesAtSentenceHeadWithoutLosingPreparedSentence() = runTest {
        val pages = FakePages(listOf(body("朝です。"), body("鳥です。"), body("夜です。")))
        val audio = FakeAudio()
        val states = mutableListOf<ReaderState>()
        val session = ReaderSession(pages, audio, Pronunciations(), states::add)
        val first = async { session.run() }
        advanceTimeBy(700)
        assertEquals(2, pages.display)
        first.cancelAndJoin()
        assertTrue(session.restore())
        assertEquals(0, pages.display)
        assertEquals(Position(0, 0, 0), states.last().confirmed)

        val second = async { runCatching { session.run() } }
        assertTrue(second.await().exceptionOrNull() is ReaderFailure)
        assertEquals(listOf("朝です。", "朝です。", "鳥です。", "夜です。"), audio.starts)
        assertEquals(listOf(0, 1, 2), pages.decoded)
        assertEquals(pages.frames, pages.closedFrames)
        assertEquals(audio.clips.size, audio.closedClips)
    }

    @Test
    fun pauseWhileNextSynthesisIsPendingDoesNotDropTheNextSentence() = runTest {
        val pages = FakePages(listOf(body("先の文。次の文。"), body("続きです。")))
        val audio = FakeAudio().apply { slowText = "次の文。" }
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val first = async { session.run() }
        advanceTimeBy(300)
        assertEquals(listOf("先の文。"), audio.starts)
        first.cancelAndJoin()
        assertTrue(session.restore())
        audio.slowText = null
        val second = async { runCatching { session.run() } }
        assertTrue(second.await().isFailure)
        assertEquals(listOf("先の文。", "先の文。", "次の文。", "続きです。"), audio.starts)
        assertEquals(audio.clips.size, audio.closedClips)
    }

    @Test
    fun pauseDuringTurnVerificationCancelsTheUnsentGesture() = runTest {
        val pages = FakePages(listOf(body("朝です。"), body("次です。"))).apply { verificationDelay = 500 }
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val first = async { session.run() }
        advanceTimeBy(300)
        first.cancelAndJoin()
        assertEquals(listOf("朝です。"), audio.starts)
        assertEquals(emptyList<Boolean>(), pages.turns)
        assertTrue(session.restore())
        assertEquals(0, pages.display)
    }

    @Test
    fun duplicateStopsWithoutReadingSamePageTwiceAndUnchangedIsRestorable() = runTest {
        val duplicate = FakePages(listOf(body("一です。"), body("二です。"), body("三です。")),
            signatures = listOf("a", "b", "a"))
        val audio = FakeAudio()
        val session = ReaderSession(duplicate, audio, Pronunciations(), {})
        val outcome = runCatching { session.run() }
        assertEquals("page_duplicate", (outcome.exceptionOrNull() as? ReaderFailure)?.reason)
        assertEquals(listOf(0, 1), duplicate.decoded)
        assertEquals(listOf("一です。"), audio.starts)
        assertFalse(session.restore())
        assertEquals(2, duplicate.turns.size)

        val stuck = FakePages(listOf(body("一です。")))
        val stuckSession = ReaderSession(stuck, FakeAudio(), Pronunciations(), {})
        assertEquals("page_unchanged",
            (runCatching { stuckSession.run() }.exceptionOrNull() as? ReaderFailure)?.reason)
        assertTrue(stuckSession.restore())
        assertEquals(listOf(true), stuck.turns)
    }

    @Test
    fun manualMovementAfterPausePreventsResumeAndAnyFurtherGesture() = runTest {
        val pages = FakePages(listOf(body("朝です。"), body("鳥です。"), body("夜です。")))
        val session = ReaderSession(pages, FakeAudio(), Pronunciations(), {})
        val first = async { session.run() }
        advanceTimeBy(700)
        first.cancelAndJoin()
        assertTrue(session.restore())
        val turnsBeforeManual = pages.turns.size
        pages.display = 2
        assertTrue(runCatching { session.run() }.isFailure)
        assertFalse(session.restore())
        assertEquals(turnsBeforeManual, pages.turns.size)
    }

    @Test
    fun confirmedBlankIsSkippedAndFigureGetsNoticeBeforeCaption() = runTest {
        val pages = FakePages(listOf(body("晴れです。"), PageText(emptyList(), PageKind.BLANK),
            PageText(listOf(SourceLine("図の説明。")), PageKind.FIGURE)))
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val result = runCatching { session.run() }
        assertTrue(result.isFailure)
        assertEquals(listOf("晴れです。", "[図表]", "図の説明。"), audio.starts)
        assertEquals(listOf(0, 1, 2), pages.decoded)
    }

    @Test
    fun unreadablePageDoesNotReadLaterPages() = runTest {
        val pages = FakePages(listOf(body("晴れです。"), body(""), body("読まれません。")))
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        assertTrue(runCatching { session.run() }.isFailure)
        assertEquals(listOf(0, 1), pages.decoded)
        assertTrue(session.restore())
        assertEquals(0, pages.display)
        assertEquals(listOf("晴れです。"), audio.starts)
    }

    @Test
    fun uncertainTurnStopsPlaybackBeforeAnyOtherGestureAndCannotBeRestoredAutomatically() = runTest {
        val pages = FakePages(listOf(body("晴れです。"), body("続きです。"))).apply {
            verificationDelay = 100
            turnFailure = ReaderFailure("表示が動いています", "page_in_transition")
        }
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val result = runCatching { session.run() }
        assertEquals("page_in_transition", (result.exceptionOrNull() as? ReaderFailure)?.reason)
        assertFalse(session.restore())
        assertEquals(listOf(true), pages.turns)
        assertEquals(listOf(0), pages.decoded)
        assertEquals(listOf("晴れです。"), audio.starts)
    }

    @Test
    fun shortPagesPrefetchDuringPlaybackAndRestoreFourKnownPages() = runTest {
        val source = (0..8).map { body("短い文${it}です。") }
        val pages = FakePages(source)
        val audio = FakeAudio()
        val states = mutableListOf<ReaderState>()
        val session = ReaderSession(pages, audio, Pronunciations(), states::add)
        val first = async { session.run() }
        advanceTimeBy(350)
        assertTrue(pages.decoded.size >= 3)
        advanceTimeBy(400)
        assertEquals(listOf("短い文0です。"), audio.starts)
        assertEquals(4, pages.display)
        assertEquals(Position(0, 0, 0), states.last().confirmed)
        first.cancelAndJoin()
        assertTrue(session.restore())
        assertEquals(0, pages.display)
        assertEquals(listOf(false, false, false, false), pages.turns.takeLast(4))
        assertEquals(audio.clips.size, audio.closedClips)
    }

    @Test
    fun rateChangesBufferAndSlowSynthesisRecordsAStarvation() = runTest {
        val source = FakePages(listOf(body("先の文。次の文。"), body("最後です。")))
        val audio = FakeAudio().apply { slowText = "次の文。" }
        val states = mutableListOf<ReaderState>()
        val logs = mutableListOf<String>()
        val session = ReaderSession(source, audio, Pronunciations(), states::add,
            clock = { testScheduler.currentTime }, log = logs::add)
        val work = async { runCatching { session.run() } }
        advanceTimeBy(350)
        val before = states.last().bufferMs
        audio.rate = 1.5f
        advanceTimeBy(100)
        assertTrue(states.last().bufferMs < before * 0.75)
        assertTrue(work.await().isFailure)
        assertTrue(logs.any { it.contains("gaps=1") && it.contains("gapMs=1000") })
        assertEquals(listOf("先の文。", "次の文。", "最後です。"), audio.starts)
    }

    @Test
    fun drainsTenPagesInOrderAndKeepsTheTrailingTextOnAnUnchangedPage() = runTest {
        val expected = (0..9).map { index -> if (index == 9) "最後の行" else "本文${index}です。" }
        val source = FakePages(expected.map { body(it) })
        val audio = FakeAudio()
        val session = ReaderSession(source, audio, Pronunciations(), {})
        assertEquals("page_unchanged", (runCatching { session.run() }.exceptionOrNull() as? ReaderFailure)?.reason)
        assertEquals(expected, audio.starts)
        assertEquals((0..9).toList(), source.decoded)
        assertTrue(session.restore())
        assertEquals(9, source.display)
    }

    @Test
    fun capturesNextPageWhileOcrIsStillRunningAndReleasesFramesOnCancel() = runTest {
        val pages = FakePages((0..5).map { body("本文${it}です。") }).apply { readDelay = 1_000 }
        val session = ReaderSession(pages, FakeAudio(), Pronunciations(), {})
        val work = async { session.run() }
        advanceTimeBy(500)
        assertTrue(pages.display >= 1)
        assertTrue(pages.decoded.isEmpty())
        assertTrue(pages.frames - pages.closedFrames <= 2)
        work.cancelAndJoin()
        assertEquals(pages.frames, pages.closedFrames)
        assertTrue(session.restore())
    }

    @Test
    fun prefetchContinuesWhenAudioHasTwentySecondsBuffered() = runTest {
        val pages = FakePages((0..6).map { body("本文${it}です。") })
        val audio = FakeAudio().apply { clipDuration = 25_000 }
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val work = async { session.run() }
        advanceTimeBy(500)
        assertTrue(pages.display >= 3)
        assertTrue(pages.decoded.size >= 3)
        work.cancelAndJoin()
        assertEquals(pages.frames, pages.closedFrames)
        assertEquals(audio.clips.size, audio.closedClips)
    }

    @Test
    fun pauseAfterEndDetectionRecapturesDiscardedOcrFramesOnResume() = runTest {
        val pages = FakePages(listOf(body("最初です。"), body("最後です。"))).apply { readDelay = 300 }
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations(), {})
        val first = async { session.run() }
        advanceTimeBy(500)
        first.cancelAndJoin()
        assertTrue(session.restore())
        val outcome = runCatching { session.run() }
        assertEquals("page_unchanged", (outcome.exceptionOrNull() as? ReaderFailure)?.reason)
        assertEquals(listOf("最初です。", "最初です。", "最後です。"), audio.starts)
        assertEquals(pages.frames, pages.closedFrames)
    }

    private fun body(vararg lines: String) = PageText(lines.map { SourceLine(it) }, PageKind.BODY)

    private class FakePages(private val pages: List<PageText>,
                            private val signatures: List<String> = pages.indices.map(Int::toString)) : ReaderPages {
        var display = 0
        var verificationDelay = 0L
        var readDelay = 50L
        var turnFailure: ReaderFailure? = null
        private fun signature(page: Int): PageSignature {
            val code = signatures[page].first().code
            return PageSignature(ByteArray(120) { if (code and (1 shl (it / 15)) != 0) 110 else 10 })
        }
        var frames = 0
        var closedFrames = 0
        val decoded = mutableListOf<Int>()
        val turns = mutableListOf<Boolean>()

        private inner class Frame(val page: Int) : PageFrame {
            override val signature = signature(page)
            override fun close() { closedFrames++ }
        }

        override suspend fun capture(): PageFrame {
            delay(10)
            frames++
            return Frame(display)
        }

        override suspend fun prepareTurn(forward: Boolean, previous: PageSignature): suspend () -> PageFrame {
            delay(verificationDelay)
            if (!previous.matches(signature(display))) throw ReaderFailure("manual movement")
            return {
                turns += forward
                delay(55)
                turnFailure?.let { throw it }
                display = (display + if (forward) 1 else -1).coerceIn(pages.indices)
                capture()
            }
        }

        override suspend fun read(frame: PageFrame): PageText {
            delay(readDelay)
            val page = (frame as Frame).page
            decoded += page
            return pages[page]
        }
    }

    private class FakeAudio : ReaderAudio {
        val clips = mutableListOf<ReadingUnit>()
        val starts = mutableListOf<String>()
        var closedClips = 0
        var slowText: String? = null
        var clipDuration = 1_000
        override var rate = 1f
        private inner class Clip(val unit: ReadingUnit) : ReaderClip {
            override val durationMs = clipDuration
            override val bytes = 10L
            override fun close() { closedClips++ }
        }

        override suspend fun synthesize(unit: ReadingUnit): ReaderClip {
            delay(if (unit.spoken == slowText) 2_000 else 70)
            clips += unit
            return Clip(unit)
        }

        override suspend fun play(audio: ReaderClip, started: () -> Unit, progress: (Int) -> Unit) {
            val unit = (audio as Clip).unit
            started()
            starts += if (unit.noticePage != null) "[図表]" else unit.spoken
            repeat(10) {
                delay(100)
                progress((it + 1) * 100)
            }
        }
    }
}
