package io.github.ebookautoreader.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PcmFormat(val sampleRate: Int, val channels: Int, val bits: Int) {
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2 && bits in listOf(8, 16))
    }
    val frameBytes: Int get() = channels * (bits / 8)
}

internal data class PcmWave(val format: PcmFormat, val data: ByteArray) {
    init { require(data.isNotEmpty() && data.size % format.frameBytes == 0) }
    val frames: Int get() = data.size / format.frameBytes
}

/** Accept uncompressed integer PCM RIFF/WAVE only; unknown chunks are skipped with padding. */
internal object PcmWaveReader {
    fun read(file: File): PcmWave {
        require(file.length() in 44..MAX_CLIP_BYTES)
        return decode(file.readBytes())
    }

    fun decode(bytes: ByteArray): PcmWave {
        require(bytes.size >= 44 && bytes.size.toLong() <= MAX_CLIP_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): Int = Integer.reverseBytes(input.int)
        fun ushort(): Int = input.short.toInt() and 0xffff
        fun uint(): Long = input.int.toLong() and 0xffffffffL
        require(tag() == 0x52494646 && uint() + 8L == bytes.size.toLong() && tag() == 0x57415645)
        var format: PcmFormat? = null
        var data: ByteArray? = null
        while (input.hasRemaining()) {
            require(input.remaining() >= 8)
            val chunk = tag()
            val size = uint()
            val start = input.position()
            val next = start.toLong() + size + (size and 1L)
            require(next <= bytes.size.toLong())
            when (chunk) {
                0x666d7420 -> {
                    require(format == null && size >= 16)
                    require(ushort() == 1)
                    val channels = ushort()
                    val rate = uint()
                    val byteRate = uint()
                    val align = ushort()
                    val bits = ushort()
                    require(rate in 8_000L..192_000L)
                    val parsed = PcmFormat(rate.toInt(), channels, bits)
                    require(align == parsed.frameBytes && byteRate == rate * align)
                    format = parsed
                }
                0x64617461 -> {
                    val pcm = requireNotNull(format)
                    require(data == null && size > 0 && size % pcm.frameBytes == 0L)
                    data = bytes.copyOfRange(start, start + size.toInt())
                }
            }
            input.position(next.toInt())
        }
        return PcmWave(requireNotNull(format), requireNotNull(data))
    }
}
