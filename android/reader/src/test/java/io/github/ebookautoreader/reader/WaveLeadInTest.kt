package io.github.ebookautoreader.reader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveLeadInTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun prefixesOnlySilenceAndPreservesPcmAndOtherChunks() {
        val source = folder.newFile("source.wav")
        val target = folder.newFile("padded.wav")
        val pcm = ByteArray(960) { if (it < 160 || it % 2 == 0) 0 else 30 }
        val container = ByteArrayOutputStream().apply {
            write("WAVEfmt ".toByteArray())
            write(pcmFormat(16))
            write("JUNK".toByteArray())
            write(int(3))
            write(byteArrayOf(4, 5, 6, 0))
            write("data".toByteArray())
            write(int(pcm.size))
            write(pcm)
        }.toByteArray()
        source.writeBytes("RIFF".toByteArray() + int(container.size) + container)
        assertEquals(10, WaveLeadIn.firstSignalMs(source))
        assertEquals(280, WaveLeadIn.prepend(source, target, 280))
        assertEquals(290, WaveLeadIn.firstSignalMs(target))
        val bytes = target.readBytes()
        assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertArrayEquals(pcm, bytes.takeLast(pcm.size).toByteArray())
        assertEquals("JUNK", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }

    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun pcmFormat(bits: Int) = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(16).putShort(1).putShort(1).putInt(8_000)
        .putInt(8_000 * bits / 8).putShort((bits / 8).toShort()).putShort(bits.toShort()).array()
}
