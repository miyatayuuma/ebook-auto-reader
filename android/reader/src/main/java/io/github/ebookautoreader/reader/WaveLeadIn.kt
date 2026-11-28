package io.github.ebookautoreader.reader

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

internal object WaveLeadIn {
    private data class Pcm(
        val rate: Int, val block: Int, val bits: Int, val dataHeader: Long, val dataSize: Int,
    )

    // Returns the length in source milliseconds so the playback cursor can exclude it.
    fun prepend(source: File, output: File, milliseconds: Int): Int? =
        RandomAccessFile(source, "r").use { input ->
            val pcm = readPcm(input) ?: return null
            val samples = pcm.rate * milliseconds / 1_000
            val bytes = samples * pcm.block
            val padding = bytes + ((pcm.dataSize + bytes) and 1) - (pcm.dataSize and 1)
            output.outputStream().buffered().use { destination ->
                input.seek(0)
                copy(input, destination, 4)
                destination.write(littleEndian(input.littleInt() + padding))
                copy(input, destination, pcm.dataHeader - 8)
                destination.write(littleEndian(input.littleInt() + bytes))
                destination.write(ByteArray(bytes) { if (pcm.bits == 8) 0x80.toByte() else 0 })
                copy(input, destination, pcm.dataSize.toLong())
                if (pcm.dataSize and 1 != 0) input.skipBytes(1)
                if ((pcm.dataSize + bytes) and 1 != 0) destination.write(0)
                copy(input, destination, input.length() - input.filePointer)
            }
            samples * 1_000 / pcm.rate
        }

    fun firstSignalMs(source: File): Int? = RandomAccessFile(source, "r").use { input ->
        val pcm = readPcm(input) ?: return null
        input.seek(pcm.dataHeader + 4)
        val frame = ByteArray(pcm.block)
        repeat(minOf(pcm.dataSize / pcm.block, pcm.rate * 2)) { index ->
            input.readFully(frame)
            val signal = if (pcm.bits == 8) {
                frame.any { abs((it.toInt() and 255) - 128) >= 2 }
            } else {
                (frame.indices step 2).any { offset ->
                    abs((frame[offset + 1].toInt() shl 8) or (frame[offset].toInt() and 255)) >= 128
                }
            }
            if (signal) return index * 1_000 / pcm.rate
        }
        null
    }

    private fun readPcm(input: RandomAccessFile): Pcm? {
        if (input.length() < 44 || input.readInt() != 0x52494646) return null // RIFF
        val riffSize = input.littleInt()
        if (riffSize.toLong() + 8 != input.length() || input.readInt() != 0x57415645) return null // WAVE
        var rate = 0
        var block = 0
        var bits = 0
        while (input.filePointer + 8 <= input.length()) {
            val tag = input.readInt()
            val sizeOffset = input.filePointer
            val size = input.littleInt()
            val next = input.filePointer + size + (size and 1)
            if (size < 0 || next > input.length()) return null
            when (tag) {
                0x666d7420 -> { // fmt
                    if (size < 16 || input.littleShort() != 1) return null
                    val channels = input.littleShort()
                    rate = input.littleInt()
                    input.skipBytes(4)
                    block = input.littleShort()
                    bits = input.littleShort()
                    if (rate !in 8_000..192_000 || channels !in 1..2 || bits !in listOf(8, 16) ||
                        block != channels * (bits / 8)) return null
                }
                0x64617461 -> { // data
                    if (rate == 0 || size < block || size % block != 0) return null
                    return Pcm(rate, block, bits, sizeOffset, size)
                }
            }
            input.seek(next)
        }
        return null
    }

    private fun RandomAccessFile.littleShort() = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
    private fun RandomAccessFile.littleInt() = Integer.reverseBytes(readInt())

    private fun littleEndian(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }

    private fun copy(source: RandomAccessFile, destination: java.io.OutputStream, count: Long) {
        val buffer = ByteArray(16_384)
        var remaining = count
        while (remaining > 0) {
            val length = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFully(buffer, 0, length)
            destination.write(buffer, 0, length)
            remaining -= length
        }
    }
}
