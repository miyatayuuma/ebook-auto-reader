package io.github.ebookautoreader.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AcquisitionRunTest {
    private fun key(n: Int) = n.toString(16).padStart(64, '0')
    private class Fake(private val numbers: List<Int?>, start: Int = 0) : ReaderPages {
        var index = start
        var turns = 0
        var frames = 0
        var closed = 0
        override suspend fun capture(): PageFrame {
            val i = index
            frames++
            return object : PageFrame {
                override val signature = PageSignature(ByteArray(120) { if (it % 4 == i) 110 else 10 })
                override val pageNumber = numbers[i]
                override suspend fun exactKey() = (i+1).toString(16).padStart(64, '0')
                override fun close() { closed++ }
            }
        }
        override suspend fun prepareTurn(forward: Boolean, previous: PageSignature): suspend () -> PageFrame {
            check(forward)
            return { turns++; index++; capture() }
        }
        override suspend fun read(frame: PageFrame): PageText = error("not used")
    }

    @Test fun exactNumberRangeAndNoLastTurn() = runTest {
        val pages = Fake(listOf(1, 2, 3))
        val checkpoints = mutableListOf<Int>()
        val result = AcquisitionRun(pages, { key(100 + it.pageNumber!!) }, { null },
            { checkpoints += it.pages.size }).run(AcquisitionProgress(1, 3, emptyList()))
        assertEquals(listOf(1, 2, 3), result.pages.map { it.number })
        assertEquals(listOf(1, 2, 3), checkpoints)
        assertEquals(2, pages.turns)
        assertEquals(pages.frames, pages.closed)
    }
    @Test fun gapStopsBeforeSavingWrongPage() = runTest {
        val pages = Fake(listOf(1, 3))
        val saved = mutableListOf<Int>()
        val error = runCatching {
            AcquisitionRun(pages, { key(100 + it.pageNumber!!) }, { null },
                { saved += it.pages.last().number }).run(AcquisitionProgress(1, 3, emptyList()))
        }.exceptionOrNull() as ReaderFailure
        assertEquals("acquire_number_mismatch", error.reason)
        assertEquals(listOf(1), saved)
        assertEquals(pages.frames, pages.closed)
    }
    @Test fun resumeVerifiesAnchorAndDoesNotSaveItTwice() = runTest {
        val pages = Fake(listOf(1, 2, 3), 1)
        val initial = AcquisitionProgress(1, 3, listOf(AcquiredPage(1, key(1), key(101)),
            AcquiredPage(2, key(2), key(102))))
        val saved = mutableListOf<Int>()
        val result = AcquisitionRun(pages, { key(100 + it.pageNumber!!) }, { key(102) },
            { saved += it.pages.last().number }).run(initial)
        assertEquals(listOf(3), saved)
        assertEquals(3, result.pages.size)
        assertEquals(1, pages.turns)
        assertEquals(pages.frames, pages.closed)
    }
    @Test fun wrongAnchorStopsWithoutTurning() = runTest {
        val pages = Fake(listOf(1, 2))
        val initial = AcquisitionProgress(1, 2, listOf(AcquiredPage(1, key(2), key(101))))
        val error = runCatching {
            AcquisitionRun(pages, { error("not allowed") }, { key(101) },
                { error("not allowed") }).run(initial)
        }.exceptionOrNull() as ReaderFailure
        assertEquals("acquire_anchor_mismatch", error.reason)
        assertEquals(0, pages.turns)
        assertEquals(pages.frames, pages.closed)
    }
}
