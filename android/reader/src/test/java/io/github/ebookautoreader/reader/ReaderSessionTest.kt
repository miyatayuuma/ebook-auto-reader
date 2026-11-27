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
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionTest {
    @Test
    fun joinsAcrossPagesAndPreparesDuringActualPlayback() = runTest {
        val pages = FakePages(listOf(body("朝です。川岸の石は"), body("光った。"), body("鳥が鳴いた。")))
        val audio = FakeAudio()
        val states = mutableListOf<ReaderState>()
        val session = ReaderSession(pages, audio, Pronunciations(), states::add)
        val result = async { runCatching { session.run() } }

        advanceTimeBy(700)
        assertEquals(listOf("朝です。"), audio.starts)
        assertEquals(1, pages.display)
        assertEquals(listOf(0, 1), pages.decoded)
        assertEquals(0, states.last().playing?.page)
        assertEquals(1, states.last().synthesized?.page)
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
        assertEquals(1, pages.display)
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
        val session = ReaderSession(pages, audio, Pronunciations()) {}
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
        val session = ReaderSession(pages, audio, Pronunciations()) {}
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
        val session = ReaderSession(duplicate, audio, Pronunciations()) {}
        val outcome = runCatching { session.run() }
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("重複"))
        assertEquals(listOf(0, 1), duplicate.decoded)
        assertEquals(listOf("一です。", "二です。"), audio.starts)
        assertFalse(session.restore())
        assertEquals(2, duplicate.turns.size)

        val stuck = FakePages(listOf(body("一です。")))
        val stuckSession = ReaderSession(stuck, FakeAudio(), Pronunciations()) {}
        assertTrue(runCatching { stuckSession.run() }.exceptionOrNull()?.message.orEmpty().contains("変わりません"))
        assertTrue(stuckSession.restore())
        assertEquals(listOf(true), stuck.turns)
    }

    @Test
    fun manualMovementAfterPausePreventsResumeAndAnyFurtherGesture() = runTest {
        val pages = FakePages(listOf(body("朝です。"), body("鳥です。"), body("夜です。")))
        val session = ReaderSession(pages, FakeAudio(), Pronunciations()) {}
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
        val session = ReaderSession(pages, audio, Pronunciations()) {}
        val result = runCatching { session.run() }
        assertTrue(result.isFailure)
        assertEquals(listOf("晴れです。", "[図表]", "図の説明。"), audio.starts)
        assertEquals(listOf(0, 1, 2), pages.decoded)
    }

    @Test
    fun unreadablePageDoesNotTriggerAnotherTurn() = runTest {
        val pages = FakePages(listOf(body("晴れです。"), body(""), body("読まれません。")))
        val audio = FakeAudio()
        val session = ReaderSession(pages, audio, Pronunciations()) {}
        assertTrue(runCatching { session.run() }.isFailure)
        assertEquals(listOf(0, 1), pages.decoded)
        assertTrue(session.restore())
        assertEquals(listOf(true, false), pages.turns)
        assertEquals(listOf("晴れです。"), audio.starts)
    }

    private fun body(vararg lines: String) = PageText(lines.map(::SourceLine), PageKind.BODY)

    private class FakePages(private val pages: List<PageText>,
                            private val signatures: List<String> = pages.indices.map(Int::toString)) : ReaderPages {
        var display = 0
        var verificationDelay = 0L
        var frames = 0
        var closedFrames = 0
        val decoded = mutableListOf<Int>()
        val turns = mutableListOf<Boolean>()

        private inner class Frame(val page: Int) : PageFrame {
            override val signature = signatures[page]
            override fun close() { closedFrames++ }
        }

        override suspend fun capture(): PageFrame {
            delay(10)
            frames++
            return Frame(display)
        }

        override suspend fun prepareTurn(forward: Boolean, previous: String): suspend () -> PageFrame {
            delay(verificationDelay)
            if (previous != signatures[display]) throw ReaderFailure("manual movement")
            return {
                turns += forward
                delay(55)
                display = (display + if (forward) 1 else -1).coerceIn(pages.indices)
                capture()
            }
        }

        override suspend fun read(frame: PageFrame): PageText {
            delay(50)
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
        private inner class Clip(val unit: ReadingUnit) : Closeable {
            override fun close() { closedClips++ }
        }

        override suspend fun synthesize(unit: ReadingUnit): Closeable {
            delay(if (unit.spoken == slowText) 2_000 else 70)
            clips += unit
            return Clip(unit)
        }

        override suspend fun play(audio: Closeable, started: () -> Unit, progress: (Int) -> Unit) {
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
