package io.github.ebookautoreader.reader

import android.util.AtomicFile
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream

/** Only numeric positions and hashes; the existing cache remains the sole text store. */
internal class AcquisitionJournal(file: File) {
    companion object {
        const val NAME = "acquisition-trial-v1"
        private const val MAGIC = 0x45414331
    }
    private val file = AtomicFile(file)
    fun load(): AcquisitionProgress? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return try {
            DataInputStream(file.openRead()).use { input ->
                require(input.readInt() == MAGIC)
                val first = input.readInt()
                val last = input.readInt()
                val count = input.readInt()
                require(count in 0..1000)
                val progress = AcquisitionProgress(first, last, List(count) {
                    AcquiredPage(input.readInt(), input.readUTF(), input.readUTF())
                })
                require(input.read() == -1)
                progress
            }
        } catch (_: Exception) {
            throw ReaderFailure("前回の取得記録を開けません。", "acquire_checkpoint_invalid")
        }
    }
    fun save(progress: AcquisitionProgress) {
        val stream = file.startWrite()
        try {
            val out = DataOutputStream(stream)
            out.writeInt(MAGIC); out.writeInt(progress.first); out.writeInt(progress.last)
            out.writeInt(progress.pages.size)
            progress.pages.forEach { out.writeInt(it.number); out.writeUTF(it.exactKey); out.writeUTF(it.cacheKey) }
            out.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
    fun clear() = file.delete()
}
