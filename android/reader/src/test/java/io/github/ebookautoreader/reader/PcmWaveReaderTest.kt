package io.github.ebookautoreader.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWaveReaderTest {
    private fun wave(pad: Boolean = false): ByteArray {
        val extra = if (pad) 10 else 0
        val b = ByteBuffer.allocate(48 + extra).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { b.put(value.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); b.putInt(b.capacity() - 8); tag("WAVE")
        tag("fmt "); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(24_000); b.putInt(48_000); b.putShort(2); b.putShort(16)
        if (pad) { tag("JUNK"); b.putInt(1); b.put(7); b.put(0) }
        tag("data"); b.putInt(4); b.putShort(123); b.putShort(-456)
        return b.array()
    }

    @Test fun pcmAndPaddedUnknownChunk() {
        for (pad in listOf(false, true)) {
            val pcm = PcmWaveReader.decode(wave(pad))
            assertEquals(PcmFormat(24_000, 1, 16), pcm.format)
            assertEquals(2, pcm.frames)
            assertArrayEquals(byteArrayOf(123, 0, 56, -2), pcm.data)
        }
    }
    @Test fun rejectsTruncatedAndMisalignedPayload() {
        val truncated = wave().copyOf(47)
        assertThrows(IllegalArgumentException::class.java) { PcmWaveReader.decode(truncated) }
        val bad = wave().apply { this[40] = 3 }
        assertThrows(IllegalArgumentException::class.java) { PcmWaveReader.decode(bad) }
    }
    @Test fun rejectsNonPcmAndWrongFrameRate() {
        val nonPcm = wave().apply { this[20] = 3 }
        val wrongRate = wave().apply { this[28] = 0 }
        assertThrows(IllegalArgumentException::class.java) { PcmWaveReader.decode(nonPcm) }
        assertThrows(IllegalArgumentException::class.java) { PcmWaveReader.decode(wrongRate) }
    }
}
