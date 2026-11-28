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

    @Test fun localOcrFailureIsDurablyDeferredAndLaterPagesAreSaved() = runTest {
        val pages = Fake(listOf(1, 2, 3))
        val snapshots = mutableListOf<AcquisitionProgress>()
        val result = AcquisitionRun(pages, {
            if (it.pageNumber == 2) throw ReaderFailure("fixture", "ocr_unclassified_page")
            key(100 + it.pageNumber!!)
        }, { null }, { snapshots += it }).run(AcquisitionProgress(1, 3, emptyList()))
        assertEquals(listOf(1, 2, 3), result.pages.map { it.number })
        assertEquals(2, result.saved)
        assertEquals(0, result.remaining)
        assertFalse(result.complete)
        assertEquals(listOf(2), result.unresolved.map { it.number })
        assertEquals("ocr_unclassified_page", snapshots[1].pages.last().failure)
        assertEquals(2, pages.turns)
        assertEquals(pages.frames, pages.closed)
    }

    @Test fun storageErrorAndCheckpointErrorNeverAdvance() = runTest {
        for (checkpointFails in listOf(false, true)) {
            val pages = Fake(listOf(1, 2))
            val error = runCatching {
                AcquisitionRun(pages, {
                    if (!checkpointFails) throw ReaderFailure("fixture", "acquire_cache_write")
                    key(101)
                }, { null }, { throw ReaderFailure("fixture", "acquire_checkpoint_write") })
                    .run(AcquisitionProgress(1, 2, emptyList()))
            }.exceptionOrNull() as ReaderFailure
            assertEquals(if (checkpointFails) "acquire_checkpoint_write" else "acquire_cache_write", error.reason)
            assertEquals(0, pages.turns)
            assertEquals(pages.frames, pages.closed)
        }
    }

    @Test fun resumeFromDeferredAnchorAndRetryOnlyRequestedPage() = runTest {
        val initial = AcquisitionProgress(1, 3, listOf(AcquiredPage(1, key(1), key(101)),
            AcquiredPage(2, key(2), null, "ocr_incomplete_body")))
        val scan = Fake(listOf(1, 2, 3), 1)
        val scanned = AcquisitionRun(scan, { key(103) }, { error("deferred has no cache") },
            {}).run(initial)
        assertEquals(2, scanned.saved)
        val retry = Fake(listOf(1, 2, 3), 1)
        val finished = AcquisitionRun(retry, { key(102) }, { null }, {}).retry(scanned, 2)
        assertTrue(finished.complete)
        assertEquals(listOf(key(101), key(102), key(103)), finished.pages.map { it.cacheKey })
        assertEquals(0, retry.turns)
        assertEquals(retry.frames, retry.closed)
    }

    @Test fun retryWithWrongExactImageCannotReplacePendingPage() = runTest {
        val initial = AcquisitionProgress(2, 3, listOf(AcquiredPage(2, key(2), null, "ocr_incomplete_body")))
        val retry = Fake(listOf(2, 3))
        val error = runCatching {
            AcquisitionRun(retry, { error("not allowed") }, { null }, { error("not allowed") }).retry(initial, 2)
        }.exceptionOrNull() as ReaderFailure
        assertEquals("acquire_anchor_mismatch", error.reason)
        assertEquals(0, retry.turns)
        assertEquals(retry.frames, retry.closed)
    }

    @Test fun legacyJournalMigratesAndV2RoundTripsDeferredPage() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use {
            it.writeInt(0x45414331); it.writeInt(1); it.writeInt(3); it.writeInt(1)
            it.writeInt(1); it.writeUTF(key(1)); it.writeUTF(key(101))
        }
        val old = AcquisitionJournalCodec.read(java.io.DataInputStream(bytes.toByteArray().inputStream()))
        assertEquals(1, old.saved)
        val next = old.copy(pages = old.pages + AcquiredPage(2, key(2), null, "ocr_unclassified_page"))
        bytes.reset()
        java.io.DataOutputStream(bytes).use { AcquisitionJournalCodec.write(it, next) }
        assertEquals(next, AcquisitionJournalCodec.read(java.io.DataInputStream(bytes.toByteArray().inputStream())))
    }
}
