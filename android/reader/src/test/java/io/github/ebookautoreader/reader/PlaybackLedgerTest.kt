package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Test

class PlaybackLedgerTest {
    private fun span(id: Long, frames: Long) =
        PlaybackSpan(id, Position(id.toInt(), 0, 0), Position(id.toInt(), 0, 1), frames)

    @Test fun submissionDoesNotMarkReadAndPrefixIsNotSource() {
        val ledger = PlaybackLedger(7, 10)
        val a = span(1, 20)
        val b = span(2, 15)
        ledger.append(a); ledger.append(b)
        ledger.submit(45)
        assertEquals(0L, ledger.playedFrames)
        assertTrue(ledger.observe(7, 10)!!.started.isEmpty())
        assertEquals(listOf(a), ledger.observe(7, 11)!!.started)
        val boundary = ledger.observe(7, 30)!!
        assertEquals(listOf(a), boundary.completed)
        assertTrue(boundary.started.isEmpty())
        val end = ledger.observe(7, 45)!!
        assertEquals(listOf(b), end.started)
        assertEquals(listOf(b), end.completed)
        assertTrue(ledger.observe(7, 45)!!.completed.isEmpty())
    }
    @Test fun staleEpochAndImpossibleProgressAreRejected() {
        val ledger = PlaybackLedger(1)
        ledger.append(span(1, 100))
        ledger.submit(50)
        assertNull(ledger.observe(2, 50))
        assertThrows(IllegalArgumentException::class.java) { ledger.observe(1, 51) }
        assertEquals(0L, ledger.playedFrames)
        ledger.observe(1, 25)
        assertThrows(IllegalArgumentException::class.java) { ledger.observe(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ledger.submit(51) }
    }
    @Test fun unsignedHeadRollover() {
        val ledger = PlaybackLedger(9)
        val a = span(1, 0x100000000L + 16)
        ledger.append(a)
        repeat(4) { i ->
            ledger.submit(0x40000000L)
            assertEquals((i + 1L) * 0x40000000L,
                ledger.observe(9, ((i + 1L) * 0x40000000L).toInt())!!.playedFrames)
        }
        ledger.submit(16)
        assertEquals(listOf(a), ledger.observe(9, 16)!!.completed)
    }
}
